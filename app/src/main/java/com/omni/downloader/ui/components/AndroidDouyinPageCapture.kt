package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.webkit.*
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Foreground-only, noninteractive page loading behind the existing app UI. */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidDouyinPageCapture(
    private val activity: ComponentActivity,
    private val budgetMillis: Long = 8_000,
    private val interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null
) : DouyinPageLoader {
    companion object { private val gate = Mutex() }
    private var closed = false
    private var dispose: (() -> Unit)? = null
    @Volatile internal var activeViews = 0
        private set

    fun close() { closed = true; dispose?.invoke() }

    override suspend fun capture(url: String, proxy: String, scope: String): DouyinPageResult =
        captureProgressive(url, proxy, scope) {}

    override suspend fun captureProgressive(url: String, proxy: String, scope: String,
        onCandidate: suspend (String) -> Unit): DouyinPageResult = withContext(Dispatchers.Main.immediate) {
        var best: String? = null
        var sawLoginPrompt = false
        withTimeoutOrNull(budgetMillis) { gate.withLock {
            val id = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_DOUYIN)
                ?: return@withLock DouyinPageResult(DouyinPageStatus.EMPTY)
            val session = SessionRepository.getInstance(activity)
            if (closed || !session.isCookieEnabled(LinkNormalizer.PLATFORM_DOUYIN))
                return@withLock DouyinPageResult(DouyinPageStatus.UNAVAILABLE)
            val profile = DouyinBrowserProfile.current(activity)
                ?: return@withLock DouyinPageResult(DouyinPageStatus.UNAVAILABLE)
            repeat(20) {
                if (!closed && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(50)
            }
            if (closed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                return@withLock DouyinPageResult(DouyinPageStatus.UNAVAILABLE)
            if (proxy.isNotBlank() && !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
                return@withLock DouyinPageResult(DouyinPageStatus.UNAVAILABLE)

            val parent = activity.findViewById<ViewGroup>(android.R.id.content)
            val panel = FrameLayout(activity).apply {
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            val web = WebView(activity)
            var disposed = false
            var script: ScriptHandler? = null
            var identity: WebViewDesktopMode? = null
            var proxyLease: WebViewProxyLease? = null
            var finished = false
            var networkFailed = false
            fun cleanup() {
                if (disposed) return
                disposed = true
                script?.remove(); identity?.close()
                web.stopLoading(); panel.removeAllViews(); parent.removeView(panel); web.destroy()
                activeViews = 0
            }
            dispose = ::cleanup
            try {
                proxyLease = WebViewProxyLease.acquire(proxy)
                profile.bind(web)
                web.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true
                    allowFileAccess = false; allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                profile.cookies.setAcceptCookie(true)
                profile.cookies.setAcceptThirdPartyCookies(web, false)
                identity = WebViewDesktopMode(web).also { it.apply(true) }
                val capture = "window.__omniCaptureId='$id';\n" +
                    activity.assets.open("browser/douyin-video-capture.js").bufferedReader().use { it.readText() } + MUTE_SCRIPT
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                    script = WebViewCompat.addDocumentStartJavaScript(web, capture,
                        setOf("https://www.douyin.com", "https://www.iesdouyin.com"))
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val next = request?.url?.toString().orEmpty()
                        if (LinkNormalizer.platformOf(next) != LinkNormalizer.PLATFORM_DOUYIN) return true
                        val nextId = LinkNormalizer.contentId(next, LinkNormalizer.PLATFORM_DOUYIN)
                        return nextId != null && nextId != id
                    }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                        request?.let { interceptor?.invoke(it) }
                    override fun onPageStarted(view: WebView?, next: String?, favicon: Bitmap?) {
                        if (disposed) return
                        finished = false; identity?.onPageStarted()
                        if (script == null) web.evaluateJavascript(capture, null)
                    }
                    override fun onPageFinished(view: WebView?, next: String?) {
                        if (disposed) return
                        identity?.onPageFinished(); finished = true
                        if (script == null) web.evaluateJavascript(capture, null)
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) networkFailed = true
                    }
                }
                web.webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                }
                panel.addView(web, FrameLayout.LayoutParams(-1, -1))
                parent.addView(panel, 0, ViewGroup.LayoutParams(-1, -1))
                activeViews = 1
                profile.restore {
                    if (!disposed && !closed && scope == DouyinAutoResolver.scope(activity, proxy))
                        web.loadUrl("https://www.douyin.com/video/$id")
                }
                val started = android.os.SystemClock.elapsedRealtime()
                var fingerprint = ""
                var changed = started
                while (android.os.SystemClock.elapsedRealtime() - started < budgetMillis) {
                    currentCoroutineContext().ensureActive()
                    if (closed || disposed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        return@withLock DouyinPageResult(DouyinPageStatus.UNAVAILABLE)
                    if (scope != DouyinAutoResolver.scope(activity, proxy)) return@withLock DouyinPageResult(DouyinPageStatus.SCOPE_CHANGED)
                    // Inspect target-bound media before considering an optional login overlay.
                    val state = evaluate(web, DOUYIN_PAGE_STATE)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (state?.optString("status") == "challenge")
                        return@withLock DouyinPageResult(DouyinPageStatus.CHALLENGE_REQUIRED, best)
                    if (LinkNormalizer.contentId(web.url.orEmpty(), LinkNormalizer.PLATFORM_DOUYIN) == id) {
                        evaluate(web, DouyinVideoBrowserScript.extract(id, requestDetail = false))
                        val output = evaluate(web, "window.__omniVideoResult || null")?.let { runCatching { JSONObject(it) }.getOrNull() }
                        if (output?.optBoolean("ok") == true) {
                            val item = output.getJSONObject("item").toString()
                            val metadata = withContext(Dispatchers.Default) { DouyinVideoParser.parseItem(item, url) }
                            if (closed || disposed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                return@withLock DouyinPageResult(DouyinPageStatus.UNAVAILABLE)
                            if (scope != DouyinAutoResolver.scope(activity, proxy)) return@withLock DouyinPageResult(DouyinPageStatus.SCOPE_CHANGED)
                            val signature = metadata.availableVideoFormats.map { listOf(it.sourceUrl, it.width, it.height, it.fps, it.vcodec, it.dynamicRange) }.toString()
                            if (signature != fingerprint) {
                                fingerprint = signature; changed = android.os.SystemClock.elapsedRealtime()
                                best = item
                                onCandidate(item)
                            }
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (!DouyinQualityPolicy.needsPage(metadata) && now - changed >= 600)
                                return@withLock DouyinPageResult(DouyinPageStatus.FOUND, best)
                        }
                    }
                    when (state?.optString("status")) {
                        "auth" -> if (best == null) return@withLock DouyinPageResult(DouyinPageStatus.AUTH_REQUIRED)
                        "login_prompt" -> sawLoginPrompt = true
                    }
                    if (networkFailed && finished) break
                    delay(200)
                }
                DouyinPageResult(if (best != null) DouyinPageStatus.TIMEOUT else if (sawLoginPrompt) DouyinPageStatus.AUTH_REQUIRED else DouyinPageStatus.TIMEOUT, best)
            } finally {
                cleanup(); dispose = null
                proxyLease?.release()
            }
        } } ?: DouyinPageResult(if (best == null && sawLoginPrompt) DouyinPageStatus.AUTH_REQUIRED else DouyinPageStatus.TIMEOUT, best)
    }

    private suspend fun evaluate(web: WebView, code: String): String? = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(code) { raw ->
            if (continuation.isActive) continuation.resume(runCatching {
                if (raw.length > 8_388_608 || raw == "null") null else JSONTokener(raw).nextValue() as? String
            }.getOrNull())
        }
    }
}

