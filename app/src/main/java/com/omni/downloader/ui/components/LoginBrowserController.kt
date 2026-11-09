package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
    private val browserProfile: DouyinBrowserProfile? = null
) {
    val container = FrameLayout(context)
    private val identities = linkedMapOf<WebView, WebViewDesktopMode>()
    private val captureScripts = linkedMapOf<WebView, androidx.webkit.ScriptHandler>()
    private var disposed = false
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var proxyLease: WebViewProxyLease? = null
    var desktop = desktop
        private set
    val root: WebView = newWebView()
    private val active get() = identities.keys.lastOrNull() ?: root
    val current: WebView get() = active

    init {
        container.addView(root)
        val load = { if (!disposed) root.loadUrl(startUrl) }
        if (browserProfile != null) work.launch {
            try {
                proxyLease = WebViewProxyLease.acquire(com.omni.downloader.engine.NetworkProxy.configuredUrl)
                if (!disposed) browserProfile.restore(load)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!disposed) onNotice(com.omni.downloader.engine.LogSanitizer.sanitize(error.message.orEmpty())) }
        }
        else SessionRepository.getInstance(context).restoreBrowserCookies(platform, load)
    }

    private fun newWebView(): WebView = WebView(container.context).apply {
        browserProfile?.bind(this)
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
        (browserProfile?.cookies ?: CookieManager.getInstance()).apply { setAcceptCookie(true); setAcceptThirdPartyCookies(current, true) }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (BrowserIdentity.permitsNavigation(url)) return false
                onNotice("请在网页内选择扫码或手机号登录，应用唤起链接不会离开当前登录页")
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
        if (disposed) return
        identities.keys.filter { it != root }.toList().forEach(::closeWindow)
        root.stopLoading()
        identities[root]?.apply(desktop)
        root.loadUrl(startUrl)
    }
    fun close() {
        if (disposed) return
        disposed = true
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
}
