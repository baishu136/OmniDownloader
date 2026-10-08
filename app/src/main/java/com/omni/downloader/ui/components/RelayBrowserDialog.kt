package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.engine.UrlSniffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RelayBrowserDialog"

/**
 * 现代原生级中转站媒体嗅探浏览器：
 * 1. 全屏原生浏览器交互，完美支持 Cloudflare Turnstile、滑动验证码与自定义清晰度点选
 * 2. 自动填入欲解析的视频链接并支持一键重新装填
 * 3. 底层多重网络管道媒体嗅探 (shouldInterceptRequest, onLoadResource, onCreateWindow, setDownloadListener, DOM 视频源探测)
 * 4. 底部高亮悬浮“🎉 成功嗅探到视频资源”提示卡片，支持一键加入高速下载队列
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayBrowserDialog(
    site: RelaySite,
    initialVideoUrl: String = "",
    onDismiss: () -> Unit,
    onCapturedDownload: (directUrl: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(site.name) }
    var currentUrl by remember { mutableStateOf(site.url) }
    var canGoBack by remember { mutableStateOf(false) }

    // 嗅探到的媒体直链状态
    var sniffedMediaUrl by remember { mutableStateOf("") }
    var sniffedMediaTitle by remember { mutableStateOf("") }

    // 统一处理捕获媒体并分发
    fun handleCapturedMedia(url: String, title: String, autoClose: Boolean = false) {
        val (cleanUrl, cleanTitle) = UrlSniffer.unpackDirectMediaUrl(url, title.ifBlank { "${site.name} 视频" })
        if (cleanUrl.equals(site.url, ignoreCase = true) ||
            cleanUrl.trimEnd('/') == site.url.trimEnd('/') ||
            !UrlSniffer.isDirectMediaUrl(cleanUrl)
        ) {
            return
        }

        sniffedMediaUrl = cleanUrl
        sniffedMediaTitle = cleanTitle

        if (autoClose) {
            Toast.makeText(context, "成功捕获下载地址，已加入下载队列！", Toast.LENGTH_SHORT).show()
            onCapturedDownload(cleanUrl, cleanTitle)
            onDismiss()
        }
    }

    // 辅助脚本：自动查找输入框并填入视频链接
    fun injectAutoFillScript(wv: WebView?) {
        if (initialVideoUrl.isBlank() || wv == null) return
        val encodedUrl = Uri.encode(initialVideoUrl)
        val jsSnippet = """
            (function() {
                try {
                    window.__cfRLUnblockHandlers = true;
                    var rawTarget = '$encodedUrl';
                    if (!rawTarget) return;
                    var targetUrl = decodeURIComponent(rawTarget);
                    var selectors = [
                        'input.n-input__input-el',
                        '#s_input',
                        'input[name="link"]',
                        'input[name="q"]',
                        'input[name="url"]',
                        'input.search__input',
                        'input[placeholder*="http"]',
                        'input[placeholder*="链接"]',
                        'input[placeholder*="粘贴"]',
                        'input[placeholder*="Link"]',
                        'input[type="text"]',
                        'input[type="url"]'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) {
                            el.focus();
                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                            if (setter && setter.set) {
                                setter.set.call(el, targetUrl);
                            } else {
                                el.value = targetUrl;
                            }
                            try {
                                el.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, data: targetUrl, inputType: 'insertText' }));
                                el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, data: targetUrl, inputType: 'insertText' }));
                            } catch(e) {}
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: targetUrl }));
                            break;
                        }
                    }

                    // 探测 DOM 中存在的视频元素
                    function probeVideoElements() {
                        var videos = document.querySelectorAll('video, source');
                        for (var vIdx = 0; vIdx < videos.length; vIdx++) {
                            var v = videos[vIdx];
                            var src = v.src || v.getAttribute('src') || '';
                            if (src && src.indexOf('http') === 0) {
                                if (window.OmniDialogBridge) {
                                    window.OmniDialogBridge.onNetworkSniffed(src, document.title || '');
                                }
                            }
                        }
                    }
                    probeVideoElements();
                    setTimeout(probeVideoElements, 1500);
                    setTimeout(probeVideoElements, 3500);
                } catch(e) {}
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsSnippet, null)
    }

    BackHandler {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false
        )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                if (webViewInstance?.canGoBack() == true) {
                                    webViewInstance?.goBack()
                                } else {
                                    onDismiss()
                                }
                            }) {
                                Icon(
                                    imageVector = if (canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                    contentDescription = "返回"
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = pageTitle.ifBlank { site.name },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (initialVideoUrl.isNotBlank()) {
                                IconButton(onClick = { injectAutoFillScript(webViewInstance) }) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = "填入链接",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            IconButton(onClick = { webViewInstance?.reload() }) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新")
                            }

                            IconButton(onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "无法唤起外部浏览器", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = "在外部浏览器打开")
                            }
                        }

                        if (pageProgress in 1..99) {
                            LinearProgressIndicator(
                                progress = { pageProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "提示：如有验证码请点击通过。页面出现下载按钮或播放时将自动嗅探，支持一键下载！",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
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
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                setSupportMultipleWindows(true)
                                javaScriptCanOpenWindowsAutomatically = true
                                allowFileAccess = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                            }

                            // 注入 JS 桥接供直接捕获下载与网络探测
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onCaptured(url: String, title: String) {
                                    if (url.isNotBlank()) {
                                        post {
                                            handleCapturedMedia(url, title, autoClose = true)
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onNetworkSniffed(url: String, title: String) {
                                    if (url.isNotBlank()) {
                                        post {
                                            handleCapturedMedia(url, title, autoClose = false)
                                        }
                                    }
                                }
                            }, "OmniDialogBridge")

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    pageProgress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) {
                                        pageTitle = title
                                    }
                                }

                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    val popupWv = WebView(view?.context ?: return false)
                                    popupWv.settings.javaScriptEnabled = true
                                    popupWv.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                            val target = req?.url?.toString() ?: return false
                                            if (target.endsWith(".exe", true) || target.endsWith(".apk", true) || target.endsWith(".dmg", true)) {
                                                return true
                                            }
                                            if (UrlSniffer.isDirectMediaUrl(target)) {
                                                handleCapturedMedia(target, "${site.name} 视频", autoClose = true)
                                                return true
                                            }
                                            return false
                                        }
                                    }
                                    transport.webView = popupWv
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    url?.let { currentUrl = it }
                                    canGoBack = view?.canGoBack() ?: false
                                    view?.evaluateJavascript("window.__cfRLUnblockHandlers = true;", null)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { currentUrl = it }
                                    canGoBack = view?.canGoBack() ?: false

                                    // 延迟 400ms 进行自动填入，确保 SPA 页面挂载完毕
                                    coroutineScope.launch {
                                        delay(400)
                                        injectAutoFillScript(view)
                                    }
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    // 核心网络嗅探：拦截所有媒体直链数据流
                                    if (UrlSniffer.isDirectMediaUrl(reqUrl)) {
                                        view?.post {
                                            handleCapturedMedia(reqUrl, "${site.name} 视频", autoClose = false)
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onLoadResource(view: WebView?, url: String?) {
                                    super.onLoadResource(view, url)
                                    if (url != null && UrlSniffer.isDirectMediaUrl(url)) {
                                        handleCapturedMedia(url, "${site.name} 视频", autoClose = false)
                                    }
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val uri = request?.url ?: return false
                                    val target = uri.toString()
                                    val scheme = uri.scheme?.lowercase()

                                    // 拦截非媒体安装包推广
                                    if (target.endsWith(".exe", true) || target.endsWith(".apk", true) || target.endsWith(".dmg", true)) {
                                        return true
                                    }

                                    // 拦截媒体流直链
                                    if (UrlSniffer.isDirectMediaUrl(target)) {
                                        handleCapturedMedia(target, "${site.name} 视频", autoClose = true)
                                        return true
                                    }
                                    return if (scheme == "http" || scheme == "https") {
                                        false
                                    } else {
                                        true
                                    }
                                }
                            }

                            // 核心特性：自动监听并拦截第三方网站生成的直接下载直链
                            setDownloadListener { downloadUrl, _, _, _, _ ->
                                if (downloadUrl.isNotBlank()) {
                                    if (downloadUrl.endsWith(".exe", true) || downloadUrl.endsWith(".apk", true) || downloadUrl.endsWith(".dmg", true)) {
                                        return@setDownloadListener
                                    }
                                    handleCapturedMedia(downloadUrl, "${site.name} 视频", autoClose = true)
                                }
                            }

                            val initialUrl = when {
                                site.url.contains("greenvideo.cc", ignoreCase = true) && initialVideoUrl.isNotBlank() -> {
                                    val sep = if (site.url.contains("?")) "&" else "?"
                                    "${site.url}${sep}url=${Uri.encode(initialVideoUrl)}"
                                }
                                site.url.contains("snapany.com", ignoreCase = true) && initialVideoUrl.isNotBlank() -> {
                                    val sep = if (site.url.contains("?")) "&" else "?"
                                    "${site.url}${sep}url=${Uri.encode(initialVideoUrl)}"
                                }
                                else -> site.url
                            }

                            loadUrl(initialUrl)
                            webViewInstance = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 底部高亮浮动条：一旦网络嗅探到视频直链，立即优雅升起提示并支持一键下载
                AnimatedVisibility(
                    visible = sniffedMediaUrl.isNotBlank(),
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "已嗅探到视频资源！",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = if (sniffedMediaUrl.contains(".m3u8")) "格式: HLS/M3U8 分片流" else "格式: 高清 MP4 直链",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    Toast.makeText(context, "已加入本地下载队列！", Toast.LENGTH_SHORT).show()
                                    onCapturedDownload(sniffedMediaUrl, sniffedMediaTitle.ifBlank { "${site.name} 视频" })
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("立即下载", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { sniffedMediaUrl = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭提示",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.stopLoading()
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }
}
