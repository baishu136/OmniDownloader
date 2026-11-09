"""Desktop-only, user-driven capture of the target page's playing video element."""
import threading
import json
import time
from urllib.parse import urlsplit, parse_qs


def identity(url):
    from engine.platforms import platform, normalize
    from engine.media_resolver import content_id
    site = platform(url)
    if site == '小黑盒': return site, (parse_qs(urlsplit(url).query).get('link_id') or [''])[0]
    if site in ('抖音', '快手'): return site, content_id(normalize(url), site)
    return site, ''


class BrowserAssist:
    def __init__(self):
        self.webview = None
        self.window = None
        self.target = ''
        self.lock = threading.RLock()

    def configure(self, webview): self.webview = webview

    def open(self, url):
        from engine.platforms import normalize, platform
        from engine.url_sniffer import UrlSniffer
        url = UrlSniffer.extract_url(url) or url.strip()
        url = normalize(url)
        site = platform(url)
        if site not in ('小黑盒', '抖音', '快手'): raise ValueError('浏览器辅助支持小黑盒、抖音和快手目标内容页')
        from core.session_store import sessions
        if not sessions.is_enabled(platform(url)): raise ValueError('[COOKIE_DISABLED] 浏览器辅助使用网页登录状态，请先开启该平台Cookie')
        if not self.webview: raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 请使用桌面独立版打开辅助浏览器')
        if not identity(url)[1]:
            from engine.platforms import http, page
            with http(site) as client: url = normalize(page(client, url).url)
        if platform(url) != site or not identity(url)[1]: raise ValueError('[MEDIA_ID_MISMATCH] 无法确认浏览器目标作品ID')
        with self.lock:
            if self.window:
                self.window.load_url(url)
                self.window.show()
            else:
                self.window = self.webview.create_window(f'{site} · 请打开目标作品', url, width=1000, height=760)
                self.window.events.closed += self._closed
                self.window.events.loaded += self._install_capture
            self.target = url
        return {'opened': True}

    def _install_capture(self):
        from pathlib import Path
        with self.lock:
            if not self.window or identity(self.target)[0] != '抖音': return
            if identity(self.window.get_current_url() or '') != identity(self.target): return
            script = (Path(__file__).resolve().parents[1] / 'web/douyin-images-capture.js').read_text('utf-8')
            self.window.evaluate_js('window.__omniCaptureId=' + json.dumps(identity(self.target)[1]) + ';\n' + script)

    def _closed(self):
        with self.lock: self.window = None

    def candidates(self):
        from engine.platforms import platform
        with self.lock:
            if not self.window: raise ValueError('请先打开目标页面并播放视频')
            from core.session_store import sessions
            if not sessions.is_enabled(platform(self.target)): raise ValueError('[COOKIE_DISABLED] 该平台Cookie已禁用')
            current = self.window.get_current_url()
            if identity(current or '') != identity(self.target): raise ValueError('[MEDIA_ID_MISMATCH] 当前不是目标作品，请返回原内容后播放')
            result = self.window.evaluate_js("""Array.from(document.querySelectorAll('video')).filter(v => v.played.length > 0).map(v => ({url:v.currentSrc,width:v.videoWidth,height:v.videoHeight,duration:v.duration,title:document.title}))""") or []
            return [dict(c, sourcePage=self.target) for c in result if urlsplit(c.get('url', '')).scheme in ('http', 'https')]

    def images(self):
        from core.session_store import sessions
        from engine.media_resolver import douyin_images_from_item
        from engine.images import ImageEngine
        with self.lock:
            if not self.window or identity(self.target)[0] != '抖音': raise ValueError('请先打开目标抖音图文')
            if not sessions.is_enabled('抖音'): raise ValueError('[COOKIE_DISABLED] 抖音Cookie已禁用')
            generation = sessions.status('抖音')['generation']
            target = identity(self.target)
            if identity(self.window.get_current_url() or '') != target: raise ValueError('[MEDIA_ID_MISMATCH] 请返回目标图文页')
            identifier = target[1]
            # Only digits enter the JavaScript source; returned data is validated again below.
            if not identifier.isdigit(): raise ValueError('[MEDIA_ID_MISMATCH] 抖音作品ID不合法')
            script = """(() => {
                window.__omniImageResult=null;
                const target=TARGET_ID;
                const find=(v,d=0)=>{if(!v||typeof v!=='object'||d>25)return null;
                  if(String(v.aweme_id||v.awemeId||'')===target&&(v.images?.length||v.image_post_info?.images?.length))return v;
                  for(const c of Object.values(v)){const i=find(c,d+1);if(i)return i;}return null;};
                const done=i=>{window.__omniImageResult=JSON.stringify(i?{aweme_id:target,desc:i.desc,author:{nickname:i.author?.nickname||''},images:i.images||i.image_post_info?.images}:{});};
                let state=window.__omniCapturedImages || window._ROUTER_DATA;try{if(!state)state=JSON.parse(decodeURIComponent(document.getElementById('RENDER_DATA')?.textContent||'{}'));}catch(e){}
                const item=find(state);if(item)done(item);else fetch('/aweme/v1/web/aweme/detail/?aid=6383&device_platform=webapp&aweme_id='+target,{credentials:'same-origin'}).then(r=>r.ok?r.json():null).then(d=>done(find(d))).catch(()=>done(null));
            })()""".replace('TARGET_ID', json.dumps(identifier))
            self.window.evaluate_js(script)
            result = None
            for _ in range(24):
                result = self.window.evaluate_js('window.__omniImageResult')
                if result: break
                time.sleep(.5)
            if not result or len(result) > 4 * 1024 * 1024: raise ValueError('[BROWSER_ASSIST_REQUIRED] 尚未读取到目标图片，请确认内容已加载')
            if identity(self.window.get_current_url() or '') != target: raise ValueError('[MEDIA_ID_MISMATCH] 页面已离开目标作品')
            if sessions.status('抖音')['generation'] != generation: raise ValueError('[SOURCE_EXPIRED] 会话策略已变化，请重新提取')
            values = douyin_images_from_item(json.loads(result), self.target, identifier)
            canonical, identifier, title, author, resources = values
            resources = [r for r in resources if r]
            if not resources or len(resources) > 200: raise ValueError('[NO_MEDIA] 未取得有效图文列表')
            post = dict(platform='抖音', url=canonical, contentId=identifier, title=title[:240], author=author,
                        resources=resources, route='first_party_browser', resolvedAt=int(time.time()*1000))
            ImageEngine.remember(post)
            return post


browser_assist = BrowserAssist()
