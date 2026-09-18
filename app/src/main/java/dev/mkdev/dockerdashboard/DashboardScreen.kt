package dev.mkdev.dockerdashboard

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Le dashboard, plein écran, dans une WebView :
 *  - JS + stockage web + cookies persistants → on reste connecté (dd_token /
 *    dd_session) et le WebSocket « Live » fonctionne ;
 *  - les liens vers d'autres origines (↗ Ouvrir une WebUI, liens des blocs)
 *    sortent dans le navigateur, le dashboard reste ici ;
 *  - hors ligne / NAS éteint : un écran d'erreur qui réessaie tout seul ;
 *  - Retour = historique de la page, puis un petit menu (recharger, autres
 *    dashboards, modifier, quitter) ;
 *  - envoi de fichiers (fonds d'écran, import de config) et téléchargements
 *    (export de config, ICS) pris en charge.
 */
@Composable
fun DashboardScreen(profile: Prefs.Profile, others: List<Prefs.Profile> = emptyList(), onChangeServer: () -> Unit, onSwitch: () -> Unit, onExit: () -> Unit, onOpenProfile: (String) -> Unit = {}, onAddProfile: () -> Unit = {}, onMacLearned: (String) -> Unit = {}) {
    val serverUrl = profile.url
    // key(url) : changer de dashboard = une autre WebView (sessions séparées par origine).
    key(serverUrl) {
        var webView by remember { mutableStateOf<WebView?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var wakeStatus by remember { mutableStateOf<String?>(null) }
        // La WebView (et son pont JS) survit aux recompositions : on lui donne toujours l'état courant.
        val latestProfile by rememberUpdatedState(profile)
        val latestOthers by rememberUpdatedState(others)
        val latestOpen by rememberUpdatedState(onOpenProfile)
        val latestEdit by rememberUpdatedState(onChangeServer)
        val latestAdd by rememberUpdatedState(onAddProfile)
        val latestSwitch by rememberUpdatedState(onSwitch)
        val scope = rememberCoroutineScope()
        var progress by remember { mutableIntStateOf(0) }
        var showMenu by remember { mutableStateOf(false) }
        var pendingFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
        // Nouvelle version de l'app signalée par le dashboard (voir Updates.kt).
        var update by remember { mutableStateOf<Updates.Release?>(null) }
        var updateDismissed by remember { mutableStateOf(false) }
        var updateTick by remember { mutableIntStateOf(0) }
        val context = LocalContext.current
        // Relancé à chaque page chargée. La connexion se fait dans la page sans
        // rechargement : tant qu'on n'est pas connecté, on retente toutes les 20 s.
        LaunchedEffect(updateTick) {
            if (updateTick == 0 || update != null || updateDismissed) return@LaunchedEffect
            while (true) {
                webView?.evaluateJavascript("(function(){try{return localStorage.getItem('dd_token')||''}catch(e){return ''}})()") { Session.remember(serverUrl, it?.trim('"')) }
                when (val r = withContext(Dispatchers.IO) { Updates.check(serverUrl) }) {
                    is Updates.Result.Available -> { update = r.release; return@LaunchedEffect }
                    Updates.Result.Nothing -> return@LaunchedEffect
                    Updates.Result.NotSignedIn -> delay(20_000)
                }
            }
        }
        // La MAC du NAS s'apprend toute seule : dès qu'on est connecté, le dashboard la donne
        // (dashboard ≥ 4.3.26). Réessai toutes les 20 s tant que la session n'est pas ouverte.
        LaunchedEffect(updateTick, profile.id) {
            if (updateTick == 0 || profile.mac.isNotBlank()) return@LaunchedEffect
            repeat(30) {
                // Connexion faite dans la page sans rechargement : on relit le jeton avant chaque essai.
                webView?.evaluateJavascript("(function(){try{return localStorage.getItem('dd_token')||''}catch(e){return ''}})()") { Session.remember(serverUrl, it?.trim('"')) }
                val mac = withContext(Dispatchers.IO) { Wol.selfMac(serverUrl) }
                if (mac != null) { onMacLearned(mac); return@LaunchedEffect }
                delay(20_000)
            }
        }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            pendingFiles?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            pendingFiles = null
        }
        val lifecycleOwner = LocalLifecycleOwner.current

        BackHandler {
            val wv = webView
            if (wv != null && wv.canGoBack()) wv.goBack() else showMenu = true
        }

        // NAS qui redémarre, Wi-Fi qui revient : on réessaie sans rien demander.
        LaunchedEffect(error) { if (error != null) { delay(5_000); webView?.reload() } }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> { webView?.onPause(); CookieManager.getInstance().flush() }
                    Lifecycle.Event.ON_RESUME -> webView?.onResume()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer); webView?.destroy(); webView = null }
        }

        Box(Modifier.fillMaxSize().background(DashBg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createWebView(
                        ctx, serverUrl,
                        bridge = AppBridge(ctx, current = { latestProfile }, allProfiles = { listOf(latestProfile) + latestOthers }, onOpen = { latestOpen(it) }, onEdit = { latestEdit() }, onAdd = { latestAdd() }, onList = { latestSwitch() }),
                        onProgress = { progress = it },
                        onError = { error = it },
                        onLoaded = { error = null; updateTick++ },
                        onToken = { Session.remember(serverUrl, it) },
                        onFileChooser = { cb, accept -> pendingFiles?.onReceiveValue(null); pendingFiles = cb; filePicker.launch(accept) },
                    ).also { webView = it; it.loadUrl(serverUrl) }
                },
            )
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = DashAccent, trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {})
            }
            error?.let { msg ->
                ErrorOverlay(
                    msg, onRetry = { webView?.reload() }, onChangeServer = onChangeServer,
                    canWake = profile.mac.isNotBlank(), wakeStatus = wakeStatus,
                    onWake = {
                        wakeStatus = "Envoi du paquet magique…"
                        scope.launch {
                            // Wi-Fi : direct depuis le téléphone ; et par chaque autre dashboard connecté (marche aussi en 4G).
                            val report = withContext(Dispatchers.IO) {
                                val parts = mutableListOf<String>()
                                val bc = Wol.wifiBroadcast(context)
                                if (bc != null) runCatching { Wol.sendLocal(profile.mac, bc) }.onSuccess { parts += "Wi-Fi : $it paquets" }.onFailure { parts += "Wi-Fi : ${it.message}" }
                                for (o in others) Wol.sendVia(o.url, profile.mac, profile.label)?.let { parts += "via ${o.label} : $it" }
                                parts
                            }
                            wakeStatus = if (report.isEmpty()) "Rien envoyé : pas en Wi-Fi, et aucun autre dashboard connecté pour relayer."
                            else "Envoyé (${report.joinToString(" · ")}). Le NAS met ~1 min à démarrer, l'app réessaie toute seule."
                        }
                    },
                )
            }
            update?.takeIf { !updateDismissed }?.let { rel ->
                UpdateBanner(rel, modifier = Modifier.align(Alignment.BottomCenter),
                    onInstall = { Updates.download(context, rel) }, // le bandeau reste : on peut relancer si le téléchargement échoue
                    onDismiss = { updateDismissed = true })
            }
        }

        if (showMenu) {
            AlertDialog(
                onDismissRequest = { showMenu = false },
                title = { Text(profile.label) },
                text = { Text(serverUrl, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE)) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { showMenu = false; webView?.reload() }) { Text("↻ Recharger") }
                        TextButton(onClick = { showMenu = false; onSwitch() }) { Text("⇄ Mes dashboards") }
                        TextButton(onClick = { showMenu = false; onChangeServer() }) { Text("✎ Modifier ce dashboard") }
                        TextButton(onClick = { showMenu = false; onExit() }) { Text("✕ Quitter") }
                    }
                },
                dismissButton = { TextButton(onClick = { showMenu = false }) { Text("Annuler") } },
            )
        }
    }
}

