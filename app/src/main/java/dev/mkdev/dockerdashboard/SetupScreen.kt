package dev.mkdev.dockerdashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
fun SetupScreen(profile: Prefs.Profile?, canCancel: Boolean, onCancel: () -> Unit, onSaved: (Prefs.Profile) -> Unit, lockEnabled: Boolean = false, onLockChanged: (Boolean) -> Unit = {}) {
    // Trois champs séparés : sur un clavier de tablette, taper « : » au milieu
    // d'une IP est pénible. On les recompose en URL au moment de tester.
    val parts = remember(profile) { Prefs.split(profile?.url ?: "") }
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var mac by remember { mutableStateOf(profile?.mac ?: "") }
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
            if (thenSave && r.first) onSaved(Prefs.Profile(profile?.id ?: Prefs.newId(), name.trim(), url, serverName = foundName, mac = if (Wol.isValidMac(mac)) Wol.normalize(mac) else ""))
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
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
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = mac.isNotBlank() && !Wol.isValidMac(mac),
                label = { Text("Adresse MAC (réveil Wake-on-LAN, facultatif)") },
                placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                supportingText = { Text("Dans les réglages réseau du NAS. Permet « Réveiller le NAS » quand il ne répond pas.", style = MaterialTheme.typography.bodySmall) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            )
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
