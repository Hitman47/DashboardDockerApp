package dev.mkdev.dockerdashboard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Les réglages de l'application : l'adresse du dashboard et le verrou
 * biométrique. Tout le reste (mot de passe, thème, taille du texte…) vit dans
 * le dashboard lui-même et dans le stockage web de la WebView.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object Prefs {
    private val SERVER_URL = stringPreferencesKey("server_url")
    private val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")

    fun serverUrl(context: Context): Flow<String?> = context.dataStore.data.map { it[SERVER_URL] }

    suspend fun setServerUrl(context: Context, url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(SERVER_URL) else prefs[SERVER_URL] = url
        }
    }

    /** Empreinte / visage / code de l'appareil demandé à l'ouverture (défaut : non). */
    fun biometricLock(context: Context): Flow<Boolean> = context.dataStore.data.map { it[BIOMETRIC_LOCK] ?: false }

    suspend fun setBiometricLock(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[BIOMETRIC_LOCK] = enabled }
    }

    /**
     * "192.168.1.30:3000" → "http://192.168.1.30:3000" ; trailing slash retiré.
     * Sans schéma on suppose http : c'est le cas normal d'un NAS sur le LAN.
     */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        return url.trimEnd('/')
    }

    data class Parts(val https: Boolean, val host: String, val port: String)

    /** "http://192.168.1.30:3000" → (https=false, "192.168.1.30", "3000") ; vide → défauts. */
    fun split(url: String): Parts {
        if (url.isBlank()) return Parts(false, "", "3000")
        val https = url.startsWith("https://")
        val rest = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val m = Regex("^(.*):(\\d{1,5})$").find(rest)
        return if (m != null) Parts(https, m.groupValues[1], m.groupValues[2])
        else Parts(https, rest, if (https) "443" else "80")
    }

    /** Recompose l'URL ; le port par défaut du schéma n'est pas répété. */
    fun join(https: Boolean, host: String, port: String): String {
        val h = host.trim().trimEnd('/').removePrefix("https://").removePrefix("http://")
        if (h.isEmpty()) return ""
        val p = port.trim()
        val default = if (https) "443" else "80"
        return "${if (https) "https" else "http"}://$h${if (p.isEmpty() || p == default) "" else ":$p"}"
    }

    /** Une adresse http:// hors LAN/VPN transporte le mot de passe en clair : à signaler, pas à interdire. */
    fun isPlainHttpOutsideLan(url: String): Boolean {
        if (!url.startsWith("http://")) return false
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".ts.net")) return false
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").find(host) ?: return !host.contains('.') // nom court = LAN
        val (a, b) = m.destructured.toList().map { it.toInt() }
        return !(a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || a == 127 || a == 100 && b in 64..127)
    }
}
