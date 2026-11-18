package com.rishadblack.plugins.sylloo_app_native

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nativephp.mobile.ui.MainActivity
import com.nativephp.mobile.ui.nativerender.NativeUIBridge
import com.nativephp.mobile.ui.nativerender.NativeUINode
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The cloud window (plan §7.6, `<native:sylloo-window>`): the business's own pages in a web view of this package,
 * drawn inside NativePHP's native screen between the native top bar and bottom navigation. It is **not** NativePHP's
 * web view: NativePHP's `webview` element offers no navigation filter, file chooser, downloads, print or back button,
 * and its PHP mode would give the page the app's bridge. This one has none of the app's bridge (no `window.Native`,
 * no JavaScript interface, no file access); the page reaches the phone only through three messages its own origin may
 * post (print this page, print a page of the business, save a page-made file), each checked here again.
 *
 * Props (set by the PHP element `Rishadblack\SyllooAppNative\Elements\CloudWindow` from the shared base's
 * WindowRules): `src` (the one-use ticket address), `hosts`, `paths`, `allow_http`, `keep_alive_path`,
 * `keep_alive_seconds`. Callbacks (each with one text): `on_leave` (an address for the normal browser), `on_ended`
 * (the session ended: ask for a new ticket; the text `signed_out` when the member used the page's own Sign out), `on_navigated` (the address shown), `on_failed` (the page did not load:
 * offline).
 */
object WindowRenderer {

    @Composable
    fun Render(node: NativeUINode, modifier: Modifier) {
        val src = node.props.getString("src", "")
        val config = WindowConfig(
            hosts = node.props.getStringList("hosts"),
            paths = node.props.getStringList("paths"),
            allowHttp = node.props.getBool("allow_http", false),
            keepAlivePath = node.props.getString("keep_alive_path", ""),
            keepAliveSeconds = node.props.getInt("keep_alive_seconds", 240).coerceAtLeast(30),
            nodeId = node.id,
            onLeave = node.props.getCallbackId("on_leave"),
            onEnded = node.props.getCallbackId("on_ended"),
            onNavigated = node.props.getCallbackId("on_navigated"),
            onFailed = node.props.getCallbackId("on_failed"),
        )

        val holder = remember { WindowHolder() }
        holder.config = config
        var canGoBack by remember { mutableStateOf(false) }
        holder.onHistory = { canGoBack = it }

        // The system back button goes back in the page first; at the first page NativePHP's own back handling
        // (the PHP screen's onBackPressed) decides.
        BackHandler(enabled = canGoBack) {
            holder.view?.goBack()
        }

        // Keep-alive: every keep_alive_seconds while shown, and when the app comes back to the front (at most once a
        // minute), so the business session never runs out while the app is open (app-plan §5.2).
        LaunchedEffect(config.keepAlivePath, config.keepAliveSeconds) {
            while (true) {
                delay(config.keepAliveSeconds * 1000L)
                holder.keepAlive()
            }
        }

        DisposableEffect(Unit) {
            val lifecycle = MainActivity.instance?.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) holder.keepAlive(minGapMs = 60_000L)
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }

        AndroidView(
            modifier = modifier.clipToBounds(),
            factory = { context ->
                val webView = WebView(context)
                webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                holder.attach(webView)
                if (WindowFilter.decide(src, config.hosts, listOf("/"), config.allowHttp, false) == WindowDecision.SHOW) {
                    webView.tag = src
                    webView.loadUrl(src)
                }
                webView
            },
            update = { webView ->
                holder.config = config
                if (webView.tag != src && WindowFilter.decide(src, config.hosts, listOf("/"), config.allowHttp, false) == WindowDecision.SHOW) {
                    webView.tag = src
                    webView.loadUrl(src)
                }
            },
            onRelease = { webView ->
                holder.detach()
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                webView.webChromeClient = null
                webView.destroy()
            }
        )
    }
}

data class WindowConfig(
    val hosts: List<String>,
    val paths: List<String>,
    val allowHttp: Boolean,
    val keepAlivePath: String,
    val keepAliveSeconds: Int,
    val nodeId: Int,
    val onLeave: Int,
    val onEnded: Int,
    val onNavigated: Int,
    val onFailed: Int,
)

/**
 * The live web view of one cloud window and what it does.
 */
class WindowHolder {
    companion object {
        /** The `ended` text when the member used the page's own Sign out. */
        const val SIGNED_OUT = "signed_out"
    }


