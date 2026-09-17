package dev.mkdev.dockerdashboard

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

/** Couleurs du dashboard (base.css) pour que les bords / écrans natifs ne jurent pas. */
val DashBg = Color(0xFF0A0B0E)
val DashAccent = Color(0xFF4A6CF7)

/**
 * Trois écrans : la fiche d'un dashboard (adresse ; premier lancement, ajout,
 * modification), la liste des dashboards mémorisés (un par NAS) et le
 * dashboard ouvert dans une WebView. Le dashboard est déjà responsive : le
 * même site s'affiche en une colonne sur téléphone et en grille sur tablette,
 * la taille du texte se règle dans ses Réglages.
 *
 * FragmentActivity (et non ComponentActivity) : exigé par BiometricPrompt.
 */
class MainActivity : FragmentActivity() {
    /** Verrou posé au démarrage et quand on revient après un moment ailleurs. */
    private val locked = mutableStateOf(true)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = DashAccent, background = DashBg, surface = Color(0xFF13151B))) {
                val scope = rememberCoroutineScope()
                val profiles by Prefs.profiles(this).collectAsState(initial = null)
                val activeId by Prefs.activeId(this).collectAsState(initial = null)
                val lockEnabled by Prefs.biometricLock(this).collectAsState(initial = null)
                // null = pas de fiche ouverte ; Profile(id = "") = nouveau
                var editing by remember { mutableStateOf<Prefs.Profile?>(null) }
                var showList by remember { mutableStateOf(false) }
                val list = profiles
                val active = list?.let { l -> l.firstOrNull { it.id == activeId } ?: l.firstOrNull() }

                Box(Modifier.fillMaxSize().background(DashBg)) {
                    when {
                        list == null || lockEnabled == null -> Unit // premier rendu : fond sombre, rien à afficher
                        list.isEmpty() || editing != null -> SetupScreen(
                            profile = editing?.takeIf { it.id.isNotEmpty() },
                            canCancel = list.isNotEmpty(),
                            onCancel = { editing = null },
                            onSaved = { p -> scope.launch { Prefs.saveProfile(this@MainActivity, p); editing = null; showList = false } },
                            lockEnabled = lockEnabled == true,
                            // Celui qui active le verrou est devant l'écran : pas de demande immédiate.
                            onLockChanged = { on -> scope.launch { Prefs.setBiometricLock(this@MainActivity, on) }; if (on) locked.value = false },
                        )
                        showList || active == null -> ProfilesScreen(
                            profiles = list, activeId = active?.id,
                            onOpen = { p -> scope.launch { Prefs.setActive(this@MainActivity, p.id); showList = false } },
                            onEdit = { p -> editing = p },
                            onDelete = { p -> scope.launch { Prefs.deleteProfile(this@MainActivity, p.id) } },
                            onAdd = { editing = Prefs.Profile("", "", "") },
                            onBack = { showList = false },
                            onServerName = { p, n -> scope.launch { Prefs.setServerName(this@MainActivity, p.id, n) } },
                        )
                        else -> DashboardScreen(
                            profile = active,
                            others = list.filter { it.id != active.id },
                            onChangeServer = { editing = active },
                            onSwitch = { showList = true },
                            onExit = { finish() },
                        )
                    }
                    // Par-dessus tout, la WebView reste vivante dessous (pas de rechargement au déverrouillage).
                    if (lockEnabled == true && locked.value && !list.isNullOrEmpty()) {
                        LockScreen(activity = this@MainActivity, onUnlocked = { locked.value = false }, onExit = { finish() })
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Un aller-retour vers Chrome (lien ↗) ne redemande pas l'empreinte ; une vraie absence, si.
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) locked.value = true
    }

    private companion object {
        const val RELOCK_AFTER_MS = 30_000L
    }
}
