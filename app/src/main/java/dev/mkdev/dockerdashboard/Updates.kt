package dev.mkdev.dockerdashboard

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour de l'app : le dépôt GitHub est privé, donc c'est le dashboard
 * qui répond (il détient le jeton GitHub, côté serveur) — /api/app/latest
 * puis /api/app/apk/<id>. On s'authentifie avec le cookie de session de la
 * WebView : tant que l'utilisateur n'est pas connecté, la vérification se
 * tait (NotSignedIn) et l'écran la retente un peu plus tard.
 */
object Updates {
    data class Release(val version: String, val apkUrl: String, val apkName: String, val sizeBytes: Long, val pageUrl: String)

    sealed class Result {
        /** Pas encore connecté au dashboard (pas de cookie, ou 401) : réessayer plus tard. */
        object NotSignedIn : Result()
        /** Réponse obtenue : rien à proposer (à jour, ou pas de jeton côté NAS), ou hors ligne. */
        object Nothing : Result()
        data class Available(val release: Release) : Result()
    }

    fun check(serverUrl: String): Result = try {
        if (!Session.has(serverUrl)) Result.NotSignedIn else {
            val conn = (URL("$serverUrl/api/app/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                Session.apply(this, serverUrl)
            }
            when (conn.responseCode) {
                401 -> Result.NotSignedIn
                200 -> {
                    val j = JSONObject(conn.inputStream.bufferedReader().readText())
                    val apk = j.optJSONObject("apk")
                    val version = j.optString("version")
                    if (!j.optBoolean("available") || apk == null || !isNewer(version, BuildConfig.VERSION_NAME)) Result.Nothing
                    else Result.Available(Release(version, serverUrl + apk.getString("url"), apk.optString("name", "DockerDashboard-$version.apk"), apk.optLong("size"), j.optString("url")))
                }
                else -> Result.Nothing
            }
        }
    } catch (_: Exception) { Result.Nothing }

    /** "0.1.10" > "0.1.9" ; suffixes ignorés ("0.2.0-rc1" = 0.2.0). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Télécharge via DownloadManager dans Téléchargements ; à la fin, la
     * notification ouvre l'installateur Android (type MIME APK). Pas de
     * FileProvider ni de permission d'installation à déclarer.
     */
    fun download(ctx: Context, release: Release) {
        val req = DownloadManager.Request(Uri.parse(release.apkUrl)).apply {
            setMimeType("application/vnd.android.package-archive")
            setTitle("Docker Dashboard ${release.version}")
            setDescription("Ouvre la notification pour installer")
            CookieManager.getInstance().getCookie(release.apkUrl)?.let { addRequestHeader("Cookie", it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, release.apkName)
        }
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(ctx, "Téléchargement de ${release.apkName}… puis appuie sur la notification pour installer.", Toast.LENGTH_LONG).show()
    }
}
