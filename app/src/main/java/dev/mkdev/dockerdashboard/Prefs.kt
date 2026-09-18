package dev.mkdev.dockerdashboard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les réglages de l'application : les dashboards mémorisés (profils : un
 * par NAS), celui qui est ouvert, et le verrou biométrique. Tout le reste
 * (mot de passe, thème, taille du texte…) vit dans le dashboard lui-même et
 * dans le stockage web de la WebView — qui sépare déjà les sessions par
 * origine, donc chaque NAS garde la sienne.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object Prefs {
    private val SERVER_URL = stringPreferencesKey("server_url") // < 0.1.7 : une seule adresse
    private val PROFILES = stringPreferencesKey("profiles")      // JSON [{id,name,url}]
    private val ACTIVE = stringPreferencesKey("active_profile")
    private val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")

    /** [serverName] = le nom que le dashboard s'est donné (Réglages › Général), relu à chaque affichage de la liste. */
    data class Profile(val id: String, val name: String, val url: String, val serverName: String = "", val mac: String = "") {
        /** Nom affiché : le nom donné, sinon celui du serveur, sinon l'hôte de l'URL. */
        val label: String get() = name.ifBlank { serverName.ifBlank { split(url).host.ifBlank { url } } }
    }

    private fun parse(json: String?): List<Profile> = try {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); Profile(o.getString("id"), o.optString("name"), o.getString("url"), o.optString("server"), o.optString("mac")) }
            .filter { it.url.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    private fun serialize(list: List<Profile>): String =
        JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("server", it.serverName).put("mac", it.mac)) } }.toString()

    fun newId(): String = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)

    /** Tous les profils ; l'ancienne adresse unique (< 0.1.7) devient le premier profil, une fois. */
    fun profiles(context: Context): Flow<List<Profile>> = context.dataStore.data.map { prefs ->
        val list = parse(prefs[PROFILES])
        val legacy = prefs[SERVER_URL]
        if (list.isEmpty() && !legacy.isNullOrBlank()) listOf(Profile("legacy", "", legacy)) else list
    }

    fun activeId(context: Context): Flow<String?> = context.dataStore.data.map { it[ACTIVE] }

    /** Ajoute ou remplace (même id) ; devient le profil ouvert. */
    suspend fun saveProfile(context: Context, profile: Profile) {
        context.dataStore.edit { prefs ->
            val list = migrated(prefs).filter { it.id != profile.id } + profile
            prefs[PROFILES] = serialize(list)
            prefs[ACTIVE] = profile.id
            prefs.remove(SERVER_URL)
        }
    }

    suspend fun deleteProfile(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val list = migrated(prefs).filter { it.id != id }
            prefs[PROFILES] = serialize(list)
            prefs.remove(SERVER_URL)
            if (prefs[ACTIVE] == id) { val next = list.firstOrNull(); if (next != null) prefs[ACTIVE] = next.id else prefs.remove(ACTIVE) }
        }
    }

    /** Mémorise le nom que le serveur vient d'annoncer (sans toucher au reste). */
    suspend fun setServerName(context: Context, id: String, serverName: String) {
        context.dataStore.edit { prefs ->
            val list = migrated(prefs)
            if (list.none { it.id == id && it.serverName != serverName }) return@edit
            prefs[PROFILES] = serialize(list.map { if (it.id == id) it.copy(serverName = serverName) else it })
            prefs.remove(SERVER_URL)
        }
    }

    /** MAC apprise auprès du dashboard (sans toucher au reste). */
    suspend fun setMac(context: Context, id: String, mac: String) {
        context.dataStore.edit { prefs ->
            val list = migrated(prefs)
            if (list.none { it.id == id && it.mac != mac }) return@edit
            prefs[PROFILES] = serialize(list.map { if (it.id == id) it.copy(mac = mac) else it })
            prefs.remove(SERVER_URL)
        }
    }

    suspend fun setActive(context: Context, id: String) {
        context.dataStore.edit { it[ACTIVE] = id }
    }

    private fun migrated(prefs: Preferences): List<Profile> {
        val list = parse(prefs[PROFILES])
        val legacy = prefs[SERVER_URL]
        return if (list.isEmpty() && !legacy.isNullOrBlank()) listOf(Profile("legacy", "", legacy)) else list
    }

    suspend fun currentProfiles(context: Context): List<Profile> = profiles(context).first()

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
