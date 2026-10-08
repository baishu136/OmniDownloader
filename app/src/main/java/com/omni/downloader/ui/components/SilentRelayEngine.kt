package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.engine.UrlSniffer
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val TAG = "SilentRelayEngine"

/**
 * 增强型后台静默中转自动化解析引擎：
 * 用户在主页点击对应中转站后，直接在后台原地完成解析与下载入队，无需弹出全屏网页打扰用户。
 * 1. 真实移动端 Viewport (360x640) 离屏挂载，防止响应式断点折叠
 * 2. 深度网络层与资源流嗅探 (shouldInterceptRequest / onLoadResource / setDownloadListener)
 * 3. 自动化自动装填链接并触发提交
 * 4. 严密直链白名单核验，严禁误捕获站点主页或非视频页面
 * 5. 毫秒级捕获直链后直接调度本地高速下载入队
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SilentRelayEngine(
    site: RelaySite,
    videoUrl: String,
    onSuccess: (directUrl: String, title: String) -> Unit,
    onError: (errorMessage: String) -> Unit,
    onProgress: (statusMessage: String) -> Unit
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isCompleted by remember { mutableStateOf(false) }

    // 25秒超时保护
    LaunchedEffect(site.id, videoUrl) {
        delay(25000)
        if (!isCompleted) {
            isCompleted = true
            mainHandler.post {
                onError("中转站响应超时，可能遭遇强力人机验证。可点击右侧「窗口排查」手动解析")
            }
        }
    }

    DisposableEffect(site.id, videoUrl) {
        onDispose {
            isCompleted = true
            webViewInstance?.let { wv ->
                try {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (ignored: Exception) {}
            }
            webViewInstance = null
        }
    }

    fun handleFoundMedia(rawUrl: String, rawTitle: String) {
        if (isCompleted || rawUrl.isBlank()) return

        val (cleanUrl, cleanTitle) = UrlSniffer.unpackDirectMediaUrl(rawUrl, rawTitle.ifBlank { "${site.name} 视频" })

        // 严格安全边界：严禁将中转站自身主页或非媒体网页当作直链回调！
        if (cleanUrl.equals(site.url, ignoreCase = true) ||
            cleanUrl.trimEnd('/') == site.url.trimEnd('/') ||
            !UrlSniffer.isDirectMediaUrl(cleanUrl)
        ) {
            return
        }

        isCompleted = true
        Log.d(TAG, "SilentEngine 成功截获有效直链: $cleanUrl (title: $cleanTitle)")
        mainHandler.post {
            onSuccess(cleanUrl, cleanTitle)
        }
    }

    // 屏幕外离屏微透明挂载，保证渲染树正常初始化但对用户完全隐形
    Box(
        modifier = Modifier
            .size(360.dp, 640.dp)
            .offset(x = (-9999).dp)
            .alpha(0.001f)
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
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                    }

                    // 注入原生 JS 桥接接口
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onResolved(directUrl: String, title: String) {
                            handleFoundMedia(directUrl, title)
                        }

                        @JavascriptInterface
                        fun onStatus(status: String) {
                            if (!isCompleted) {
                                mainHandler.post {
                                    onProgress(status)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onFailed(reason: String) {
                            if (!isCompleted) {
                                isCompleted = true
                                mainHandler.post {
                                    onError(reason.ifBlank { "中转站返回解析失败，可尝试窗口排查" })
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onLog(msg: String) {
                            Log.d(TAG, "Bridge Log: $msg")
                        }
                    }, "OmniBridge")

                    // 核心拦截 1：文件下载附件监听
                    setDownloadListener { downloadUrl, _, _, _, _ ->
                        if (!isCompleted && downloadUrl.isNotBlank()) {
                            if (!downloadUrl.endsWith(".exe", true) && !downloadUrl.endsWith(".apk", true) && !downloadUrl.endsWith(".dmg", true)) {
                                handleFoundMedia(downloadUrl, "")
                            }
                        }
                    }

                    // 核心拦截 2：弹窗与新窗口拦截
                    webChromeClient = object : WebChromeClient() {
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
                                    if (UrlSniffer.isDirectMediaUrl(target)) {
                                        handleFoundMedia(target, "")
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
                            onProgress("正在连接中转站...")
                            view?.evaluateJavascript("window.__cfRLUnblockHandlers = true;", null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            onProgress("正在自动填入链接并请求解析...")

                            val safeTargetUrl = JSONObject.quote(videoUrl)
                            val automationScript = """
                                (function() {
                                    try {
                                        var targetUrl = $safeTargetUrl;
                                        window.__cfRLUnblockHandlers = true;

                                        // Hook a.click 拦截下载直链
                                        if (!window.__omniAnchorHooked) {
                                            window.__omniAnchorHooked = true;
                                            var origAnchorClick = HTMLAnchorElement.prototype.click;
                                            HTMLAnchorElement.prototype.click = function() {
                                                try {
                                                    var href = this.href || this.getAttribute('href') || '';
                                                    var filename = this.download || this.title || document.title || '视频';
                                                    if (href && typeof href === 'string' && href.indexOf('http') === 0) {
                                                        var isCurrentSite = (href === window.location.href || href.replace(/\/+$/, '') === window.location.origin);
                                                        var isMedia = href.match(/\.(mp4|m4a|webm|flv|m3u8|mp3)(\?.*)?$/i) ||
                                                                      href.indexOf('googlevideo.com') !== -1 ||
                                                                      href.indexOf('twimg.com/video') !== -1 ||
                                                                      href.indexOf('snapcdn.app') !== -1 ||
                                                                      href.indexOf('/api/video/download') !== -1 ||
                                                                      href.indexOf('token=') !== -1;
                                                        if (!isCurrentSite && isMedia) {
                                                            if (!window.__omniDone && window.OmniBridge) {
                                                                window.__omniDone = true;
                                                                window.OmniBridge.onResolved(href, filename);
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
                                        if (!window.__omniWindowOpenHooked) {
                                            window.__omniWindowOpenHooked = true;
                                            var origWindowOpen = window.open;
                                            window.open = function(url) {
                                                try {
                                                    if (url && typeof url === 'string' && url.indexOf('http') === 0) {
                                                        var isCurrentSite = (url === window.location.href || url.replace(/\/+$/, '') === window.location.origin);
                                                        var isMedia = url.match(/\.(mp4|m4a|webm|flv|m3u8|mp3)(\?.*)?$/i) ||
                                                                      url.indexOf('googlevideo.com') !== -1 ||
                                                                      url.indexOf('twimg.com/video') !== -1 ||
                                                                      url.indexOf('snapcdn.app') !== -1 ||
                                                                      url.indexOf('/api/video/download') !== -1 ||
                                                                      url.indexOf('token=') !== -1;
                                                        if (!isCurrentSite && isMedia) {
                                                            if (!window.__omniDone && window.OmniBridge) {
                                                                window.__omniDone = true;
                                                                window.OmniBridge.onResolved(url, document.title || '视频');
                                                            }
                                                        }
                                                    }
                                                } catch(e) {}
                                                return origWindowOpen.apply(this, arguments);
                                            };
                                        }

                                        // Hook 网络请求接口响应中的直链数据
                                        var origFetch = window.fetch;
                                        if (origFetch && !window.__omniFetchHooked) {
                                            window.__omniFetchHooked = true;
                                            window.fetch = async function() {
                                                var res = await origFetch.apply(this, arguments);
                                                try {
                                                    var clone = res.clone();
                                                    clone.json().then(function(data) {
                                                        inspectData(data);
                                                    }).catch(function(){});
                                                } catch(e) {}
                                                return res;
                                            };
                                        }

                                        var origOpen = XMLHttpRequest.prototype.open;
                                        if (origOpen && !window.__omniXhrHooked) {
                                            window.__omniXhrHooked = true;
                                            XMLHttpRequest.prototype.open = function() {
                                                this.addEventListener('load', function() {
                                                    try {
                                                        var data = JSON.parse(this.responseText);
                                                        inspectData(data);
                                                    } catch(e) {}
                                                });
                                                return origOpen.apply(this, arguments);
                                            };
                                        }

                                        function inspectData(item) {
                                            if (!item || window.__omniDone) return;
                                            if (typeof item === 'string') {
                                                if (item.match(/\.(exe|apk|dmg|pkg|deb|zip|rar)(\?.*)?$/i)) return;
                                                if (item.match(/^https?:\/\/.+\.(mp4|m4a|m3u8|webm|flv|mp3)(\?.*)?$/i) ||
                                                    (item.indexOf('http') === 0 && (
                                                        item.indexOf('googlevideo.com') !== -1 ||
                                                        item.indexOf('twimg.com/video') !== -1 ||
                                                        item.indexOf('byteoversea.com') !== -1 ||
                                                        item.indexOf('twcdn.net') !== -1 ||
                                                        item.indexOf('dl.snapcdn.app') !== -1 ||
                                                        item.indexOf('greenvideo.cc/api/video/download') !== -1
                                                    ))) {
                                                    window.__omniDone = true;
                                                    if (window.OmniBridge) window.OmniBridge.onResolved(item, document.title || '视频');
                                                }
                                            } else if (typeof item === 'object') {
                                                if (item.downloadUrl && typeof item.downloadUrl === 'string' && item.downloadUrl.indexOf('http') === 0) {
                                                    window.__omniDone = true;
                                                    if (window.OmniBridge) window.OmniBridge.onResolved(item.downloadUrl, item.displayTitle || item.fileName || document.title || '视频');
                                                    return;
                                                }
                                                if (item.code === 500 || item.code === 530 || item.statusCode === 404 || item.status === 'error' || item.status === 'fail') {
                                                    var errorMsg = item.msg || item.message || '中转站提示未找到视频';
                                                    if (errorMsg.indexOf('must not be blank') === -1) {
                                                        window.__omniDone = true;
                                                        if (window.OmniBridge) window.OmniBridge.onFailed(errorMsg);
                                                        return;
                                                    }
                                                }
                                                for (var k in item) {
                                                    inspectData(item[k]);
                                                }
                                            }
                                        }

                                        // 自动填入输入框
                                        function fillInput() {
                                            var selectors = [
                                                'input.n-input__input-el',
                                                '#s_input',
                                                'input[name="q"]',
                                                'input[name="link"]',
                                                'input[name="url"]',
                                                'input.search__input',
                                                'input[placeholder*="http"]',
                                                'input[placeholder*="链接"]',
                                                'input[placeholder*="粘贴"]',
                                                'input[placeholder*="Link"]',
                                                'input[type="text"]',
                                                'input[type="url"]'
                                            ];
                                            var inputEl = null;
                                            for (var i = 0; i < selectors.length; i++) {
                                                var el = document.querySelector(selectors[i]);
                                                if (el) {
                                                    inputEl = el;
                                                    break;
                                                }
                                            }

                                            if (inputEl) {
                                                inputEl.focus();
                                                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                                                if (setter && setter.set) {
                                                    setter.set.call(inputEl, targetUrl);
                                                } else {
                                                    inputEl.value = targetUrl;
                                                }
                                                try {
                                                    inputEl.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, data: targetUrl, inputType: 'insertText' }));
                                                    inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, data: targetUrl, inputType: 'insertText' }));
                                                } catch(e) {}
                                                inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                                                inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                                                inputEl.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: targetUrl }));
                                            }

                                            // 同步 Nuxt 3 Pinia 并直调 extractVideoUrl
                                            try {
                                                var nuxtRoot = document.querySelector('#__nuxt');
                                                var vueApp = nuxtRoot ? nuxtRoot.__vue_app__ : window.__nuxt_app__;
                                                var pinia = vueApp ? ((vueApp.config && vueApp.config.globalProperties && vueApp.config.globalProperties["\x24pinia"]) || (vueApp._context && vueApp._context.provides && vueApp._context.provides.pinia) || vueApp["\x24pinia"]) : null;
                                                if (pinia && pinia._s && pinia._s.get('video')) {
                                                    var videoStore = pinia._s.get('video');
                                                    videoStore.inputUrl = targetUrl;
                                                    if (typeof videoStore.extractVideoUrl === 'function' && !videoStore.__omniExtracting) {
                                                        videoStore.__omniExtracting = true;
                                                        console.log('[OmniSilent] Pinia directly invoking extractVideoUrl');
                                                        videoStore.extractVideoUrl({ url: targetUrl }).catch(function(e){ console.error(e); });
                                                    }
                                                }
                                            } catch(e) {}

                                            return inputEl;
                                        }

                                        // 触发提交
                                        function triggerSubmit(inputEl) {
                                            window.__cfRLUnblockHandlers = true;

                                            // 策略 A: 特化站点核心接口
                                            if (typeof window.ksearchvideo === 'function') {
                                                window.ksearchvideo();
                                                if (window.OmniBridge) window.OmniBridge.onStatus('已调用解析接口，等待直链生成...');
                                                return true;
                                            }
                                            if (typeof window.searchVideo === 'function') {
                                                window.searchVideo();
                                                return true;
                                            }

                                            // 策略 B: 查找提交按钮
                                            var submitBtn = document.querySelector('button.button-1, button.btn-red, button.btn-search, button[type="submit"], #btn-submit, #search-form button');
                                            if (!submitBtn) {
                                                var buttons = Array.from(document.querySelectorAll('button, input[type="submit"], div[role="button"]'));
                                                for (var j = 0; j < buttons.length; j++) {
                                                    var b = buttons[j];
                                                    var txt = (b.innerText || b.value || '').trim();
                                                    if (txt.indexOf('客户端') !== -1 || txt.indexOf('App') !== -1 || txt.indexOf('VIP') !== -1) continue;
                                                    if (txt.indexOf('开始') !== -1 || txt.indexOf('提取') !== -1 ||
                                                        txt.indexOf('解析') !== -1 || txt.indexOf('Search') !== -1 ||
                                                        txt.indexOf('Download') !== -1 || txt.indexOf('Go') !== -1) {
                                                        submitBtn = b;
                                                        break;
                                                    }
                                                }
                                            }

                                            if (submitBtn) {
                                                try {
                                                    submitBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                                } catch(e) {}
                                                submitBtn.click();
                                                if (window.OmniBridge) window.OmniBridge.onStatus('已提交解析请求，正在提取直链...');
                                                return true;
                                            } else if (inputEl) {
                                                try {
                                                    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                                                    inputEl.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                                                } catch(e) {}
                                            }
                                            return false;
                                        }

                                        var hasSubmitted = false;
                                        var fillAttempts = 0;
                                        var fillTimer = setInterval(function() {
                                            fillAttempts++;
                                            if (window.__omniDone) {
                                                clearInterval(fillTimer);
                                                return;
                                            }
                                            var el = fillInput();
                                            if (el && !hasSubmitted) {
                                                hasSubmitted = true;
                                                setTimeout(function() {
                                                    triggerSubmit(el);
                                                }, 300);
                                            }
                                            if (fillAttempts > 40) {
                                                clearInterval(fillTimer);
                                            }
                                        }, 400);

                                        // 扫描页面 DOM 视频直链与清晰度下载按钮
                                        function scanDomMedia() {
                                            if (window.__omniDone) return;

                                            // 1. 深度检测 Nuxt Pinia videoStore 中的解析完成状态与直链
                                            try {
                                                var nuxtApp = window.__nuxt_app__ || (document.querySelector('#__nuxt') ? document.querySelector('#__nuxt').__vue_app__ : null);
                                                var pinia = nuxtApp ? ((nuxtApp.config && nuxtApp.config.globalProperties && nuxtApp.config.globalProperties["\x24pinia"]) || (nuxtApp._context && nuxtApp._context.provides && nuxtApp._context.provides.pinia) || nuxtApp["\x24pinia"]) : null;
                                                if (pinia && pinia._s && pinia._s.get('video')) {
                                                    var vs = pinia._s.get('video');
                                                    if (vs.videoExtractInfo && vs.videoExtractInfo.vid) {
                                                        var list = vs.videoExtractInfo.videoItemVoList || [];
                                                        if (vs.videoExtractInfo.videoInfoVoList && vs.videoExtractInfo.videoInfoVoList.length > 0) {
                                                            vs.videoExtractInfo.videoInfoVoList.forEach(function(item) {
                                                                if (item.videoItemVoList) list = list.concat(item.videoItemVoList);
                                                            });
                                                        }
                                                        // 优先提取已有的 baseUrl
                                                        for (var idx = 0; idx < list.length; idx++) {
                                                            var vItem = list[idx];
                                                            if (vItem && vItem.baseUrl && (vItem.baseUrl.match(/\.(mp4|m4a|webm|flv|m3u8)(\?.*)?$/i) || vItem.baseUrl.indexOf('douyinvod.com') !== -1 || vItem.baseUrl.indexOf('twimg.com') !== -1 || vItem.baseUrl.indexOf('/api/video/download') !== -1)) {
                                                                window.__omniDone = true;
                                                                if (window.OmniBridge) window.OmniBridge.onResolved(vItem.baseUrl, vs.videoExtractInfo.displayTitle || '视频');
                                                                return;
                                                            }
                                                        }
                                                        // 触发 doDownloadVideo 生成下载直链
                                                        if (!vs.__omniDownloaded && typeof vs.doDownloadVideo === 'function') {
                                                            vs.__omniDownloaded = true;
                                                            var firstQuality = (list[0] && list[0].quality) || '1080P';
                                                            vs.doDownloadVideo({ host: vs.videoExtractInfo.host, vid: vs.videoExtractInfo.vid, quality: firstQuality }).then(function(res) {
                                                                if (res && res.data && res.data.status === 2) {
                                                                    vs.getDownloadVideoInfo({ host: vs.videoExtractInfo.host, vid: vs.videoExtractInfo.vid, quality: firstQuality }).then(function(info) {
                                                                        if (info && info.data && info.data.downloadUrl) {
                                                                            window.__omniDone = true;
                                                                            if (window.OmniBridge) window.OmniBridge.onResolved(info.data.downloadUrl, vs.videoExtractInfo.displayTitle || '视频');
                                                                        }
                                                                    });
                                                                }
                                                            }).catch(function(e){ console.error(e); });
                                                        }
                                                    }
                                                }
                                            } catch(e) {}

                                            // 2. 扫描并模拟点击清晰度按钮 (如 1080P, 720P, 下载等)
                                            var buttons = Array.from(document.querySelectorAll('button, a, div[role="button"]'));
                                            for (var bIdx = 0; bIdx < buttons.length; bIdx++) {
                                                var btn = buttons[bIdx];
                                                if (btn.__omniClicked || btn.disabled) continue;
                                                var txt = (btn.innerText || btn.value || '').trim();
                                                if (txt === '开始' || txt.indexOf('客户端') !== -1 || txt.indexOf('App') !== -1 || txt.indexOf('VIP') !== -1) continue;

                                                var isQualityBtn = (
                                                    txt.indexOf('1080') !== -1 || txt.indexOf('720') !== -1 || txt.indexOf('480') !== -1 ||
                                                    txt.indexOf('360') !== -1 || txt.indexOf('超清') !== -1 || txt.indexOf('高清') !== -1 ||
                                                    txt.indexOf('原画') !== -1 || txt.indexOf('下载') !== -1 || txt.indexOf('Download') !== -1 ||
                                                    txt.indexOf('无水印') !== -1 || txt.indexOf('点击下载') !== -1
                                                );

                                                if (isQualityBtn) {
                                                    btn.__omniClicked = true;
                                                    console.log('[OmniSilent] Triggering quality button:', txt);
                                                    if (window.OmniBridge) window.OmniBridge.onStatus('已捕获清晰度选项 (' + txt + ')，正在提取直链...');
                                                    try {
                                                        btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
                                                        btn.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
                                                        btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                                    } catch(e) {}
                                                    btn.click();
                                                    break;
                                                }
                                            }

                                            // 3. 扫描已生成的 a[href] 链接
                                            var links = Array.from(document.querySelectorAll('a[href]'));
                                            for (var k = 0; k < links.length; k++) {
                                                var a = links[k];
                                                var href = a.getAttribute('href') || '';
                                                if (href.indexOf('http') === 0 && href !== window.location.href && href.replace(/\/+$/, '') !== window.location.origin) {
                                                    if (href.match(/\.(mp4|m4a|m3u8|webm|flv|mp3)(\?.*)?$/i) ||
                                                        (href.indexOf('googlevideo.com') !== -1 || href.indexOf('twimg.com/video') !== -1 || href.indexOf('snapcdn.app') !== -1 || href.indexOf('/api/video/download') !== -1)) {
                                                        window.__omniDone = true;
                                                        if (window.OmniBridge) window.OmniBridge.onResolved(href, document.title || '视频');
                                                        return;
                                                    }
                                                }
                                            }

                                            // 4. 扫描 video 元素
                                            var videos = document.querySelectorAll('video, source');
                                            for (var vIdx = 0; vIdx < videos.length; vIdx++) {
                                                var v = videos[vIdx];
                                                var src = v.src || v.getAttribute('src') || '';
                                                if (src && src.indexOf('http') === 0 && src.indexOf(window.location.origin) !== 0) {
                                                    window.__omniDone = true;
                                                    if (window.OmniBridge) window.OmniBridge.onResolved(src, document.title || '视频');
                                                    return;
                                                }
                                            }
                                        }

                                        setInterval(scanDomMedia, 500);

                                    } catch(e) {}
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(automationScript, null)
                        }

                        // 核心拦截 3：网络层深度拦截 (shouldInterceptRequest)
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            if (UrlSniffer.isDirectMediaUrl(reqUrl)) {
                                view?.post {
                                    handleFoundMedia(reqUrl, "")
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            super.onLoadResource(view, url)
                            if (url != null && UrlSniffer.isDirectMediaUrl(url)) {
                                handleFoundMedia(url, "")
                            }
                        }

                        // 核心拦截 4：页面重定向拦截
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            if (target.endsWith(".exe", true) || target.endsWith(".apk", true) || target.endsWith(".dmg", true)) {
                                return true
                            }
                            if (UrlSniffer.isDirectMediaUrl(target)) {
                                handleFoundMedia(target, "")
                                return true
                            }
                            return false
                        }
                    }

                    val initialUrl = when {
                        site.url.contains("greenvideo.cc", ignoreCase = true) && videoUrl.isNotBlank() -> {
                            val sep = if (site.url.contains("?")) "&" else "?"
                            "${site.url}${sep}url=${Uri.encode(videoUrl)}"
                        }
                        site.url.contains("snapany.com", ignoreCase = true) && videoUrl.isNotBlank() -> {
                            val sep = if (site.url.contains("?")) "&" else "?"
                            "${site.url}${sep}url=${Uri.encode(videoUrl)}"
                        }
                        else -> site.url
                    }

                    loadUrl(initialUrl)
                    webViewInstance = this
                }
            },
            update = { wv ->
                webViewInstance = wv
            }
        )
    }
}
