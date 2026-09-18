package dev.mkdev.dockerdashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour de l'app : le dépôt GitHub est privé, donc c'est le dashboard
 * qui répond (il détient le jeton GitHub, côté serveur) — /api/app/latest
 * puis /api/app/apk/<id>. On s'authentifie comme la page (cookie + jeton,
 * voir Session) : tant que l'utilisateur n'est pas connecté, la vérification
 * se tait (NotSignedIn) et l'écran la retente un peu plus tard.
 *
 * Installation en un geste : l'APK est téléchargé par l'app elle-même (dans
 * son cache, avec la session — pas de DownloadManager, qui n'avait pas le
 * jeton et laissait l'utilisateur chercher une notification), puis remis à
 * PackageInstaller. Comme l'app se met à jour elle-même, Android 12+ ne
 * demande aucune confirmation (USER_ACTION_NOT_REQUIRED +
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION) ; la seule étape manuelle, une fois,
 * est l'autorisation « installer des apps de cette source ». Après la
 * réinstallation, RelaunchReceiver rouvre l'app.
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

    /** Où en est l'installation, affiché dans le bandeau. */
    sealed class State {
        object Idle : State()
        data class Downloading(val percent: Int) : State()
        object Installing : State()
        /** Android attend un geste de l'utilisateur (autorisation de la source, 1re fois). */
        object WaitingUser : State()
        data class Failed(val message: String) : State()
    }

    /** État partagé avec InstallReceiver (qui n'a pas accès à l'écran). */
    val state = MutableStateFlow<State>(State.Idle)
    /** APK téléchargé, pour relancer l'installation sans re-télécharger (après l'autorisation de la source). */
    @Volatile private var lastFile: File? = null
    /** Demande de permission « notifications » en cours : complétée par MainActivity.onRequestPermissionsResult. */
    @Volatile var notifPermission: CompletableDeferred<Unit>? = null
    const val NOTIF_REQUEST = 41

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

    /** Télécharge l'APK dans le cache (avec la session), puis le donne à PackageInstaller. */
    suspend fun downloadAndInstall(ctx: Context, serverUrl: String, release: Release) {
        val app = ctx.applicationContext
        try {
            state.value = State.Downloading(0)
            val file = withContext(Dispatchers.IO) { download(app, serverUrl, release) }
            lastFile = file
            // Si la question « autoriser les notifications ? » est encore à l'écran, on attend la réponse :
            // sinon l'app serait remplacée avant, et la notification « toucher pour rouvrir » perdue.
            notifPermission?.let { withTimeoutOrNull(60_000) { it.await() } }
            state.value = State.Installing
            withContext(Dispatchers.IO) { install(app, file) }
        } catch (e: Exception) {
            state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun download(ctx: Context, serverUrl: String, release: Release): File {
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 60_000
            Session.apply(this, serverUrl)
        }
        when (conn.responseCode) {
            200 -> {}
            401 -> throw IllegalStateException("Session expirée : reconnecte-toi au dashboard, puis réessaie.")
            else -> throw IllegalStateException("Le dashboard a répondu HTTP ${conn.responseCode}.")
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, release.apkName.substringAfterLast('/'))
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L; var last = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                    if (pct != last) { last = pct; state.value = State.Downloading(pct) }
                }
            }
        }
        if (file.length() < 100_000) throw IllegalStateException("APK incomplet (${file.length()} octets).")
        return file
    }

    private fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            setAppPackageName(ctx.packageName)
            // Pas de dialogue quand l'app se met à jour elle-même (Android 12+, voir l'en-tête).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            // Mutable : c'est le système qui remplit les extras (statut, intent de confirmation).
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    /** Retour de PackageInstaller. STATUS_SUCCESS n'arrive jamais ici : l'app a été remplacée entre-temps. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // 1re fois : Android demande d'autoriser l'app à installer (et, avant Android 12, confirme).
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        state.value = State.WaitingUser
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { state.value = State.Failed("Impossible d'ouvrir la confirmation : ${it.message}") }
                    } else state.value = State.Failed("Confirmation demandée mais introuvable.")
                }
                PackageInstaller.STATUS_SUCCESS -> state.value = State.Idle
                // Retour de l'écran « Autoriser cette source » : Android annule la session en cours.
                // Si l'autorisation vient d'être donnée, on repart aussitôt avec l'APK déjà téléchargé.
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    val file = lastFile
                    if (state.value == State.WaitingUser && file?.exists() == true && context.packageManager.canRequestPackageInstalls()) {
                        state.value = State.Installing
                        val pending = goAsync()
                        Thread {
                            runCatching { install(context.applicationContext, file) }.onFailure { state.value = State.Failed("Échec : ${it.message}") }
                            pending.finish()
                        }.start()
                    } else state.value = State.Failed("Installation annulée.")
                }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                    state.value = State.Failed(when (status) {
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Pas assez d'espace."
                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Signature différente : désinstalle l'ancienne app d'abord ($msg)."
                        PackageInstaller.STATUS_FAILURE_INVALID -> "APK invalide ($msg)."
                        else -> "Échec : $msg"
                    })
                }
            }
        }
        companion object { const val ACTION = "dev.mkdev.dockerdashboard.INSTALL_STATUS" }
    }

    /**
     * L'app vient d'être remplacée. Android 10+ interdit de la rouvrir depuis un
     * receiver (« background activity launch blocked ») : on pose une
     * notification dont le toucher la rouvre, et on tente quand même le
     * lancement direct pour les Android plus anciens.
     */
    class RelaunchReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            File(context.cacheDir, "apk").deleteRecursively()
            val launch = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mises à jour de l'app", NotificationManager.IMPORTANCE_HIGH).apply { description = "Rouvrir l'app après une mise à jour" })
            val tap = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Docker Dashboard ${BuildConfig.VERSION_NAME} installé")
                .setContentText("Touche pour rouvrir l'app.")
                .setContentIntent(tap).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
                .build()
            runCatching { nm.notify(1, n) }
            if (Build.VERSION.SDK_INT < 29) runCatching { context.startActivity(launch) }
        }
        companion object { const val CHANNEL = "app-updates" }
    }
}
