package dev.mkdev.dockerdashboard

import android.webkit.CookieManager
import java.net.HttpURLConnection

/**
 * Comment l'app s'authentifie auprès d'un dashboard pour ses propres appels
 * (mise à jour, WOL, MAC) : le cookie de session de la WebView, et — pour le
 * dashboard ouvert — le jeton que la page garde dans son localStorage, lu
 * après chaque chargement et conservé en mémoire seulement (jamais écrit).
 * Les deux portent la même session ; le jeton survit à l'expiration du cookie
 * sur les dashboards < 4.3.27.
 */
object Session {
    /** serverUrl → jeton `dd_token` de la page (mémoire vive). */
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun remember(serverUrl: String, token: String?) {
        if (token.isNullOrBlank() || token == "null") tokens.remove(serverUrl) else tokens[serverUrl] = token
    }

    /** true si on a de quoi s'authentifier (cookie ou jeton). */
    fun has(serverUrl: String): Boolean {
        val cookie = CookieManager.getInstance().getCookie(serverUrl)
        return tokens.containsKey(serverUrl) || (!cookie.isNullOrBlank() && cookie.contains("dd_session="))
    }

    /** Pose cookie + jeton sur la connexion. */
    fun apply(conn: HttpURLConnection, serverUrl: String) {
        CookieManager.getInstance().getCookie(serverUrl)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        tokens[serverUrl]?.let { conn.setRequestProperty("X-Auth-Token", it) }
    }
}
