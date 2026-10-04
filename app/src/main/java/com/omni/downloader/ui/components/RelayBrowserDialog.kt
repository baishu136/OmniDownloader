package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.downloader.data.model.RelaySite

/**
 * 备用中转网站内置浏览器（方案 2：内置 WebView 自动化填充与下载拦截）
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
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(site.name) }
    var currentUrl by remember { mutableStateOf(site.url) }
    var canGoBack by remember { mutableStateOf(false) }

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
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
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
                            text = "提示：在网页完成解析后直接点击【下载】，将自动由 OmniDownloader 本地高速下载！",
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

                            // 注入 JS 桥接供直接捕获下载
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onCaptured(url: String, title: String) {
                                    if (url.isNotBlank()) {
                                        post {
                                            val (cleanUrl, cleanTitle) = com.omni.downloader.engine.UrlSniffer.unpackDirectMediaUrl(url, title.ifBlank { "${site.name} 中转视频" })
                                            Toast.makeText(context, "成功捕获中转下载地址，已加入下载队列！", Toast.LENGTH_SHORT).show()
                                            onCapturedDownload(cleanUrl, cleanTitle)
                                            onDismiss()
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
                                            if (target.isNotBlank()) {
                                                if (target.contains(".mp4") || target.contains(".m4a") || target.contains(".webm") ||
                                                    target.contains("download") || target.contains("googlevideo") ||
                                                    target.contains("twimg.com/video") || target.contains("twcdn.net") ||
                                                    target.contains("snapcdn") || target.contains("greenvideo")) {
                                                    val (cleanUrl, cleanTitle) = com.omni.downloader.engine.UrlSniffer.unpackDirectMediaUrl(target, "${site.name} 中转视频")
                                                    Toast.makeText(context, "成功捕获中转下载地址，已加入下载队列！", Toast.LENGTH_SHORT).show()
                                                    onCapturedDownload(cleanUrl, cleanTitle)
                                                    onDismiss()
                                                    return true
                                                }
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

                                    val encodedUrl = if (initialVideoUrl.isNotBlank()) Uri.encode(initialVideoUrl) else ""
                                    val jsSnippet = """
                                        (function() {
                                            try {
                                                window.__cfRLUnblockHandlers = true;

                                                // Hook a.click
                                                if (!window.__dialogAnchorHooked) {
                                                    window.__dialogAnchorHooked = true;
                                                    var origAnchorClick = HTMLAnchorElement.prototype.click;
                                                    HTMLAnchorElement.prototype.click = function() {
                                                        try {
                                                            var href = this.href || this.getAttribute('href') || '';
                                                            var filename = this.download || this.title || document.title || '';
                                                            if (href && typeof href === 'string' && href.indexOf('http') === 0) {
                                                                if (!href.match(/\.(exe|apk|dmg|pkg|deb|zip|rar)(\?.*)?$/i)) {
                                                                    if (window.OmniDialogBridge) {
                                                                        window.OmniDialogBridge.onCaptured(href, filename);
                                                                    }
                                                                }
                                                            }
                                                            if (this.target === '_blank') {
                                                                this.target = '_self';
                                                            }
                                                        } catch(e) {}
                                                        return origAnchorClick.apply(this, arguments);
                                                    };
                                                }

                                                // Hook window.open
                                                if (!window.__dialogOpenHooked) {
                                                    window.__dialogOpenHooked = true;
                                                    var origWindowOpen = window.open;
                                                    window.open = function(url) {
                                                        try {
                                                            if (url && typeof url === 'string' && url.indexOf('http') === 0) {
                                                                if (!url.match(/\.(exe|apk|dmg|pkg|deb|zip|rar)(\?.*)?$/i)) {
                                                                    if (window.OmniDialogBridge) {
                                                                        window.OmniDialogBridge.onCaptured(url, document.title || '');
                                                                    }
                                                                }
                                                            }
                                                        } catch(e) {}
                                                        return origWindowOpen.apply(this, arguments);
                                                    };
                                                }

                                                // 自动填入链接
                                                var rawTarget = '$encodedUrl';
                                                if (rawTarget) {
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
                                                            console.log('[OmniDownloader] Auto-filled URL into: ' + selectors[i]);
                                                            break;
                                                        }
                                                    }

                                                    // 同步 Nuxt 3 Pinia video store
                                                    try {
                                                        var nuxtRoot = document.querySelector('#__nuxt');
                                                        var vueApp = nuxtRoot ? nuxtRoot.__vue_app__ : window.__nuxt_app__;
                                                        var pinia = vueApp ? ((vueApp.config && vueApp.config.globalProperties && vueApp.config.globalProperties["\x24pinia"]) || (vueApp._context && vueApp._context.provides && vueApp._context.provides.pinia)) : null;
                                                        if (pinia && pinia._s && pinia._s.get('video')) {
                                                            var videoStore = pinia._s.get('video');
                                                            videoStore.inputUrl = targetUrl;
                                                        }
                                                    } catch(e) {}
                                                }
                                            } catch(e) {
                                                console.error('[OmniDownloader] Auto-fill failed: ' + e);
                                            }
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(jsSnippet, null)
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
                                    if (target.contains(".mp4") || target.contains(".m4a") || target.contains("googlevideo") ||
                                        target.contains("twcdn.net") || target.contains("twimg.com/video") || target.contains("snapcdn.app") ||
                                        (target.contains("download") && !target.contains("client") && !target.contains("app") && !target.contains("desktop"))) {
                                        val (cleanUrl, cleanTitle) = com.omni.downloader.engine.UrlSniffer.unpackDirectMediaUrl(target, "${site.name} 中转视频")
                                        Toast.makeText(context, "成功捕获中转下载地址，已加入下载队列！", Toast.LENGTH_SHORT).show()
                                        onCapturedDownload(cleanUrl, cleanTitle)
                                        onDismiss()
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
                                    val (cleanUrl, cleanTitle) = com.omni.downloader.engine.UrlSniffer.unpackDirectMediaUrl(downloadUrl, "${site.name} 中转视频")
                                    Toast.makeText(context, "成功捕获中转下载地址，已加入下载队列！", Toast.LENGTH_SHORT).show()
                                    onCapturedDownload(cleanUrl, cleanTitle)
                                    onDismiss()
                                }
                            }

                            loadUrl(site.url)
                            webViewInstance = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
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
