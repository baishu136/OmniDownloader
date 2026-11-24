package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.omni.downloader.data.repository.SessionRepository
import kotlinx.coroutines.*

/** A visible login browser and user-opened OAuth windows sharing the same browser identity. */
@SuppressLint("SetJavaScriptEnabled")
internal class LoginBrowserController(
    context: Context,
    private val platform: String,
    private val startUrl: String,
    desktop: Boolean,
    private val onProgress: (Int) -> Unit,
    private val onPageFinished: () -> Unit,
    private val onNotice: (String) -> Unit,
    private val onLocation: (String) -> Unit = {},
    private val documentStartScript: String? = null,
    private val browserProfile: DouyinBrowserProfile? = null,
    private val googleProfile: com.omni.downloader.data.repository.GoogleBrowserProfile? = null,
    private val strings: com.omni.downloader.ui.localization.AppStrings = com.omni.downloader.ui.localization.ZhHansStrings
) {
    val container = FrameLayout(context)
    private val identities = linkedMapOf<WebView, WebViewDesktopMode>()
    private val captureScripts = linkedMapOf<WebView, androidx.webkit.ScriptHandler>()
    private var disposed = false
    private var unregisterGoogle:()->Unit={}
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var proxyLease: WebViewProxyLease? = null
    private var recoveryAttempts = 0
    var desktop = desktop
        private set
    val root: WebView = newWebView()
    private val active get() = identities.keys.lastOrNull() ?: root
    val current: WebView get() = active

    init {
        if(googleProfile!=null) unregisterGoogle=googleProfile.register{close()}
        container.addView(root)
        val load = { if (!disposed) root.loadUrl(startUrl) }
        if (browserProfile != null) work.launch {
            try {
                proxyLease = WebViewProxyLease.acquire(com.omni.downloader.engine.NetworkProxy.configuredUrl, strings)
                if (!disposed) browserProfile.restore(load)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!disposed) onNotice(com.omni.downloader.engine.LogSanitizer.sanitize(error.message.orEmpty())) }
        }
        else if(googleProfile!=null) load()
        else SessionRepository.getInstance(context).restoreBrowserCookies(platform, load)
    }

    private fun newWebView(): WebView = WebView(container.context).apply {
        browserProfile?.bind(this)
        googleProfile?.bind(this)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        val identity = WebViewDesktopMode(this).also { it.apply(desktop) }
        identities[this] = identity
        documentStartScript?.let { script ->
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                captureScripts[this] = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, script,
                    setOf("https://www.douyin.com", "https://www.iesdouyin.com"))
            }
        }
        val current = this
        (googleProfile?.cookies ?: browserProfile?.cookies ?: CookieManager.getInstance()).apply { setAcceptCookie(true); setAcceptThirdPartyCookies(current, true) }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (BrowserIdentity.permitsNavigation(url)) return false
                onNotice(strings.loginNotice)
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (disposed) return
                if (view == active) onLocation(url.orEmpty())
                identity.onPageStarted(); onProgress(0)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (disposed) return
                identity.onPageFinished(); onProgress(100); onPageFinished()
                if (documentStartScript != null && view != null && identities.containsKey(view) && !captureScripts.containsKey(view)) view.evaluateJavascript(documentStartScript, null)
                if (view == active && !url.isNullOrBlank()) {
                    view.evaluateJavascript(RAW_JSON_PROBE_SCRIPT) { raw ->
                        if (disposed) return@evaluateJavascript
                        val text = parseJsStringResult(raw)
                        val fallback = LoginPageRecovery.jsonPayloadFallbackUrl(platform, url, text, recoveryAttempts)
                        if (fallback != null) {
                            recoveryAttempts++
                            view.loadUrl(fallback)
                        }
                    }
                }
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (disposed || request?.isForMainFrame != true || view == null) return
                val statusCode = errorResponse?.statusCode ?: return
                val failedUrl = request.url?.toString().orEmpty()
                val fallback = LoginPageRecovery.httpErrorFallbackUrl(platform, failedUrl, statusCode, recoveryAttempts)
                if (fallback != null) {
                    recoveryAttempts++
                    view.post { if (!disposed) view.loadUrl(fallback) }
                } else {
                    onNotice("HTTP $statusCode")
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (disposed || request?.isForMainFrame != true) return
                val desc = error?.description?.toString().orEmpty()
                if (desc.isNotBlank()) onNotice(desc)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) { if (!disposed && view == active) onProgress(progress) }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (!isUserGesture || disposed || identities.size >= 3) return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val child = newWebView()
                container.addView(child)
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView?) { if (window != null && window != root) closeWindow(window) }
        }
    }

    private fun closeWindow(view: WebView) {
        if (disposed || !identities.containsKey(view)) return
        identities.remove(view)?.close()
        captureScripts.remove(view)?.remove()
        container.removeView(view); view.stopLoading(); view.destroy()
        onLocation(active.url.orEmpty())
        onProgress(active.progress)
    }
    fun goBack(): Boolean {
        if (active.canGoBack()) { active.goBack(); return true }
        if (active != root) { closeWindow(active); return true }
        return false
    }
    fun setDesktop(enabled: Boolean) {
        if (enabled == desktop || disposed) return
        desktop = enabled
        reloadStart()
    }
    fun reloadStart() {
        recoveryAttempts = 0
        navigate(startUrl)
    }
    fun navigate(url:String) {
        if (disposed) return
        if(!BrowserIdentity.permitsNavigation(url))return
        recoveryAttempts = 0
        identities.keys.filter { it != root }.toList().forEach(::closeWindow)
        root.stopLoading()
        identities[root]?.apply(desktop)
        root.loadUrl(url)
    }
    fun close() {
        if (disposed) return
        disposed = true
        unregisterGoogle();unregisterGoogle={}
        work.cancel()
        proxyLease?.let { lease -> CoroutineScope(Dispatchers.Main.immediate).launch { lease.release() } }
        proxyLease = null
        identities.toList().forEach { (view, identity) ->
            captureScripts.remove(view)?.remove()
            identity.close(); container.removeView(view); view.stopLoading(); view.destroy()
        }
        identities.clear()
        captureScripts.clear()
    }

    private companion object {
        const val RAW_JSON_PROBE_SCRIPT = "(function(){try{if(!document.body)return '';var t=(document.body.innerText||'').trim();if(t.length>0&&t.length<=1024&&t[0]==='{'&&!document.querySelector('form,input,video,main'))return t;}catch(e){}return '';})()"

        fun parseJsStringResult(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "null") return ""
            return runCatching { org.json.JSONTokener(raw).nextValue() as? String ?: raw }.getOrDefault(raw)
        }
    }
}
