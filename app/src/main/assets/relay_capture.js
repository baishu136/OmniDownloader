(function () {
    'use strict';
    if (window.__omniCapture) return;
    var targetUrl = __OMNI_TARGET_URL__, automatic = __OMNI_AUTOSUBMIT__;
    var bridge = window.OmniBridge, done = false, submitted = false, filled = null;
    var started = Date.now(), clicked = new WeakSet(), inspected = 0;
    window.__omniCapture = true;
    function arm() { if (!submitted) { submitted = true; bridge.onSubmitted(); } }
    function status(text) { if (bridge.onStatus) bridge.onStatus(text); }
    function report(value, title, mime) {
        if (done || typeof value !== 'string' || value.length > 65536) return false;
        try {
            var url = new URL(value, location.href);
            if (!/^https?:$/.test(url.protocol)) return false;
            done = !!bridge.onMedia(url.href, String(title || document.title || '').slice(0, 250), mime || '');
        } catch (_) {}
        return done;
    }
    function inspect(value, title, depth) {
        if (done || depth > 7 || ++inspected > 600) return;
        if (typeof value === 'string') { if (/^https?:\/\//i.test(value)) report(value, title); return; }
        if (!value || typeof value !== 'object') return;
        title = value.displayTitle || value.title || value.fileName || title;
        // Response data is evidence, never a reason to mark completion before native validation.
        Object.keys(value).slice(0, 150).forEach(function (key) { inspect(value[key], title, depth + 1); });
    }
    function inspectRoot(value, title) { inspected = 0; inspect(value, title, 0); }
    async function responseData(response) {
        try {
            var type = response.headers.get('content-type') || '';
            if (/^(video|audio)\//i.test(type) || /mpegurl/i.test(type)) { report(response.url, '', type); return; }
            if (!/json/i.test(type) || Number(response.headers.get('content-length')) > 2097152) return;
            var copy = response.clone(), reader = copy.body && copy.body.getReader();
            if (!reader) return;
            var decoder = new TextDecoder(), text = '', size = 0;
            try {
                while (true) {
                    var part = await reader.read(); if (part.done) break;
                    size += part.value.byteLength;
                    if (size > 2097152) { await reader.cancel(); return; }
                    text += decoder.decode(part.value, {stream: true});
                }
                text += decoder.decode(); inspectRoot(JSON.parse(text));
            } finally { reader.releaseLock(); }
        } catch (_) {}
    }
    if (window.fetch) {
        var originalFetch = window.fetch;
        window.fetch = function () {
            // Return the original response, preserving errors, body and receiver semantics.
            return originalFetch.apply(this, arguments).then(function (response) { responseData(response); return response; });
        };
    }
    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function () {
        this.addEventListener('load', function () {
            try {
                var type = this.getResponseHeader('content-type') || '';
                if (/^(video|audio)\//i.test(type) || /mpegurl/i.test(type)) report(this.responseURL, '', type);
                else if (this.responseType === 'json') inspectRoot(this.response);
                else if ((!this.responseType || this.responseType === 'text') && this.responseText.length <= 2097152 && /json/i.test(type)) inspectRoot(JSON.parse(this.responseText));
            } catch (_) {}
        });
        return originalOpen.apply(this, arguments);
    };
    document.addEventListener('submit', arm, true);
    document.addEventListener('click', function (event) {
        var el = event.target.closest && event.target.closest('button,input[type=submit],a[download]');
        if (!el) return;
        if (/解析|提取|开始|search|extract|download|submit/i.test(el.textContent || el.value || '') || el.hasAttribute('download')) arm();
        if (el.tagName === 'A') report(el.href, el.download || el.title);
    }, true);
    var originalAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () { report(this.href, this.download || this.title); return originalAnchorClick.apply(this, arguments); };
    var originalWindowOpen = window.open;
    window.open = function (url) { report(url); return originalWindowOpen.apply(this, arguments); };
    var originalPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () { report(this.currentSrc || this.src); return originalPlay.apply(this, arguments); };
    function store() {
        var root = document.querySelector('#__nuxt');
        var app = window.__nuxt_app__ || (root && root.__vue_app__);
        var pinia = app && ((app.config && app.config.globalProperties && app.config.globalProperties.$pinia) || (app._context && app._context.provides && app._context.provides.pinia) || app.$pinia);
        return pinia && pinia._s && pinia._s.get('video');
    }
    function visible(el) { return el && !el.disabled && el.getClientRects().length > 0; }
    function fillAndSubmit() {
        if (!targetUrl || done) return;
        var selectors = ['input[type=url]', 'input[name=url]', 'input[name=link]', '#s_input', 'input[name=q]', 'input[placeholder*=链接]', 'input[placeholder*=粘贴]', 'input[placeholder*=http]', 'input.n-input__input-el', 'input[type=text]', 'textarea'];
        var input = null;
        selectors.some(function (selector) { input = Array.from(document.querySelectorAll(selector)).find(visible); return !!input; });
        if (!input) return;
        if (filled !== input) {
            var proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto, 'value');
            if (setter && setter.set) setter.set.call(input, targetUrl); else input.value = targetUrl;
            input.dispatchEvent(new Event('input', {bubbles:true})); input.dispatchEvent(new Event('change', {bubbles:true})); filled = input;
        }
        if (!automatic || submitted) return;
        var vs = store(); arm(); status('已提交解析请求，正在等待视频地址…');
        if (vs && typeof vs.extractVideoUrl === 'function') {
            vs.inputUrl = targetUrl;
            Promise.resolve(vs.extractVideoUrl({url:targetUrl})).catch(function () { status('站点暂未返回结果，可使用窗口排查'); });
            return;
        }
        if (typeof window.ksearchvideo === 'function') { window.ksearchvideo(); return; }
        if (typeof window.searchVideo === 'function') { window.searchVideo(); return; }
        var form = input.closest('form');
        var buttons = Array.from((form || document).querySelectorAll('button,input[type=submit],[role=button]')).filter(visible);
        var button = buttons.find(function (el) { return el.type === 'submit' || /^(开始|解析|提取|search|extract|download|go|submit)(\s|$)/i.test((el.textContent || el.value || '').trim()); });
        if (button) button.click();
        else if (form && form.requestSubmit) form.requestSubmit();
        else input.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
    }
    var polling = false;
    function greenVideoResult(vs) {
        var info = vs && vs.videoExtractInfo;
        if (!info || !info.vid || !submitted) return;
        inspectRoot(info, info.displayTitle);
        if (done || !automatic || polling || typeof vs.doDownloadVideo !== 'function' || typeof vs.getDownloadVideoInfo !== 'function') return;
        var list = info.videoItemVoList || [];
        (info.videoInfoVoList || []).forEach(function (part) { list = list.concat(part.videoItemVoList || []); });
        var args = {host:info.host,vid:info.vid,quality:(list[0] && list[0].quality) || '1080P'};
        polling = true;
        var attempts = 0;
        function poll() {
            if (done || Date.now()-started > 24000 || ++attempts > 9) return;
            Promise.resolve(vs.doDownloadVideo(args)).then(function (result) {
                var data = result && result.data;
                if (data && data.status === 2 && data.progress === 100) return Promise.resolve(vs.getDownloadVideoInfo(args)).then(function (value) { inspectRoot(value, info.displayTitle); });
                if (data && [3,4,5].indexOf(data.status) < 0) setTimeout(poll,2500);
            }).catch(function () { status('站点响应暂不可用，可使用窗口排查'); });
        }
        poll();
    }
    function scan() {
        if (done) return;
        document.querySelectorAll('video,audio,source,a[href]').forEach(function (el) { report(el.currentSrc || el.src || el.href, el.download || el.title); });
        try { greenVideoResult(store()); } catch (_) {}
        if (automatic && submitted && !done) {
            var quality = Array.from(document.querySelectorAll('button,[role=button]')).filter(function (el) { return visible(el) && !clicked.has(el) && /1080p|720p|下载视频|download video/i.test(el.textContent || ''); });
            if (quality[0]) { clicked.add(quality[0]); quality[0].click(); }
        }
    }
    var scheduled = false;
    var observer = new MutationObserver(function () { if (!scheduled) { scheduled=true; setTimeout(function () { scheduled=false; scan(); },250); } });
    observer.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['href','src']});
    var ticks = 0, timer = setInterval(function () { fillAndSubmit(); scan(); if (done || ++ticks >= 60) clearInterval(timer); },400);
    window.__omniFillInput = function () { filled = null; fillAndSubmit(); };
    window.addEventListener('pagehide',function () { clearInterval(timer); observer.disconnect(); },{once:true});
    fillAndSubmit(); scan();
})();
