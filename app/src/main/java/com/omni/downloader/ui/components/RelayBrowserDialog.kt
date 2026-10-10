package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
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
 * 嗅探到的媒体流资源条目 (对齐专业级嗅探猫 Cat Catch 数据结构)
 */
data class SniffedMediaItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val format: String, // MP4, M3U8, 音频, WebM, FLV
    val host: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 现代原生级中转站媒体嗅探浏览器（集成类似“嗅探猫”的专业媒体嗅探与多资源管理体系）：
 * 1. 深度嗅探注入 (Fetch Hook, XHR Hook, HTMLMediaElement Hook, DOM 定期探测与主动深度搜索)
 * 2. 动态捕获列表：支持多流识别、按格式打标 (MP4/M3U8/音频/WebM/FLV)、多选/单选下载、复制直链
 * 3. 顶部/底部常驻“🐱 嗅探猫”资源管理器悬浮胶囊与详情抽屉面板
 * 4. 原生浏览器交互，完美支持 Turnstile、滑块验证码与视频源直链原生捕获
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

    // 嗅探到的媒体列表与面板状态
    val sniffedMediaList = remember { mutableStateListOf<SniffedMediaItem>() }
    var showSnifferSheet by remember { mutableStateOf(false) }

    // 复制直链工具函数
    fun copyToClipboard(text: String, label: String = "直链") {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    // 复制全部嗅探到的直链
    fun copyAllSniffedUrls() {
        if (sniffedMediaList.isEmpty()) {
            Toast.makeText(context, "暂无嗅探到的直链", Toast.LENGTH_SHORT).show()
            return
        }
        val allUrls = sniffedMediaList.joinToString("\n") { it.url }
        copyToClipboard(allUrls, "全部媒体直链")
    }

    // 格式识别辅助函数
    fun detectMediaFormat(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "M3U8"
            lower.contains(".mp4") -> "MP4"
            lower.contains(".m4a") || lower.contains(".mp3") || lower.contains(".aac") || lower.contains(".flac") -> "音频"
            lower.contains(".webm") -> "WebM"
            lower.contains(".flv") -> "FLV"
            else -> "视频"
        }
    }

    // 统一处理捕获媒体并加入嗅探列表
    fun handleCapturedMedia(url: String, title: String, autoClose: Boolean = false) {
        val (cleanUrl, cleanTitle) = UrlSniffer.unpackDirectMediaUrl(url, title.ifBlank { "${site.name} 视频" })
        if (cleanUrl.equals(site.url, ignoreCase = true) ||
            cleanUrl.trimEnd('/') == site.url.trimEnd('/') ||
            !UrlSniffer.isDirectMediaUrl(cleanUrl)
        ) {
            return
        }

        val host = try { Uri.parse(cleanUrl).host ?: "" } catch (_: Exception) { "" }
        val format = detectMediaFormat(cleanUrl)

        // 去重加入嗅探猫列表（最新捕获的置顶）
        if (sniffedMediaList.none { it.url.equals(cleanUrl, ignoreCase = true) }) {
            val item = SniffedMediaItem(
                url = cleanUrl,
                title = cleanTitle,
                format = format,
                host = host
            )
            sniffedMediaList.add(0, item)
        }

        if (autoClose) {
            Toast.makeText(context, "成功捕获下载地址，已加入下载队列！", Toast.LENGTH_SHORT).show()
            onCapturedDownload(cleanUrl, cleanTitle)
            onDismiss()
        }
    }

    // 注入“嗅探猫”级全能嗅探与钩子脚本
    fun injectCatCatchSnifferScript(wv: WebView?) {
        if (wv == null) return
        val jsSnippet = """
            (function() {
                if (window.__omniCatCatchInjected) return;
                window.__omniCatCatchInjected = true;

                function reportMedia(url, title) {
                    if (!url || typeof url !== 'string' || !url.startsWith('http')) return;
                    var lower = url.toLowerCase();
                    var isMedia = lower.includes('.mp4') || lower.includes('.m3u8') ||
                                  lower.includes('.m4a') || lower.includes('.mp3') ||
                                  lower.includes('.webm') || lower.includes('.flv') ||
                                  lower.includes('dl.snapcdn.app') || lower.includes('video.twimg.com') ||
                                  lower.includes('googlevideo.com/videoplayback') || lower.includes('byteoversea.com') ||
                                  lower.includes('ibytedtos.com') || lower.includes('tiktokcdn.com') ||
                                  lower.includes('douyinvod.com') || lower.includes('snssdk.com') ||
                                  lower.includes('yximgs.com') || lower.includes('xhscdn.com');
                    var isApi = lower.includes('/api/') || lower.includes('/ajax/') || lower.includes('/extract/') || lower.includes('cnsimpleextract');
                    if (isMedia && !isApi) {
                        if (window.OmniDialogBridge) {
                            window.OmniDialogBridge.onNetworkSniffed(url, title || document.title || '');
                        }
                    }
                }

                // 1. Hook Fetch 请求
                try {
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function() {
                            try {
                                var reqUrl = typeof arguments[0] === 'string' ? arguments[0] : (arguments[0] && arguments[0].url ? arguments[0].url : '');
                                if (reqUrl) reportMedia(reqUrl);
                            } catch(e) {}
                            return origFetch.apply(this, arguments);
                        };
                    }
                } catch(e) {}

                // 2. Hook XMLHttpRequest
                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        try {
                            if (url && typeof url === 'string') reportMedia(url);
                        } catch(e) {}
                        return origOpen.apply(this, arguments);
                    };
                } catch(e) {}

                // 3. Hook HTMLMediaElement
                try {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        try {
                            var src = this.currentSrc || this.src;
                            if (src) reportMedia(src);
                        } catch(e) {}
                        return origPlay.apply(this, arguments);
                    };
                } catch(e) {}

                // 4. 定时扫描 DOM 中媒体元素
                function scanMediaDOM() {
                    try {
                        var els = document.querySelectorAll('video, audio, source');
                        for (var i = 0; i < els.length; i++) {
                            var src = els[i].src || els[i].getAttribute('src') || els[i].getAttribute('data-src') || '';
                            if (src) reportMedia(src);
                        }
                    } catch(e) {}
                }
                scanMediaDOM();
                setInterval(scanMediaDOM, 1500);

                // 5. 挂载深度搜索 (Deep Search) 函数
                window.__omniDeepSearch = function() {
                    scanMediaDOM();
                    var count = 0;
                    try {
                        var html = document.documentElement.innerHTML;
                        var regex = /(https?:\/\/[^"'\s\\]+?\.(mp4|m3u8|m4a|mp3|flv|webm)(\?[^"'\s\\]*)?)/gi;
                        var match;
                        while ((match = regex.exec(html)) !== null) {
                            reportMedia(match[1]);
                            count++;
                        }
                    } catch(e) {}
                    return count;
                };
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsSnippet, null)
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
                } catch(e) {}
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsSnippet, null)
    }

    // 触发主动深度搜索
    fun triggerDeepSearch() {
        webViewInstance?.evaluateJavascript("if (window.__omniDeepSearch) { window.__omniDeepSearch(); } else { 0; }") { res ->
            Toast.makeText(context, "深度扫描完毕，已探测隐藏媒体资源！", Toast.LENGTH_SHORT).show()
        }
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
                                    .padding(horizontal = 6.dp)
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

                            // 嗅探猫资源徽章按钮 (显示捕获数量，点击唤起资源面板)
                            FilledTonalButton(
                                onClick = { showSnifferSheet = true },
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (sniffedMediaList.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (sniffedMediaList.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = "🐱 嗅探 (${sniffedMediaList.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
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
                            text = "嗅探猫模式运行中：自动拦截媒体流，可点击右上角「🐱 嗅探」查看全部资源或深度搜索！",
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

                                    // 延迟注入嗅探猫核心钩子脚本与自动填入
                                    coroutineScope.launch {
                                        delay(300)
                                        injectCatCatchSnifferScript(view)
                                        injectAutoFillScript(view)
                                    }
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    val method = request.method?.uppercase() ?: "GET"
                                    // 核心网络嗅探：仅拦截真正的 GET 媒体数据流，绝不拦截 POST 接口
                                    if (method == "GET" && UrlSniffer.isDirectMediaUrl(reqUrl)) {
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

                // 底部高亮浮动条：显示已捕获数量、支持查看列表与下载最新资源
                AnimatedVisibility(
                    visible = sniffedMediaList.isNotEmpty(),
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    val latestItem = sniffedMediaList.firstOrNull()
                    Surface(
                        shape = RoundedCornerShape(16.dp),
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
                                    text = "🐱 嗅探猫已捕获 ${sniffedMediaList.size} 个媒体资源",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = latestItem?.let { "最新: [${it.format}] ${it.host}" } ?: "点击右侧查看",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))

                            // 查看列表按钮
                            OutlinedButton(
                                onClick = { showSnifferSheet = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("列表", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 立即下载最新项按钮
                            Button(
                                onClick = {
                                    latestItem?.let {
                                        Toast.makeText(context, "已加入本地下载队列！", Toast.LENGTH_SHORT).show()
                                        onCapturedDownload(it.url, it.title.ifBlank { "${site.name} 视频" })
                                        onDismiss()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("下载最新", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 🐱 嗅探猫 · 媒体资源管理器抽屉 (ModalBottomSheet)
        // ==========================================
        if (showSnifferSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSnifferSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp)
                ) {
                    // 抽屉头部标题与操作栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🐱 嗅探猫",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    text = "${sniffedMediaList.size} 个资源",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { triggerDeepSearch() }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "深度搜索",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { copyAllSniffedUrls() }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制全部",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            IconButton(onClick = { sniffedMediaList.clear() }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "清空列表",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // 工具快捷功能说明行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "点击「下载」直接入队，点击「深度搜索」主动挖掘隐藏媒体",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    if (sniffedMediaList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "暂未嗅探到媒体流",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "可尝试播放页面中的视频，或点击右上角「🔍 深度搜索」",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            items(sniffedMediaList, key = { it.id }) { item ->
                                SniffedMediaCard(
                                    item = item,
                                    onDownload = {
                                        Toast.makeText(context, "已加入本地下载队列！", Toast.LENGTH_SHORT).show()
                                        onCapturedDownload(item.url, item.title)
                                        showSnifferSheet = false
                                        onDismiss()
                                    },
                                    onCopy = {
                                        copyToClipboard(item.url, "媒体直链")
                                    }
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

/**
 * 嗅探猫单项媒体资源卡片
 */
@Composable
private fun SniffedMediaCard(
    item: SniffedMediaItem,
    onDownload: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 格式胶囊彩标
            val badgeColor = when (item.format) {
                "M3U8" -> Color(0xFFF97316) // 橙色
                "MP4" -> Color(0xFF3B82F6)  // 蓝色
                "音频" -> Color(0xFF10B981) // 绿色
                "WebM" -> Color(0xFF8B5CF6) // 紫色
                else -> Color(0xFF6B7280)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.format,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 标题与来源域名
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "来源: ${item.host.ifBlank { "直链 CDN" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 操作按键：复制与下载
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制直链",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("下载", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
