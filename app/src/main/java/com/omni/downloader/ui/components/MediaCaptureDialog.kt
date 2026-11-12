package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.material3.TextButton
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.downloader.engine.RelayCapturePolicy
import com.omni.downloader.ui.localization.LocalAppStrings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 浏览器辅助模式：当分享页是 SPA、无法在后台直接取流时，
 * 用应用内 WebView 打开页面（共享已导入的会话 Cookie），
 * 捕获实际播放的 MP4/HLS 请求后加入下载队列。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MediaCaptureDialog(
    startUrl: String,
    onDismissRequest: () -> Unit,
    onCaptured: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions = remember(context) { com.omni.downloader.data.repository.SessionRepository.getInstance(context) }
    val profiles by sessions.profiles.collectAsState()
    val platform = remember(startUrl) { com.omni.downloader.engine.LinkNormalizer.platformOf(startUrl) }
    if (profiles[platform]?.cookieEnabled == false) {
        Dialog(onDismissRequest = onDismissRequest) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("$platform 的 Cookie 已禁用", style = MaterialTheme.typography.titleMedium)
                    Text("浏览器辅助会使用网页登录状态。可开启该平台 Cookie 后继续，或关闭窗口保持匿名解析。", Modifier.padding(vertical = 12.dp))
                    Button(shape = MaterialTheme.shapes.medium, onClick = { sessions.setCookieEnabled(platform, true) }) { Text("开启 Cookie 并继续") }
                    TextButton(onClick = onDismissRequest) { Text("关闭") }
                }
            }
        }
        return
    }
    val strings = LocalAppStrings.current
    var desktop by remember { mutableStateOf(BrowserIdentity.prefersDesktopUrl(startUrl)) }
    var identity by remember { mutableStateOf<WebViewDesktopMode?>(null) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    val capturedOnce = remember { AtomicBoolean(false) }
    var candidate by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.browserCaptureTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.browserCaptureHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, contentDescription = strings.cancel)
                    }
                }

                AnimatedVisibility(visible = isLoading) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (BrowserIdentity.prefersDesktopUrl(startUrl)) {
                    TextButton(onClick = {
                        desktop = !desktop
                        browser?.stopLoading()
                        identity?.apply(desktop)
                        browser?.loadUrl(startUrl)
                    }) { Text(if (desktop) "桌面网站（切换手机版）" else "手机版（切换桌面网站）") }
                }
                candidate?.let { source ->
                    Button(shape = MaterialTheme.shapes.medium, onClick = { onCaptured(source) }, modifier = Modifier.fillMaxWidth()) { Text("下载当前已捕获媒体（请确认来自目标内容）") }
                }
                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                mediaPlaybackRequiresUserGesture = true
                            }
                            browser = this
                            val mode = WebViewDesktopMode(this).also { it.apply(desktop) }
                            identity = mode
                            val webView = this
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(webView, true)
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    mode.onPageStarted()
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    mode.onPageFinished()
                                    isLoading = false
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url?.toString().orEmpty()
                                    val mime = request?.requestHeaders?.get("Accept").orEmpty()
                                    if (url.isNotBlank() &&
                                        RelayCapturePolicy.accepts(url, mime) &&
                                        capturedOnce.compareAndSet(false, true)
                                    ) {
                                        mainHandler.post { candidate = url }
                                    }
                                    return null
                                }
                            }
                            setDownloadListener { url, _, _, mime, _ ->
                                if (url.isNotBlank() && capturedOnce.compareAndSet(false, true)) {
                                    val safeMime = mime.orEmpty()
                                    if (RelayCapturePolicy.accepts(url, safeMime)) {
                                        mainHandler.post { candidate = url }
                                    }
                                }
                            }
                            com.omni.downloader.data.repository.SessionRepository.getInstance(context).restoreBrowserCookies(
                                com.omni.downloader.engine.LinkNormalizer.platformOf(startUrl)) { loadUrl(startUrl) }
                        }
                    },
                    onRelease = { identity?.close(); identity = null; browser = null; it.stopLoading(); it.destroy() },
                    update = { }
                )
            }
        }
    }
}
