package dev.mkdev.dockerdashboard

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Verrou à l'ouverture : empreinte, visage, ou à défaut le code de l'appareil.
 * Le dashboard pilote les conteneurs (arrêt, suppression…) et la tablette
 * traîne dans la maison : on redemande qui tient l'écran.
 *
 * Empreinte ET code (BIOMETRIC_WEAK | DEVICE_CREDENTIAL) : Android impose alors
 * de ne pas fournir de bouton « Annuler » dans la fenêtre ; l'utilisateur
 * ressort avec Retour, et retombe sur cet écran.
 */
object Lock {
    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** null = utilisable ; sinon la raison, à afficher à côté de l'interrupteur. */
    fun unavailableReason(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Aucune empreinte ni code configuré sur l'appareil"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Pas de capteur biométrique"
        else -> "Indisponible sur cet appareil"
    }

    fun prompt(activity: FragmentActivity, onResult: (ok: Boolean, message: String?) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true, null)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Retour / clic hors de la fenêtre = pas une erreur à afficher.
                val quiet = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                onResult(false, if (quiet) null else errString.toString())
            }
            // onAuthenticationFailed = doigt non reconnu : la fenêtre reste ouverte, rien à faire.
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Docker Dashboard")
            .setSubtitle("Déverrouiller")
            .setAllowedAuthenticators(ALLOWED)
            .setConfirmationRequired(false)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}

@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit, onExit: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    fun ask() {
        if (asking) return
        asking = true; message = null
        Lock.prompt(activity) { ok, msg -> asking = false; if (ok) onUnlocked() else message = msg }
    }
    // La fenêtre système s'ouvre d'elle-même ; le bouton sert après un Retour.
    LaunchedEffect(Unit) { ask() }
    // Retour sur l'écran verrouillé = on sort (le dashboard dessous ne doit pas réagir).
    BackHandler { onExit() }

    Column(
        Modifier.fillMaxSize().background(DashBg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🐋", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("Docker Dashboard", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text("Verrouillé", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9AA0AE))
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("Quitter") }
            Button(onClick = { ask() }, enabled = !asking) { Text("🔓 Déverrouiller") }
        }
    }
}
