package dev.mkdev.dockerdashboard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Le seul réglage de l'application : l'adresse du dashboard. Tout le reste
 * (mot de passe, thème, taille du texte…) vit dans le dashboard lui-même et
 * dans le stockage web de la WebView.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object Prefs {
    private val SERVER_URL = stringPreferencesKey("server_url")

    fun serverUrl(context: Context): Flow<String?> = context.dataStore.data.map { it[SERVER_URL] }

    suspend fun setServerUrl(context: Context, url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(SERVER_URL) else prefs[SERVER_URL] = url
        }
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
