"""Bounded first-party enhancement in a separate, noninteractive Edge profile."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit

from core.config import config
from core.session_store import sessions
from engine.media_errors import code

_gate = threading.Lock()
_cache = {}


@contextmanager
def capture_gate(cancel):
    deadline = time.monotonic() + 30
    while not _gate.acquire(timeout=.1):
        if cancel and cancel.is_set():
            raise RuntimeError('[CANCELLED] 提取已取消')
        if time.monotonic() >= deadline:
            raise RuntimeError('[NETWORK_ERROR] 自动提取繁忙，请稍后重试')
    try:
        yield
    finally:
        _gate.release()


def scope():
    return f'{sessions.status("抖音")["generation"]}|{sessions.is_enabled("抖音")}|{config.proxy_url}'


def needs_page(result):
    formats = result.get('formats') or []
    if not formats:
        return True
    best = max(formats, key=lambda f: (f.get('width') or 0) * (f.get('height') or 0))
    return (min(best.get('width') or 0, best.get('height') or 0) <= 720
            or (result.get('referenceWidth') or 0)*(result.get('referenceHeight') or 0) > (best.get('width') or 0)*(best.get('height') or 0)
            or any(not f.get('width') or not f.get('height') for f in formats))


def merge(native, page):
    if not page or not page.get('formats'):
        return native
    if not native:
        return page
    if str(native.get('id')) != str(page.get('id')):
        raise ValueError('[MEDIA_ID_MISMATCH] 页面没有返回同一作品')
    result = dict(native)
    streams = {f['url']: f for f in native['formats']}
    streams.update({f['url']: f for f in page['formats']})
    result['formats'] = list(streams.values())
    if (page.get('referenceWidth') or 0)*(page.get('referenceHeight') or 0) > (native.get('referenceWidth') or 0)*(native.get('referenceHeight') or 0):
        result['referenceWidth'], result['referenceHeight'] = page['referenceWidth'], page['referenceHeight']
    return result


def edge_executable():
    for key in ('PROGRAMFILES(X86)', 'PROGRAMFILES', 'LOCALAPPDATA'):
        root = os.environ.get(key)
        if root:
            path = Path(root) / 'Microsoft/Edge/Application/msedge.exe'
            if path.is_file():
                return path
    return None


class CDP:
    def __init__(self, socket):
        self.socket, self.counter, self.pending = socket, 0, {}
        self.event_handler = None

    def call(self, method, params=None, timeout=3):
        self.counter += 1
        identifier = self.counter
        self.socket.send(json.dumps({'id': identifier, 'method': method, 'params': params or {}}))
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if identifier in self.pending:
                message = self.pending.pop(identifier)
            else:
                try:
                    message = json.loads(self.socket.recv(timeout=max(.05, deadline-time.monotonic())))
                except TimeoutError:
                    continue
            if message.get('id') != identifier:
                if 'id' in message:
                    self.pending[message['id']] = message
                elif self.event_handler:
                    self.event_handler(message, self)
                continue
            if 'error' in message:
                raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 浏览器暂不能执行目标提取')
            return message.get('result') or {}
        raise TimeoutError('Target browser operation timed out')


@contextmanager
def browser(proxy='', visible=False):
    from websockets.sync.client import connect
    import requests
    executable = edge_executable()
    if not executable:
        raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 自动页面补全需要本机 Microsoft Edge')
    profile = Path(tempfile.mkdtemp(prefix='omni-douyin-'))
    command = [str(executable), '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
               f'--user-data-dir={profile}', '--no-first-run', '--no-default-browser-check',
               '--disable-background-networking', '--disable-sync', '--mute-audio',
               '--autoplay-policy=user-gesture-required', '--window-size=1280,900']
    if not visible:
        command += ['--headless=new']
    if proxy:
        parts = urlsplit(proxy)
        if parts.scheme not in ('http', 'https', 'socks5', 'socks5h') or not parts.hostname or not parts.port or parts.username or parts.password:
            raise ValueError('[NETWORK_ERROR] 浏览器代理需要免认证 HTTP、HTTPS 或 SOCKS5 地址')
        command += ['--proxy-server='+proxy.replace('socks5h://', 'socks5://')]
    command += ['about:blank']
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    connection = None
    try:
        port_file = profile / 'DevToolsActivePort'
        deadline = time.monotonic()+6
        while not port_file.is_file() and time.monotonic()<deadline:
            if process.poll() is not None:
                raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 自动浏览器未能启动')
            time.sleep(.05)
        port = int(port_file.read_text('utf-8').splitlines()[0])
        client = requests.Session(); client.trust_env = False
        targets = client.get(f'http://127.0.0.1:{port}/json', timeout=2).json()
        target = next(t for t in targets if t.get('type') == 'page')
        address = target['webSocketDebuggerUrl']
        if urlsplit(address).hostname not in ('127.0.0.1', 'localhost'):
            raise RuntimeError('[NETWORK_ERROR] 浏览器调试通道不是本机地址')
        connection = connect(address, open_timeout=3, close_timeout=1, max_size=16*1024*1024, proxy=None)
        yield CDP(connection)
    finally:
        if connection:
            try:
                connection.send(json.dumps({'id': 999999, 'method': 'Browser.close'}))
            except Exception:
                pass
            connection.close()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
        try:
            shutil.rmtree(profile)
        except OSError:
            pass  # Edge can release its profile after the parent exits.


def capture(url, identity, original_scope, cancel=None, budget=15):
    from engine.platforms import http
    from engine.media_resolver import probe_original_mp4
    script = (Path(__file__).resolve().parents[1]/'web/static/target-capture.js').read_text('utf-8')
    with browser(config.proxy_url) as page:
        page.call('Page.enable'); page.call('Network.enable')
        rows=[]
        for cookie in sessions.jar('抖音'):
            row=dict(name=cookie.name, value=cookie.value, domain=cookie.domain, path=cookie.path or '/', secure=cookie.secure)
            if cookie.expires: row['expires']=cookie.expires
            rows.append(row)
        if rows: page.call('Network.setCookies', {'cookies': rows})
        options = json.dumps(dict(id=identity, site='抖音', quiet=True), ensure_ascii=False)
        page.call('Page.addScriptToEvaluateOnNewDocument', {'source': 'window.__omniTarget='+options+';\n'+script})
        page.call('Page.navigate', {'url': f'https://www.douyin.com/video/{identity}'})
        started=changed=time.monotonic(); best=None; fingerprint=''; status='TIMEOUT'
        while time.monotonic()-started<budget:
            if cancel and cancel.is_set(): raise RuntimeError('[CANCELLED] 提取已取消')
            if scope()!=original_scope: raise RuntimeError('[SOURCE_EXPIRED] 会话或网络已变化，请重新解析')
            response=page.call('Runtime.evaluate', {'expression': 'JSON.stringify(window.__omniReadTarget ? window.__omniReadTarget() : {})', 'returnByValue': True})
            raw=(response.get('result') or {}).get('value')
            if isinstance(raw,str) and len(raw)<=8*1024*1024:
                data=json.loads(raw)
                if data.get('status') in ('auth','challenge'):
                    status='AUTH_REQUIRED' if data['status']=='auth' else 'CHALLENGE_REQUIRED';break
                if data.get('contentId')==identity:
                    media=next((m for m in data.get('media',[]) if m.get('mediaId')==identity and m.get('formats')),None)
                    if media:
                        streams=[dict(f, format_id=f['url'], ext='mp4', vcodec=f.get('vcodec') or 'unknown', acodec='unknown') for f in media['formats']]
                        best=dict(id=identity,title=data.get('title') or identity,uploader=data.get('author') or '',thumbnail=data.get('thumbnailUrl') or '',
                                  formats=streams,duration=(media.get('durationMs') or 0)/1000,
                                  referenceWidth=media.get('referenceWidth') or 0,referenceHeight=media.get('referenceHeight') or 0)
                        next_fingerprint=hashlib.sha256(raw.encode()).hexdigest()
                        if next_fingerprint!=fingerprint: fingerprint=next_fingerprint;changed=time.monotonic()
                        top=max(streams,key=lambda f:f.get('width',0)*f.get('height',0))
                        minimum=2.5 if min(top.get('width',0),top.get('height',0))>=1080 else 7
                        gap=(media.get('referenceWidth') or 0)*(media.get('referenceHeight') or 0)>top.get('width',0)*top.get('height',0)
                        if not gap and time.monotonic()-started>=minimum and time.monotonic()-changed>=1.5: status='FOUND';break
            time.sleep(.4)
        if best:
            with http('抖音') as client:
                top=sorted(best['formats'],key=lambda f:f.get('width',0)*f.get('height',0),reverse=True)[:2]
                for candidate in top:
                    dimensions=probe_original_mp4(client,candidate['url'])
                    if dimensions: candidate.update(width=dimensions[0],height=dimensions[1],dimensions_verified=True)
            if status=='TIMEOUT':status='FOUND'
        return best,status


def resolve(url, native, cancel=None):
    from engine.media_resolver import content_id
    identity=content_id(url,'抖音')
    if not identity:return native()
    initial_scope=scope();key=(identity,initial_scope)
    with capture_gate(cancel):
        if cancel and cancel.is_set():raise RuntimeError('[CANCELLED] 提取已取消')
        if scope()!=initial_scope:raise RuntimeError('[SOURCE_EXPIRED] 会话或网络已变化，请重新解析')
        cached=_cache.get(key)
        if cached and time.monotonic()-cached[0]<cached[1]:return cached[2]
        result=None;error=None
        try:result=native()
        except Exception as exc:
            error=exc
            if code(str(exc)) in ('NO_MEDIA','MEDIA_ID_MISMATCH','CONTENT_UNAVAILABLE','RATE_LIMITED'):raise
        status='NOT_NEEDED'
        if needs_page(result or {}) and sessions.is_enabled('抖音') and os.environ.get('OMNI_AUTOMATIC_BROWSER', 'on') != 'off':
            try:
                richer,status=capture(url,identity,initial_scope,cancel)
                result=merge(result,richer)
            except Exception as exc:
                if code(str(exc)) in ('SOURCE_EXPIRED','CANCELLED','MEDIA_ID_MISMATCH'):raise
                status='UNAVAILABLE'
        if scope()!=initial_scope:raise RuntimeError('[SOURCE_EXPIRED] 会话或网络已变化，请重新解析')
        if not result:
            if status in ('AUTH_REQUIRED','CHALLENGE_REQUIRED'):raise RuntimeError(f'[{status}] 请完成平台登录或验证后重试')
            if error:raise error
            raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 自动页面未返回目标视频')
        result=dict(result);result['access_evidence']=['DOUYIN_PAGE_'+status]
        result['quality_warning']=''
        if status in ('AUTH_REQUIRED','CHALLENGE_REQUIRED'):result['quality_warning']='平台要求登录或验证；已保留当前可用源，更高规格尚未确认。'
        elif needs_page(result):result['quality_warning']='已保留当前可用源；更高画质尚未确认，本次结果不代表上传原片。'
        if status in ('FOUND','NOT_NEEDED'):
            if len(_cache)>=32:_cache.pop(next(iter(_cache)))
            _cache[key]=(time.monotonic(),45 if result['quality_warning'] else 300,result)
        return result