@Composable
private fun UpdateBanner(release: Updates.Release, modifier: Modifier, onInstall: () -> Unit, onDismiss: () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth().padding(12.dp), color = Color(0xFF1B2A6B), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Docker Dashboard ${release.version} disponible", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text("Tu as la ${BuildConfig.VERSION_NAME} · ${release.sizeBytes / 1_000_000} Mo", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8C1E8))
            }
            TextButton(onClick = onInstall) { Text("Installer", color = Color.White) }
            TextButton(onClick = onDismiss) { Text("✕", color = Color(0xFFB8C1E8)) }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onChangeServer: () -> Unit, canWake: Boolean = false, wakeStatus: String? = null, onWake: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().background(DashBg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🐋", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text("Dashboard injoignable", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9AA0AE))
        Spacer(Modifier.height(4.dp))
        Text("Nouvel essai automatique toutes les 5 s.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onChangeServer) { Text("Modifier") }
            Button(onClick = onRetry) { Text("Réessayer") }
        }
        if (canWake) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onWake, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B2A6B), contentColor = Color.White)) { Text("⚡ Réveiller le NAS (WOL)") }
            wakeStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8C1E8), textAlign = TextAlign.Center)
            }
        }
    }
}

// ── WebView ──────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    ctx: Context,
    serverUrl: String,
    bridge: AppBridge,
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit,
    onLoaded: () -> Unit,
    onFileChooser: (ValueCallback<Array<Uri>>, String) -> Unit,
    onToken: (String?) -> Unit = {},
): WebView {
    val origin = Uri.parse(serverUrl)
    // Inspection chrome://inspect depuis un PC en USB, seulement si le débogage USB est
    // activé sur l'appareil (donc par son propriétaire) : diagnostic d'affichage sans build debug.
    if (BuildConfig.DEBUG || android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
    val webView = WebView(ctx)
    // Sans ceci la WebView est mesurée en WRAP_CONTENT : Chromium prend alors une
    // hauteur de viewport de 0 et les unités `vh` valent 0 px → les fenêtres du
    // dashboard (max-height: 90vh) s'affichaient écrasées sur une ligne.
    webView.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    webView.setBackgroundColor(0xFF0A0B0E.toInt())
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = "$userAgentString DockerDashboardApp/${BuildConfig.VERSION_NAME}"
    }
    CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(webView, true) }

    fun isSameOrigin(u: Uri) = u.scheme == origin.scheme && u.host.equals(origin.host, ignoreCase = true) && u.port == origin.port
    fun openExternal(u: Uri) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(ctx, "Aucune application pour ouvrir $u", Toast.LENGTH_SHORT).show() }
    }

    var failed = false
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "http" || u.scheme == "https") { if (isSameOrigin(u)) return false; openExternal(u); return true }
            openExternal(u) // mailto:, tel:, intent: …
            return true
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { failed = false; onProgress(5) }
        override fun onPageFinished(view: WebView, url: String?) {
            onProgress(100)
            if (failed) return
            // Le jeton de session de la page (posé au login) : l'app l'utilise pour ses propres appels.
            view.evaluateJavascript("(function(){try{return localStorage.getItem('dd_token')||''}catch(e){return ''}})()") { onToken(it?.trim('"')) }
            onLoaded()
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
            if (!request.isForMainFrame) return
            failed = true
            onError(describe(err.errorCode, err.description?.toString()))
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame || response.statusCode < 500) return
            failed = true
            onError("Le serveur répond HTTP ${response.statusCode} (reverse proxy sans dashboard derrière ?)")
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { onProgress(newProgress) }
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val accept = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
            onFileChooser(callback, if (accept.startsWith(".")) "*/*" else accept)
            return true
        }
        // target="_blank" et window.open() : on récupère l'URL dans une WebView jetable, puis navigateur externe.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val temp = WebView(view.context)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (isSameOrigin(request.url)) view.loadUrl(request.url.toString()) else openExternal(request.url)
                    v.post { v.destroy() }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    // Pont `window.DockerDashboardApp` : liste/changement de dashboard pour l'en-tête du site,
    // et `saveFile` pour les téléchargements blob:. Seule l'origine du dashboard est chargée ici
    // (les autres liens partent dans le navigateur).
    webView.addJavascriptInterface(bridge, "DockerDashboardApp")
    webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
        if (url.startsWith("blob:")) {
            val name = guessBlobName(mimeType)
            webView.evaluateJavascript(blobToBridgeJs(url, name), null)
        } else {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(ctx, "Téléchargement de $name…", Toast.LENGTH_SHORT).show()
        }
    }
    return webView
}

