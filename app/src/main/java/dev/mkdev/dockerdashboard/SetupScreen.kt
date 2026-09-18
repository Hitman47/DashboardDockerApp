package dev.mkdev.dockerdashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Premier lancement : l'adresse du dashboard. « Tester » appelle
 * /api/auth/status (public) et lit le nom du dashboard dans la réponse,
 * ce qui prouve qu'on parle bien à un Docker Dashboard et pas à autre chose
 * qui écoute sur ce port.
 */
@Composable
fun SetupScreen(profile: Prefs.Profile?, others: List<Prefs.Profile> = emptyList(), canCancel: Boolean, onCancel: () -> Unit, onSaved: (Prefs.Profile) -> Unit, lockEnabled: Boolean = false, onLockChanged: (Boolean) -> Unit = {}) {
    // Trois champs séparés : sur un clavier de tablette, taper « : » au milieu
    // d'une IP est pénible. On les recompose en URL au moment de tester.
    val parts = remember(profile) { Prefs.split(profile?.url ?: "") }
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var mac by remember { mutableStateOf(profile?.mac ?: "") }
    var macStatus by remember { mutableStateOf<String?>(null) }
    var sshUser by remember { mutableStateOf(profile?.sshUser ?: "root") }
    var sshPort by remember { mutableStateOf((profile?.sshPort ?: 22).toString()) }
    var sshOpen by remember { mutableStateOf(false) }
    var pubKey by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var detecting by remember { mutableStateOf(false) }
    var https by remember { mutableStateOf(parts.https) }
    var host by remember { mutableStateOf(parts.host) }
    var port by remember { mutableStateOf(parts.port) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var foundName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val url = Prefs.join(https, host, port)
    val context = LocalContext.current
    val lockUnavailable = remember { Lock.unavailableReason(context) }
    // Retour = annuler la fiche (pas quitter l'app) quand il y a déjà un dashboard.
    BackHandler(enabled = canCancel) { onCancel() }

    fun test(thenSave: Boolean) {
        if (url.isEmpty()) return
        testing = true; result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { probe(url) }
            testing = false
            result = r
            if (r.first) foundName = r.second.removePrefix("Trouvé : ")
            // Sans nom saisi, le profil prend le nom que le dashboard s'est donné.
            if (thenSave && r.first) onSaved(Prefs.Profile(profile?.id ?: Prefs.newId(), name.trim(), url, serverName = foundName, mac = if (Wol.isValidMac(mac)) Wol.normalize(mac) else "", sshUser = sshUser.trim().ifBlank { "root" }, sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22))
        }
    }

    // Défilement : avec la section SSH ouverte (ou le clavier), le formulaire dépasse l'écran d'un téléphone.
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🐋", fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text(if (profile == null) "Nouveau dashboard" else "Modifier le dashboard", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("L'adresse de ton NAS et le port du dashboard.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9AA0AE))
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Nom (facultatif)") },
                placeholder = { Text(foundName.ifBlank { "NAS maison, NAS bureau…" }) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim(); result = null },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Adresse du NAS") },
                    placeholder = { Text("192.168.1.30") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter { it.isDigit() }.take(5); result = null },
                    modifier = Modifier.widthIn(min = 96.dp, max = 120.dp),
                    singleLine = true,
                    label = { Text("Port") },
                    placeholder = { Text("3000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { test(thenSave = true) }),
                )
            }
            Spacer(Modifier.height(10.dp))
            // La MAC s'apprend seule à la première connexion (dashboard ≥ 4.3.26) ; ici : détection à la
            // demande (ce dashboard s'il est connecté, sinon la table ARP des autres) ou saisie formatée.
            fun detect() {
                detecting = true; macStatus = null
                scope.launch {
                    val found = withContext(Dispatchers.IO) {
                        Wol.selfMac(url)?.let { it to "donnée par ce dashboard" }
                            ?: others.asSequence().mapNotNull { o -> Wol.lookupMac(o.url, host)?.let { it to "vue par ${o.label}" } }.firstOrNull()
                    }
                    detecting = false
                    if (found != null) { mac = found.first; macStatus = "MAC ${found.second}." }
                    else macStatus = "Introuvable : ouvre ce dashboard une fois (connecté), ou un autre dashboard qui a déjà parlé à ce NAS."
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = Wol.format(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = mac.isNotBlank() && !Wol.isValidMac(mac),
                    label = { Text("MAC (réveil WOL)") },
                    placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                )
                TextButton(onClick = { detect() }, enabled = !detecting && host.isNotBlank()) { Text(if (detecting) "…" else "Détecter") }
            }
            Text(
                macStatus ?: "Remplie toute seule à la première connexion. Sert à « Réveiller le NAS » quand il ne répond pas.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            // Secours SSH : indépendant du dashboard (il peut être mort), clé de l'app à autoriser sur le NAS.
            TextButton(onClick = { sshOpen = !sshOpen; if (sshOpen && pubKey == null) scope.launch { pubKey = runCatching { Ssh.publicKey(context) }.getOrElse { "Erreur : ${'$'}{it.message}" } } }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text((if (sshOpen) "▾ " else "▸ ") + "Secours SSH (réparer / redémarrer le NAS)", color = Color(0xFF9AA0AE))
            }
            if (sshOpen) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = sshUser, onValueChange = { sshUser = it.trim() }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Utilisateur SSH") }, placeholder = { Text("root") })
                    OutlinedTextField(value = sshPort, onValueChange = { v -> sshPort = v.filter { it.isDigit() }.take(5) }, modifier = Modifier.widthIn(min = 96.dp, max = 120.dp), singleLine = true, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Spacer(Modifier.height(8.dp))
                Text("1. Clé publique de l'app — à ajouter une fois sur le NAS :", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE))
                Text(pubKey ?: "Génération…", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8C1E8), modifier = Modifier.fillMaxWidth().padding(4.dp), fontFamily = FontFamily.Monospace, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pubKey?.let { clipboard.setText(AnnotatedString("mkdir -p ~/.ssh && echo '${'$'}it' >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys")) } }, enabled = pubKey != null) { Text("Copier la commande") }
                    TextButton(onClick = { pubKey?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = pubKey != null) { Text("Copier la clé") }
                }
                if (sshUser.isNotBlank() && sshUser != "root") {
                    Text("2. ${'$'}sshUser n'est pas root : autoriser sudo sans mot de passe (fichier /etc/sudoers.d/docker-dashboard) :", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE))
                    Text(Ssh.sudoersHint(sshUser), style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8C1E8), modifier = Modifier.fillMaxWidth().padding(4.dp), fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(Ssh.sudoersHint(sshUser))) }) { Text("Copier la ligne sudoers") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { Ssh.forgetHost(context, host) } }, enabled = host.isNotBlank()) { Text("Oublier l'empreinte", color = Color(0xFF9AA0AE)) }
                    TextButton(onClick = { scope.launch { pubKey = Ssh.regenerate(context) } }) { Text("Nouvelle clé", color = Color(0xFFFF6B6B)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = https, onCheckedChange = { https = it; result = null })
                Text("HTTPS (reverse proxy / certificat)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE))
            }
            if (url.isNotEmpty()) {
                Text(url, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = lockEnabled && lockUnavailable == null, onCheckedChange = onLockChanged, enabled = lockUnavailable == null)
                Text(
                    if (lockUnavailable == null) "🔒 Empreinte / visage / code à l'ouverture" else "🔒 Verrou : $lockUnavailable",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE),
                )
            }
            if (url.isNotEmpty() && Prefs.isPlainHttpOutsideLan(url)) {
                Spacer(Modifier.height(8.dp))
                Text("⚠ http:// hors du réseau local : le mot de passe circulerait en clair. Passe par un VPN (Tailscale) ou par https.", color = Color(0xFFF5B942), style = MaterialTheme.typography.bodySmall)
            }
            result?.let { (ok, msg) ->
                Spacer(Modifier.height(8.dp))
                Text((if (ok) "✅ " else "❌ ") + msg, color = if (ok) Color(0xFF4CD08A) else Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canCancel) TextButton(onClick = onCancel) { Text("Annuler") }
                TextButton(onClick = { test(thenSave = false) }, enabled = url.isNotEmpty() && !testing) { Text("Tester") }
                Button(onClick = { test(thenSave = true) }, enabled = url.isNotEmpty() && !testing) { Text("Ouvrir") }
                if (testing) CircularProgressIndicator(Modifier.height(20.dp).widthIn(max = 20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** → (ok, message). Jamais d'exception : le message explique l'échec. */
/** Interroge `/api/auth/status` (public) : le nom que le dashboard s'est donné, ou l'erreur si ce n'en est pas un / injoignable. */
fun probeDashboardName(base: String): Result<String> = runCatching {
    val conn = (URL("$base/api/auth/status").openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000; readTimeout = 5000
        setRequestProperty("Accept", "application/json")
    }
    val code = conn.responseCode
    if (code != 200) error("HTTP $code : ce n'est pas un Docker Dashboard ?")
    val json = JSONObject(conn.inputStream.bufferedReader().readText())
    if (!json.has("required")) error("Réponse inattendue : ce n'est pas un Docker Dashboard")
    json.optJSONObject("branding")?.optString("name").orEmpty().ifBlank { "Docker Dashboard" }
}

private fun probe(base: String): Pair<Boolean, String> = probeDashboardName(base).fold(
    onSuccess = { true to "Trouvé : $it" },
    onFailure = { e ->
        val m = e.message.orEmpty()
        false to when {
            m.startsWith("HTTP ") || m.startsWith("Réponse") -> m
            m.isNotBlank() -> "Injoignable ($m)"
            else -> "Injoignable"
        }
    },
)
