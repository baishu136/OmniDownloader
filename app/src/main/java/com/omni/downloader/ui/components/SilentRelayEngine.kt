package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
 * 增强型后台静默中转自动化解析引擎 (Headless WebView)
 * 专门针对 X2Twitter、TwitterSaver、SnapAny、GreenVideo 等复杂反爬与 SPA 框架优化：
 * 1. 真实移动端 Viewport (360x640) 离屏渲染，避免 1px 导致 Cloudflare Turnstile 阻断或响应式折叠
 * 2. 自动破除 Cloudflare Rocket Loader 的 __cfRLUnblockHandlers 阻塞
 * 3. 直调站点特化解析函数 (如 ksearchvideo) 并穿透 Vue/React/jQuery 双向绑定
 * 4. 20 秒自适应轮询与状态汇报，彻底解决“卡填入 / 卡解析”
 * 5. 严格过滤非媒体推广文件，毫秒级捕获真实视频直链
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
                onError("中转站响应超时，可能遭遇强力人机验证或链接失效")
            }
        }
    }

    DisposableEffect(site.id, videoUrl) {
        onDispose {
            isCompleted = true
            webViewInstance?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            }
            webViewInstance = null
        }
    }

    // 使用真实移动端尺寸 (360x640) 并在屏幕外微透明挂载，保证 Turnstile 验证码正常初始化且对用户完全无感
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
                            Log.d(TAG, "Bridge onResolved: $directUrl (title: $title)")
                            if (!isCompleted && directUrl.isNotBlank()) {
                                val (cleanUrl, cleanTitle) = UrlSniffer.unpackDirectMediaUrl(directUrl, title)
                                // 关键防御：严禁将中转站自身主页或非媒体网页当作视频直链回调！
                                if (cleanUrl.equals(site.url, ignoreCase = true) ||
                                    cleanUrl.trimEnd('/') == site.url.trimEnd('/') ||
                                    !UrlSniffer.isDirectMediaUrl(cleanUrl)) {
                                    Log.w(TAG, "忽略非媒体直链或站点自身URL: $cleanUrl")
                                    return
                                }
                                isCompleted = true
                                mainHandler.post {
                                    onSuccess(cleanUrl, cleanTitle)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onStatus(status: String) {
                            Log.d(TAG, "Bridge onStatus: $status")
                            if (!isCompleted) {
                                mainHandler.post {
                                    onProgress(status)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onFailed(reason: String) {
                            Log.e(TAG, "Bridge onFailed: $reason")
                            if (!isCompleted) {
                                isCompleted = true
                                mainHandler.post {
                                    onError(reason.ifBlank { "中转站返回解析失败" })
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onLog(msg: String) {
                            Log.d(TAG, "Bridge JS Log: $msg")
                        }
                    }, "OmniBridge")

                    // 监听下载触发
                    setDownloadListener { downloadUrl, _, _, _, _ ->
                        Log.d(TAG, "DownloadListener intercepted: $downloadUrl")
                        // 过滤非音视频安装包文件
                        if (downloadUrl.endsWith(".exe", true) || downloadUrl.endsWith(".apk", true) ||
                            downloadUrl.endsWith(".dmg", true) || downloadUrl.endsWith(".zip", true) ||
                            downloadUrl.endsWith(".pkg", true)) {
                            Log.d(TAG, "Ignored non-media download: $downloadUrl")
                            return@setDownloadListener
                        }
                        if (!isCompleted && downloadUrl.isNotBlank()) {
                            val (cleanUrl, cleanTitle) = UrlSniffer.unpackDirectMediaUrl(downloadUrl, "")
                            if (cleanUrl.equals(site.url, ignoreCase = true) || cleanUrl.trimEnd('/') == site.url.trimEnd('/') ||
                                !UrlSniffer.isDirectMediaUrl(cleanUrl)) {
                                Log.w(TAG, "DownloadListener 忽略非媒体直链: $cleanUrl")
                                return@setDownloadListener
                            }
                            isCompleted = true
                            mainHandler.post {
                                onSuccess(cleanUrl, cleanTitle)
                            }
                        }
                    }

                    // 核心支撑：捕获通过 target="_blank" 或 window.open 弹出的下载直链窗口
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
                                    Log.d(TAG, "onCreateWindow intercepted: $target")
                                    if (target.endsWith(".exe", true) || target.endsWith(".apk", true) || target.endsWith(".dmg", true)) {
                                        return true
                                    }
                                    if (!isCompleted && target.isNotBlank()) {
                                        if (UrlSniffer.isDirectMediaUrl(target)) {
                                            isCompleted = true
                                            val (cleanUrl, cleanTitle) = UrlSniffer.unpackDirectMediaUrl(target, "")
                                            mainHandler.post {
                                                onSuccess(cleanUrl, cleanTitle)
                                            }
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
                            Log.d(TAG, "Page started: $url")
                            onProgress("正在连接中转站...")
                            // 预注入破除 Cloudflare Rocket Loader 限制
                            view?.evaluateJavascript("window.__cfRLUnblockHandlers = true;", null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "Page finished: $url, injecting enhanced automation...")
                            onProgress("正在自动填入链接并请求解析...")

                            val safeTargetUrl = JSONObject.quote(videoUrl)
                            val automationScript = """
                                (function() {
                                    try {
                                        var targetUrl = $safeTargetUrl;
                                        window.__cfRLUnblockHandlers = true; // 强制放行 Cloudflare Rocket Loader 按钮事件

                                        console.log('[OmniSilent] Enhanced automation starting for:', targetUrl);

                                        // 0. 全局 Hook HTMLAnchorElement.prototype.click (核心防御：捕获动态创建的 <a target="_blank"> 直链)
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
                                                            console.log('[OmniSilent] Intercepted a.click() download url:', href);
                                                            if (!window.__omniDone && window.OmniBridge) {
                                                                window.__omniDone = true;
                                                                window.OmniBridge.onResolved(href, filename);
                                                            }
                                                        }
                                                    }
                                                    // 强制将 target="_blank" 改为 "_self"，防止在无弹窗管理器的 WebView 中被静默抛弃
                                                    if (this.target === '_blank') {
                                                        this.target = '_self';
                                                    }
                                                } catch(e) {
                                                    console.error('[OmniSilent] a.click hook error: ' + e);
                                                }
                                                return origAnchorClick.apply(this, arguments);
                                            };
                                        }

                                        // 0.1 全局 Hook window.open (捕获弹窗式视频直链)
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
                                                            console.log('[OmniSilent] Intercepted window.open url:', url);
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

                                        // 0.2 Hook 站点错误处理函数 (如 X2Twitter 的 renderFail)
                                        if (typeof window.renderFail === 'function' && !window.__omniRenderFailHooked) {
                                            window.__omniRenderFailHooked = true;
                                            var origRenderFail = window.renderFail;
                                            window.renderFail = function(msg) {
                                                if (!window.__omniDone && window.OmniBridge) {
                                                    window.__omniDone = true;
                                                    window.OmniBridge.onFailed(msg || '中转站解析失败');
                                                }
                                                return origRenderFail.apply(this, arguments);
                                            };
                                        }

                                        // 1. Hook 网络请求，嗅探接口返回的音视频直链或错误提示
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
                                                // 排除非媒体文件
                                                if (item.match(/\.(exe|apk|dmg|pkg|deb|zip|rar)(\?.*)?$/i)) return;
                                                if (item.match(/^https?:\/\/.+\.(mp4|m4a|m3u8|webm|flv|mp3)(\?.*)?$/i) ||
                                                    (item.indexOf('http') === 0 && (
                                                        item.indexOf('googlevideo') !== -1 ||
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
                                                // 捕获特定直链字段 (如 downloadUrl, playUrl)
                                                if (item.downloadUrl && typeof item.downloadUrl === 'string' && item.downloadUrl.indexOf('http') === 0) {
                                                    window.__omniDone = true;
                                                    if (window.OmniBridge) window.OmniBridge.onResolved(item.downloadUrl, item.displayTitle || item.fileName || document.title || '视频');
                                                    return;
                                                }
                                                // 捕获显式错误响应 (如 statusCode 404/500, status error, 或携带 msg 失败文本)
                                                if (item.code === 500 || item.code === 530 || item.statusCode === 404 || item.status === 'error' || item.status === 'fail' || (item.status === 'ok' && item.msg)) {
                                                    var errorMsg = item.msg || item.message || '中转站提示未找到视频或已被删除';
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

                                        // 2. 强力多选择器输入框填充 (深度兼容 Naive UI / Vue 3 / Nuxt 3 / React)
                                        function fillInput() {
                                            var selectors = [
                                                'input.n-input__input-el',
                                                '#s_input',
                                                'input[name="q"]',
                                                'input[name="link"]',
                                                'input[name="url"]',
                                                'input.search__input',
                                                'input[placeholder*="Twitter"]',
                                                'input[placeholder*="X"]',
                                                'input[placeholder*="链接"]',
                                                'input[placeholder*="粘贴"]',
                                                'input[placeholder*="http"]',
                                                'input[placeholder*="Link"]',
                                                'input[type="text"]',
                                                'input[type="url"]',
                                                'input',
                                                'textarea'
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

                                                // 原生原型 Setter 穿透
                                                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                                                if (setter && setter.set) {
                                                    setter.set.call(inputEl, targetUrl);
                                                } else {
                                                    inputEl.value = targetUrl;
                                                }

                                                // jQuery 联动
                                                if (window.jQuery) {
                                                    try { window.jQuery(inputEl).val(targetUrl).trigger('change').trigger('input'); } catch(e){}
                                                }

                                                // 全套现代化原生事件派发 (触发 Vue 3 / Naive UI 的响应式双向更新)
                                                try {
                                                    inputEl.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, data: targetUrl, inputType: 'insertText' }));
                                                    inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, data: targetUrl, inputType: 'insertText' }));
                                                } catch(e) {}
                                                inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                                                inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                                                inputEl.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: targetUrl }));
                                                inputEl.dispatchEvent(new Event('keyup', { bubbles: true }));
                                                inputEl.dispatchEvent(new Event('blur', { bubbles: true }));
                                            }

                                            // 穿透同步 Nuxt 3 Pinia video store (针对 GreenVideo 特效提升)
                                            try {
                                                var nuxtRoot = document.querySelector('#__nuxt');
                                                var vueApp = nuxtRoot ? nuxtRoot.__vue_app__ : window.__nuxt_app__;
                                                var pinia = vueApp ? ((vueApp.config && vueApp.config.globalProperties && vueApp.config.globalProperties["\x24pinia"]) || (vueApp._context && vueApp._context.provides && vueApp._context.provides.pinia)) : null;
                                                if (pinia && pinia._s && pinia._s.get('video')) {
                                                    var videoStore = pinia._s.get('video');
                                                    if (videoStore.inputUrl !== targetUrl) {
                                                        videoStore.inputUrl = targetUrl;
                                                        console.log('[OmniSilent] Synced targetUrl into Nuxt Pinia videoStore.inputUrl');
                                                    }
                                                }
                                            } catch(e) {}

                                            return inputEl;
                                        }

                                        // 3. 强力提交/解析触发
                                        function triggerSubmit(inputEl) {
                                            window.__cfRLUnblockHandlers = true;

                                            // 策略 A：直接调用已知站点的专属核心函数 (X2Twitter / TwitterSaver)
                                            if (typeof window.ksearchvideo === 'function') {
                                                console.log('[OmniSilent] Directly calling ksearchvideo()');
                                                window.ksearchvideo();
                                                if (window.OmniBridge) window.OmniBridge.onStatus('已直接调用解析接口，等待直链生成...');
                                                return true;
                                            }
                                            if (typeof window.searchVideo === 'function') {
                                                window.searchVideo();
                                                return true;
                                            }

                                            // 策略 B：针对 GreenVideo 的 Nuxt Pinia 直接调用
                                            try {
                                                var nuxtRoot = document.querySelector('#__nuxt');
                                                var vueApp = nuxtRoot ? nuxtRoot.__vue_app__ : window.__nuxt_app__;
                                                var pinia = vueApp ? ((vueApp.config && vueApp.config.globalProperties && vueApp.config.globalProperties["\x24pinia"]) || (vueApp._context && vueApp._context.provides && vueApp._context.provides.pinia)) : null;
                                                if (pinia && pinia._s && pinia._s.get('video')) {
                                                    var videoStore = pinia._s.get('video');
                                                    if (typeof videoStore.extractVideo === 'function' && !videoStore.__omniCalled) {
                                                        videoStore.__omniCalled = true;
                                                        videoStore.inputUrl = targetUrl;
                                                        console.log('[OmniSilent] Directly calling Nuxt Pinia videoStore.extractVideo()');
                                                        videoStore.extractVideo({ url: targetUrl });
                                                        if (window.OmniBridge) window.OmniBridge.onStatus('已通过 Pinia 启动 GreenVideo 解析引擎...');
                                                        return true;
                                                    }
                                                }
                                            } catch(e) {}

                                            // 策略 C：查找按钮并触发点击 (包含 GreenVideo 的 button.button-1)
                                            var submitBtn = document.querySelector('button.button-1, button.btn-red, button.btn-search, button[type="submit"], #btn-submit, #search-form button');

                                            if (!submitBtn) {
                                                var buttons = Array.from(document.querySelectorAll('button, input[type="submit"], a, div[role="button"]'));
                                                for (var j = 0; j < buttons.length; j++) {
                                                    var b = buttons[j];
                                                    var txt = (b.innerText || b.value || '').trim();
                                                    // 排除非触发型元素
                                                    if (txt.indexOf('客户端') !== -1 || txt.indexOf('App') !== -1 || txt.indexOf('VIP') !== -1 || txt.indexOf('播放器') !== -1) continue;
                                                    if (txt.indexOf('开始') !== -1 || txt.indexOf('提取') !== -1 || 
                                                        txt.indexOf('解析') !== -1 || txt.indexOf('Search') !== -1 || 
                                                        txt.indexOf('Download') !== -1 || txt.indexOf('Go') !== -1) {
                                                        submitBtn = b;
                                                        break;
                                                    }
                                                }
                                            }

                                            if (submitBtn) {
                                                console.log('[OmniSilent] Found submit button:', submitBtn);
                                                var onclickStr = submitBtn.getAttribute('onclick') || '';
                                                if (onclickStr && onclickStr.indexOf('ksearchvideo') !== -1) {
                                                    try {
                                                        if (typeof window.ksearchvideo === 'function') {
                                                            window.ksearchvideo();
                                                        } else {
                                                            eval(onclickStr);
                                                        }
                                                    } catch(e) {
                                                        submitBtn.click();
                                                    }
                                                } else {
                                                    // 派发全套鼠标事件以确保触发 Vue/React 的监听器
                                                    try {
                                                        submitBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
                                                        submitBtn.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
                                                        submitBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                                    } catch(e) {}
                                                    submitBtn.click();
                                                }
                                                if (window.OmniBridge) window.OmniBridge.onStatus('已提交解析请求，正在等待中转直链...');
                                                return true;
                                            } else if (inputEl) {
                                                // 模拟 Enter 键触发
                                                try {
                                                    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                                                    inputEl.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                                                } catch(e) {}
                                                var form = inputEl.closest('form');
                                                if (form) {
                                                    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                                                    return true;
                                                }
                                            }
                                            return false;
                                        }

                                        // 4. 持续尝试填入与提交 (最多等待 18 秒，每 350ms 检查一次)
                                        var hasSubmitted = false;
                                        var fillAttempts = 0;
                                        var fillTimer = setInterval(function() {
                                            fillAttempts++;
                                            if (window.__omniDone) {
                                                clearInterval(fillTimer);
                                                return;
                                            }

                                            var el = fillInput();
                                            if (el) {
                                                if (!hasSubmitted) {
                                                    hasSubmitted = true;
                                                    setTimeout(function() {
                                                        triggerSubmit(el);
                                                    }, 300);
                                                }
                                            }

                                            // 超过 18 秒停止
                                            if (fillAttempts > 50) {
                                                clearInterval(fillTimer);
                                            }
                                        }, 350);

                                        // 5. 深度扫描结果区域 (涵盖清晰度列表、转码进度、表格与直链按钮)
                                        function scanResults() {
                                            if (window.__omniDone) return;

                                            // 优先检查页面是否存在解析失败提示 (如 X2Twitter 的 #search-result .error 或 GreenVideo 的 videoSuggest)
                                            var errorEls = document.querySelectorAll('#search-result .error, .alert-warning, .alert-danger, .alert-error, .error-message, .error-box, #error, .n-alert--error-type');
                                            for (var eIdx = 0; eIdx < errorEls.length; eIdx++) {
                                                var eEl = errorEls[eIdx];
                                                if (eEl && eEl.offsetParent !== null) {
                                                    var eText = (eEl.innerText || '').trim();
                                                    if (eText.indexOf('未找到') !== -1 || eText.indexOf('私人的') !== -1 || eText.indexOf('被阻止') !== -1 ||
                                                        eText.indexOf('Invalid') !== -1 || eText.indexOf('failed') !== -1 || eText.indexOf('Error') !== -1 ||
                                                        eText.indexOf('不存在') !== -1 || eText.indexOf('失效') !== -1 || eText.indexOf('失败') !== -1) {
                                                        window.__omniDone = true;
                                                        if (window.OmniBridge) window.OmniBridge.onFailed(eText);
                                                        return;
                                                    }
                                                }
                                            }

                                            // 检查 GreenVideo 等站点的转码进度
                                            try {
                                                var nuxtRoot = document.querySelector('#__nuxt');
                                                var vueApp = nuxtRoot ? nuxtRoot.__vue_app__ : window.__nuxt_app__;
                                                var pinia = vueApp ? ((vueApp.config && vueApp.config.globalProperties && vueApp.config.globalProperties["\x24pinia"]) || (vueApp._context && vueApp._context.provides && vueApp._context.provides.pinia)) : null;
                                                if (pinia && pinia._s && pinia._s.get('video')) {
                                                    var vs = pinia._s.get('video');
                                                    if (vs.percentage && vs.percentage > 0 && vs.percentage < 100) {
                                                        if (window.OmniBridge) window.OmniBridge.onStatus('正在转码与合成视频直链: ' + vs.percentage + '%');
                                                    }
                                                }
                                            } catch(e) {}

                                            // 扫描所有链接 <a>
                                            var links = Array.from(document.querySelectorAll('a[href]'));
                                            for (var k = 0; k < links.length; k++) {
                                                var a = links[k];
                                                var href = a.getAttribute('href') || '';
                                                var txt = (a.innerText || '').trim();

                                                // 排除非媒体推广文件
                                                if (href.match(/\.(exe|apk|dmg|pkg|deb|rpm|zip|rar|7z|tar|gz|iso)(\?.*)?$/i)) continue;
                                                if (txt.indexOf('客户端') !== -1 || txt.indexOf('App') !== -1 || txt.indexOf('应用') !== -1 || txt.indexOf('软件') !== -1) continue;

                                                if (href.indexOf('http') === 0) {
                                                    // 视频/音频格式直链 或 严格视频CDN域名
                                                    if (href.match(/\.(mp4|m4a|m3u8|webm|flv|mp3)(\?.*)?$/i) ||
                                                        (href.indexOf('googlevideo.com') !== -1 || href.indexOf('twimg.com/video') !== -1 || href.indexOf('byteoversea.com') !== -1 || href.indexOf('snapany.com/api/download') !== -1 || href.indexOf('twcdn.net') !== -1 || href.indexOf('dl.snapcdn.app') !== -1 || href.indexOf('greenvideo.cc/api/video/download') !== -1)) {
                                                        window.__omniDone = true;
                                                        if (window.OmniBridge) window.OmniBridge.onResolved(href, document.title || '视频');
                                                        return;
                                                    }
                                                }
                                            }

                                            // 扫描结果区域内需要点击的清晰度/下载按钮 (GreenVideo 的清晰度按钮或 X2Twitter 的 convertFile)
                                            var resultContainer = document.querySelector('#data-result, #search-result, .download-list, .media-item, .result, .n-collapse-item__content, div[class*="video"]');
                                            var searchScope = resultContainer || document.body;
                                            var actionBtns = Array.from(searchScope.querySelectorAll('button, a, div[role="button"]'));

                                            for (var m = 0; m < actionBtns.length; m++) {
                                                var actBtn = actionBtns[m];
                                                var bText = (actBtn.innerText || actBtn.value || '').trim();

                                                // 排除非操作性按钮
                                                if (bText === '开始' || bText.indexOf('客户端') !== -1 || bText.indexOf('App') !== -1 || bText.indexOf('VIP') !== -1 || bText.indexOf('播放器') !== -1 || bText.indexOf('联系') !== -1) continue;

                                                // 匹配清晰度或下载按钮：如 "1080P", "720P", "480P", "超清", "高清", "原画", "下载", "Download", "MP4", "无水印"
                                                var isMatch = (
                                                    bText.indexOf('下载') !== -1 || bText.indexOf('Download') !== -1 ||
                                                    bText.indexOf('MP4') !== -1 || bText.indexOf('1080') !== -1 ||
                                                    bText.indexOf('720') !== -1 || bText.indexOf('480') !== -1 ||
                                                    bText.indexOf('360') !== -1 || bText.indexOf('超清') !== -1 ||
                                                    bText.indexOf('高清') !== -1 || bText.indexOf('原画') !== -1 ||
                                                    bText.indexOf('无水印') !== -1 || bText.indexOf('Get Link') !== -1
                                                );

                                                if (isMatch && !actBtn.__omniClicked && !actBtn.disabled) {
                                                    actBtn.__omniClicked = true;
                                                    console.log('[OmniSilent] Triggering result download/quality action button:', actBtn, bText);
                                                    if (window.OmniBridge) window.OmniBridge.onStatus('已捕获下载入口 (' + bText + ')，正在提取直链...');
                                                    try {
                                                        actBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
                                                        actBtn.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
                                                        actBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                                    } catch(e) {}
                                                    actBtn.click();
                                                    break;
                                                }
                                            }
                                        }

                                        var observer = new MutationObserver(function() {
                                            scanResults();
                                        });
                                        observer.observe(document.body, { childList: true, subtree: true, attributes: true });

                                        setInterval(function() {
                                            scanResults();
                                        }, 600);

                                    } catch(e) {
                                        console.error('[OmniSilent] Error during execution: ' + e);
                                    }
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(automationScript, null)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            Log.d(TAG, "shouldOverrideUrlLoading: $target")
                            if (target.endsWith(".exe", true) || target.endsWith(".apk", true) || target.endsWith(".dmg", true)) {
                                return true
                            }
                            if (UrlSniffer.isDirectMediaUrl(target)) {
                                if (!isCompleted) {
                                    isCompleted = true
                                    val (cleanUrl, cleanTitle) = UrlSniffer.unpackDirectMediaUrl(target, "")
                                    mainHandler.post {
                                        onSuccess(cleanUrl, cleanTitle)
                                    }
                                }
                                return true
                            }
                            return false
                        }
                    }

                    loadUrl(site.url)
                    webViewInstance = this
                }
            },
            update = { wv ->
                webViewInstance = wv
            }
        )
    }
}
