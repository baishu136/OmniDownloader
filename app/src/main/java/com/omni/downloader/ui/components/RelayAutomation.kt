package com.omni.downloader.ui.components

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebSettings
import com.omni.downloader.engine.RelayCapturePolicy
import com.omni.downloader.engine.UrlSniffer
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One capture session, with all callbacks serialized onto the UI thread. */
internal class RelayCaptureSession(
    private val fallbackTitle: String,
    private val onSuccess: (String, String) -> Unit,
    private val onProgress: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    private val completed = AtomicBoolean(false)
    private val submitted = AtomicBoolean(false)
    val isCompleted get() = completed.get()

    @JavascriptInterface fun onSubmitted() { if (active.get()) submitted.set(true) }
    @JavascriptInterface fun onResolved(url: String, title: String): Boolean = capture(url, title)
    @JavascriptInterface fun onMedia(url: String, title: String, mime: String): Boolean = capture(url, title, mime)
    @JavascriptInterface fun onStatus(status: String) { main.post { if (active.get() && !completed.get()) onProgress(status.take(180)) } }
    @JavascriptInterface fun onFailed(reason: String) { fail(reason) }

    fun capture(rawUrl: String, rawTitle: String = "", mime: String = "", explicit: Boolean = false): Boolean {
        if (!active.get() || rawUrl.length !in 1..65536 || (!submitted.get() && !explicit)) return false
        val (url, title) = UrlSniffer.unpackDirectMediaUrl(rawUrl, rawTitle.take(250).ifBlank { fallbackTitle })
        if (!RelayCapturePolicy.accepts(url, mime) || !completed.compareAndSet(false, true)) return false
        main.post { if (active.get()) onSuccess(url, title.take(250)) }
        return true
    }
    fun fail(reason: String) {
        if (!active.get() || !completed.compareAndSet(false, true)) return
        main.post { if (active.get()) onError(reason.take(300)) }
    }
    fun close() { active.set(false); main.removeCallbacksAndMessages(null) }
}

internal object RelayAutomation {
    private var source: String? = null
    fun install(view: WebView, videoUrl: String, autoSubmit: Boolean) {
        val script = source ?: view.context.assets.open("relay_capture.js").bufferedReader().use { it.readText() }.also { source = it }
        view.evaluateJavascript(script.replace("__OMNI_TARGET_URL__", JSONObject.quote(videoUrl))
            .replace("__OMNI_AUTOSUBMIT__", autoSubmit.toString()), null)
    }
    fun fill(view: WebView) { view.evaluateJavascript("window.__omniFillInput && window.__omniFillInput()", null) }
    fun initialUrl(siteUrl: String, videoUrl: String): String {
        val uri = android.net.Uri.parse(siteUrl)
        val host = uri.host.orEmpty().lowercase()
        return if (videoUrl.isNotBlank() && host in setOf("greenvideo.cc", "www.greenvideo.cc", "snapany.com", "www.snapany.com"))
            uri.buildUpon().clearQuery().apply {
                uri.queryParameterNames.filter { it != "url" }.forEach { name -> uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) } }
                appendQueryParameter("url", videoUrl)
            }.build().toString() else siteUrl
    }

    /** Returns cleanup for the temporary popup WebViews owned by this session. */
    fun bind(
        view: WebView, session: RelayCaptureSession, videoUrl: String, autoSubmit: Boolean,
        onLocation: (String) -> Unit = {}, onTitle: (String) -> Unit = {}, onProgress: (Int) -> Unit = {}
    ): () -> Unit {
        val popups = mutableListOf<WebView>()
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false; allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }
        view.addJavascriptInterface(session, "OmniBridge")
        view.setDownloadListener { url, _, _, mime, _ -> session.capture(url, mime = mime.orEmpty(), explicit = true) }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) { onProgress(progress) }
            override fun onReceivedTitle(view: WebView?, title: String?) { if (!title.isNullOrBlank()) onTitle(title) }
            override fun onCreateWindow(owner: WebView?, isDialog: Boolean, isUserGesture: Boolean, message: android.os.Message?): Boolean {
                val transport = message?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(view.context); popups.add(popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(child: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return true
                        if (url.scheme !in setOf("http", "https")) return true
                        if (!session.capture(url.toString(), explicit = isUserGesture) && url.host == android.net.Uri.parse(view.url.orEmpty()).host) view.loadUrl(url.toString())
                        popup.post { popups.remove(popup); popup.stopLoading(); popup.destroy() }
                        return true
                    }
                }
                transport.webView = popup; message.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView?) { if (window != null && popups.remove(window)) window.destroy() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(web: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                url?.let(onLocation); session.onStatus("正在连接中转站…")
            }
            override fun onPageCommitVisible(web: WebView?, url: String?) { if (web != null) install(web, videoUrl, autoSubmit) }
            override fun onPageFinished(web: WebView?, url: String?) {
                url?.let(onLocation); if (web != null) install(web, videoUrl, autoSubmit)
            }
            override fun shouldInterceptRequest(web: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request?.method.equals("GET", ignoreCase = true)) session.capture(request!!.url.toString())
                return null // Observe; never issue a GET for a page's POST API.
            }
            override fun shouldOverrideUrlLoading(web: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                if (url.scheme !in setOf("http", "https")) return true
                if (!request.method.equals("GET", ignoreCase = true)) return false
                return session.capture(url.toString(), explicit = request.hasGesture())
            }
            override fun onReceivedError(web: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (autoSubmit && request?.isForMainFrame == true) session.fail("中转站页面加载失败，可使用窗口排查")
            }
        }
        return { popups.toList().forEach { it.stopLoading(); it.destroy() }; popups.clear() }
    }
}