    var view: WebView? = null
    var config: WindowConfig? = null
    var onHistory: (Boolean) -> Unit = {}
    private var lastKeepAlive = 0L
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        view = webView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(true)
        settings.setGeolocationEnabled(false)
        settings.mediaPlaybackRequiresUserGesture = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val cfg = config ?: return@setDownloadListener
            when (WindowFilter.decide(url, cfg.hosts, listOf("/"), cfg.allowHttp, false)) {
                WindowDecision.SHOW -> WindowDownloads.download(webView.context, url, userAgent, contentDisposition, mimeType, null)
                WindowDecision.LEAVE -> send(cfg.onLeave, url)
                else -> {}
            }
        }

        installPageScripts(webView)
    }

    fun detach() {
        view = null
    }

    /**
     * The page scripts and the one message channel, both only for the business's own origins (androidx.webkit). Older
     * web views without these features get neither: no in-app print or page-made downloads, nothing else changes.
     */
    private fun installPageScripts(webView: WebView) {
        val cfg = config ?: return
        val origins = WindowFilter.origins(cfg.hosts, cfg.allowHttp)
        if (origins.isEmpty()) return

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "SyllooWindow", origins) { view, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame) handleMessage(view, message.data, sourceOrigin)
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, WindowScripts.PAGE, origins)
        }
    }

    private fun handleMessage(webView: WebView, data: String?, origin: Uri) {
        val cfg = config ?: return
        val message = try { JSONObject(data ?: return) } catch (e: Exception) { return }
        val current = webView.url ?: return
        if (!WindowFilter.mayShow(current, cfg.hosts, cfg.paths, cfg.allowHttp)) return

        when (message.optString("type")) {
            "print" -> WindowPrint.printWindow(webView, message.optString("title"))
            "print_page" -> {
                val url = message.optString("url")
                val sameHost = Uri.parse(url).host.equals(origin.host, ignoreCase = true)
                if (sameHost && WindowFilter.mayShow(url, cfg.hosts, cfg.paths, cfg.allowHttp)) {
                    WindowPrint.printPage(webView.context, url, message.optString("title"))
                }
            }
            "save" -> WindowDownloads.saveData(webView.context, message.optString("data"), message.optString("name"))
        }
    }

    fun keepAlive(minGapMs: Long = 0L) {
        val cfg = config ?: return
        val webView = view ?: return
        if (cfg.keepAlivePath.isEmpty() || !cfg.keepAlivePath.startsWith("/")) return
        val now = SystemClock.elapsedRealtime()
        if (minGapMs > 0 && now - lastKeepAlive < minGapMs) return

        main.post {
            val current = webView.url ?: return@post
            if (!WindowFilter.mayShow(current, cfg.hosts, cfg.paths, cfg.allowHttp)) return@post
            lastKeepAlive = now
            val path = JSONObject.quote(cfg.keepAlivePath)
            webView.evaluateJavascript(
                "(function(){return fetch($path,{credentials:'same-origin',headers:{'X-Requested-With':'XMLHttpRequest'}}).then(function(r){window.__syllooKeepAlive=r.status;}).catch(function(){});})();null",
                null
            )
            main.postDelayed({
                webView.evaluateJavascript("String(window.__syllooKeepAlive||'')") { status ->
                    val code = status?.trim('"')
                    if (code == "401" || code == "419") send(cfg.onEnded, "keep_alive")
                }
            }, 5_000L)
        }
    }

    /** A left address: the phone's own app for `tel:`, `mailto:`, `sms:`, `geo:`, WhatsApp; else the PHP screen's browser. */
    private fun leave(webView: WebView, url: String) {
        if (WindowExternal.open(webView.context, url)) return
        config?.let { send(it.onLeave, url) }
    }

    private fun send(callbackId: Int, text: String) {
        val cfg = config ?: return
        if (callbackId != 0) NativeUIBridge.sendTextChangeEvent(callbackId, cfg.nodeId, text)
    }

    private var lastEnded = ""
    private var lastEndedAt = 0L

    /** When the page's own Sign out was sent (elapsedRealtime; 0 = not yet). Written on WebView's network thread. */
    @Volatile
    private var signOutSentAt = 0L

    /** The time since the page's own Sign out was sent, or null when it was not. */
    private fun sinceSignOut(): Long? =
        if (signOutSentAt == 0L) null else SystemClock.elapsedRealtime() - signOutSentAt

    /** What the window does with an address it does not show; the same ended / sign-out is told once (within 2 s). */
    private fun act(view: WebView, cfg: WindowConfig, url: String, isRedirect: Boolean) {
        val decision = WindowFilter.decide(url, cfg.hosts, cfg.paths, cfg.allowHttp, isRedirect)
        when (WindowFilter.afterSignOut(decision, sinceSignOut())) {
            WindowDecision.LEAVE -> leave(view, url)
            WindowDecision.ENDED -> endOnce(cfg, url)
            WindowDecision.SIGN_OUT -> endOnce(cfg, SIGNED_OUT)
            else -> {}
        }
    }

    private fun endOnce(cfg: WindowConfig, why: String) {
        val now = SystemClock.elapsedRealtime()
        val key = if (why == SIGNED_OUT) SIGNED_OUT else "ended"
        if (key == lastEnded && now - lastEndedAt < 2_000L) return
        if (lastEnded == SIGNED_OUT && now - lastEndedAt < 2_000L) return
        lastEnded = key
        lastEndedAt = now
        send(cfg.onEnded, why)
    }

    private inner class Client : WebViewClient() {
        /**
         * The page's own Sign out is a form sent with POST: shouldOverrideUrlLoading never sees it, and onPageStarted
         * only after the business answered it (its redirect then looks like an ended session). Here it is seen before it
         * is sent: it goes on (the business signs its web session out too) and its answer counts as the sign-out
         * (WindowFilter.afterSignOut). Runs on WebView's network thread.
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val cfg = config
            if (cfg != null && request.isForMainFrame &&
                WindowFilter.decide(request.url.toString(), cfg.hosts, cfg.paths, cfg.allowHttp, false) == WindowDecision.SIGN_OUT
            ) {
                signOutSentAt = SystemClock.elapsedRealtime()
            }

            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val cfg = config ?: return true

            val url = request.url.toString()
            val decision = WindowFilter.decide(url, cfg.hosts, cfg.paths, cfg.allowHttp, request.isRedirect)

            return when (WindowFilter.afterSignOut(decision, sinceSignOut())) {
                WindowDecision.SHOW -> false
                else -> { act(view, cfg, url, request.isRedirect); true }
            }
        }

        /**
         * The second check, for what shouldOverrideUrlLoading never sees: a form sent with POST (the page's own Sign
         * out) and the page its answer leads to. A page the window may not show is stopped before it is drawn. The
         * ticket address the window was opened with is always let through.
         */
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            onHistory(view.canGoBack())
            val cfg = config ?: return
            if (url == null || url == "about:blank" || url == view.tag) return
            if (WindowFilter.decide(url, cfg.hosts, cfg.paths, cfg.allowHttp, true) == WindowDecision.SHOW) return

            view.stopLoading()
            act(view, cfg, url, true)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            onHistory(view.canGoBack())
            val cfg = config ?: return
            if (url != null && WindowFilter.mayShow(url, cfg.hosts, cfg.paths, cfg.allowHttp)) send(cfg.onNavigated, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            onHistory(view.canGoBack())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) config?.let { send(it.onFailed, error.errorCode.toString()) }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
            return WindowFiles.choose(fileChooserParams, filePathCallback)
        }

        /** `target=_blank` and `window.open()` (by a tap): the address goes through the same filter. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val catcher = WebView(view.context)
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    open(request.url.toString())
                    v.destroy()
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") {
                        open(url)
                        v.stopLoading()
                        v.destroy()
                    }
                }

                private fun open(url: String) {
                    val cfg = config ?: return
                    when (WindowFilter.decide(url, cfg.hosts, cfg.paths, cfg.allowHttp, false)) {
                        WindowDecision.SHOW -> view.loadUrl(url)
                        WindowDecision.LEAVE -> leave(view, url)
                        else -> {}
                    }
                }
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }
}

/**
 * The page script of the cloud window (only on the business's origins, the top frame): `window.print()` and the
 * office's `print` event go to the Android print screen; a page-made file (`<a download href="blob:…">`) is handed
 * over as data to be saved in Downloads. The same behaviour the Tauri app had (app-plan §7, §8.1).
 */
object WindowScripts {
    const val PAGE = """(function () {
  if (window.top !== window || !window.SyllooWindow) return;
  var post = function (message) { try { window.SyllooWindow.postMessage(JSON.stringify(message)); } catch (e) {} };
  window.addEventListener('print', function (e) {
    var d = e.detail || {};
    var url = d.url || (d.data && d.data.url) || (d[0] && d[0].url);
    if (!url) return;
    e.stopImmediatePropagation();
    post({ type: 'print_page', url: new URL(url, location.href).toString(), title: String(document.title || '') });
  }, true);
  window.print = function () { post({ type: 'print', title: String(document.title || '') }); };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (!a || !a.href || (a.href.indexOf('blob:') !== 0 && a.href.indexOf('data:') !== 0)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    var name = a.getAttribute('download') || '';
    fetch(a.href).then(function (r) { return r.blob(); }).then(function (blob) {
      var reader = new FileReader();
      reader.onload = function () { post({ type: 'save', data: String(reader.result), name: name }); };
      reader.readAsDataURL(blob);
    }).catch(function () {});
  }, true);
})();"""
}