private fun describe(code: Int, description: String?): String = when (code) {
    WebViewClient.ERROR_HOST_LOOKUP -> "Adresse introuvable (DNS)"
    WebViewClient.ERROR_CONNECT -> "Connexion refusée : le conteneur tourne ? le port est le bon ?"
    WebViewClient.ERROR_TIMEOUT -> "Délai dépassé : NAS éteint ou réseau/VPN coupé ?"
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "Certificat HTTPS refusé"
    else -> description?.takeIf { it.isNotBlank() } ?: "Erreur réseau ($code)"
}

private fun guessBlobName(mime: String?): String {
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.ROOT).format(java.util.Date())
    val ext = when {
        mime == null -> "bin"
        mime.contains("json") -> "json"
        mime.contains("calendar") -> "ics"
        mime.contains("csv") -> "csv"
        mime.startsWith("text/") -> "txt"
        else -> "bin"
    }
    return "docker-dashboard-$stamp.$ext"
}

/** Lit le blob côté page, l'encode en base64 et le passe au pont Kotlin. */
private fun blobToBridgeJs(blobUrl: String, name: String) = """
    (async () => {
      try {
        const r = await fetch('$blobUrl'); const b = await r.blob();
        const fr = new FileReader();
        fr.onload = () => DockerDashboardApp.saveFile(String(fr.result).split(',')[1] || '', '$name', b.type || 'application/octet-stream');
        fr.readAsDataURL(b);
      } catch (e) { DockerDashboardApp.saveFile('', '$name', 'error:' + e); }
    })();
""".trimIndent()

/** Écrit le fichier dans Téléchargements (MediaStore, sans permission de stockage depuis Android 10). */
class BlobSaver(private val ctx: Context) {
    @android.webkit.JavascriptInterface
    fun saveFile(base64: String, name: String, mime: String) {
        val handler = android.os.Handler(ctx.mainLooper)
        try {
            if (base64.isEmpty()) throw IllegalStateException(mime.removePrefix("error:"))
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, name).writeBytes(bytes)
            }
            handler.post { Toast.makeText(ctx, "Enregistré dans Téléchargements : $name", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            handler.post { Toast.makeText(ctx, "Téléchargement impossible : ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
}
