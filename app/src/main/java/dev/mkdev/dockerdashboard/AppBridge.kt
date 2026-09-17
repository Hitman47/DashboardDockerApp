package dev.mkdev.dockerdashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pont exposé au dashboard sous `window.DockerDashboardApp` : le site affiche
 * alors, dans son en-tête, un bouton « ⇄ » (liste des dashboards de l'app,
 * modifier, ajouter) ; `saveFile` sert aux téléchargements blob:. Seules des
 * actions d'interface : rien de sensible.
 * Les méthodes sont appelées sur un thread JS → on repasse sur le principal.
 * Créé une fois avec la WebView : tout est lu via des lambdas pour suivre
 * les recompositions (profil actif, callbacks).
 */
class AppBridge(
    ctx: Context,
    private val current: () -> Prefs.Profile,
    private val allProfiles: () -> List<Prefs.Profile>,
    private val onOpen: (String) -> Unit,
    private val onEdit: () -> Unit,
    private val onAdd: () -> Unit,
    private val onList: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val saver = BlobSaver(ctx)

    /** Téléchargements blob: (export de config…) — voir BlobSaver. */
    @JavascriptInterface fun saveFile(base64: String, name: String, mime: String) = saver.saveFile(base64, name, mime)

    @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME

    /** JSON [{id, label, serverName, url, active}] */
    @JavascriptInterface fun profiles(): String = JSONArray().apply {
        allProfiles().forEach { p ->
            put(JSONObject().put("id", p.id).put("label", p.label).put("serverName", p.serverName).put("url", p.url).put("active", p.id == current().id))
        }
    }.toString()

    @JavascriptInterface fun openDashboard(id: String) { main.post { onOpen(id) } }
    @JavascriptInterface fun editDashboard() { main.post { onEdit() } }
    @JavascriptInterface fun addDashboard() { main.post { onAdd() } }
    @JavascriptInterface fun switchDashboard() { main.post { onList() } }
}