private val MUTE_SCRIPT = """
;(()=>{const quiet=v=>{v.muted=true;v.autoplay=false;v.preload='metadata';if(!v.paused)v.pause();};
document.addEventListener('play',e=>{if(e.target instanceof HTMLVideoElement)quiet(e.target);},true);
new MutationObserver(()=>document.querySelectorAll('video').forEach(quiet)).observe(document,{childList:true,subtree:true});})();
""".trimIndent()

internal val DOUYIN_PAGE_STATE = """
(()=>{const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0;};
const nodes=Array.from(document.querySelectorAll('[class*="captcha"],[id*="captcha"],[role="dialog"],[class*="login-panel"]')).slice(0,32).filter(visible);
const text=nodes.map(n=>n.innerText || '').join(' ');
const challenge=/请完成.{0,16}验证|拖动.{0,16}滑块|按住左边按钮|验证后继续/.test(text)||Array.from(document.querySelectorAll('iframe[src*="captcha"],iframe[src*="verifycenter"]')).some(visible);
const auth=/登录后.{0,12}观看|请先登录/.test(text)||/\/(?:login|passport)\b/.test(location.pathname);
const prompt=/扫码登录|登录后.{0,12}高清/.test(text);
return JSON.stringify({status:challenge?'challenge':auth?'auth':prompt?'login_prompt':'normal'});})()
""".trimIndent()
