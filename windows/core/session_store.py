"""Windows user-bound DPAPI cookie store. Public APIs expose metadata only."""
import contextlib
import ctypes
import http.cookiejar
import json
import os
from pathlib import Path
import tempfile
import threading
import time

DOMAINS = {'YouTube': ('youtube.com',), 'X (Twitter)': ('x.com', 'twitter.com'),
           '小红书': ('xiaohongshu.com',), '小黑盒': ('xiaoheihe.cn', 'heybox.cn'),
           'Pixiv': ('pixiv.net',), '抖音': ('douyin.com',), '哔哩哔哩': ('bilibili.com',),
           'Pinterest': ('pinterest.com',), 'Instagram': ('instagram.com',), 'Facebook': ('facebook.com',),
           'TikTok': ('tiktok.com',), '快手': ('kuaishou.com',)}


class Blob(ctypes.Structure):
    _fields_ = [('length', ctypes.c_ulong), ('data', ctypes.POINTER(ctypes.c_ubyte))]


def protect(data, decrypt=False):
    if os.name != 'nt':
        raise RuntimeError('[ENGINE_UNAVAILABLE] 凭据存储需要Windows用户数据保护')
    buffer = ctypes.create_string_buffer(data)
    incoming = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    outgoing = Blob()
    fn = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not fn(ctypes.byref(incoming), None, None, None, None, 1, ctypes.byref(outgoing)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(outgoing.data, outgoing.length)
    finally:
        ctypes.windll.kernel32.LocalFree(outgoing.data)


class SessionStore:
    def __init__(self, directory=None):
        self.directory = Path(directory or os.environ.get('OMNI_STATE_DIR') or Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'OmniDownloader') / 'sessions'
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()

    def _path(self, platform):
        if platform not in DOMAINS:
            raise ValueError('不支持的会话平台')
        return self.directory / f'{list(DOMAINS).index(platform)}.bin'

    def _policy(self, platform):
        path = self._path(platform).with_suffix('.policy.json')
        if not path.exists(): return {'enabled': True, 'revision': 0}
        try:
            value = json.loads(path.read_text('utf-8'))
            if type(value['enabled']) is not bool or type(value['revision']) is not int: raise ValueError()
            return value
        except (OSError, ValueError, KeyError, TypeError): return {'enabled': False, 'revision': -1}

    def is_enabled(self, platform):
        with self.lock: return self._policy(platform)['enabled']

    def set_enabled(self, platform, enabled):
        if type(enabled) is not bool: raise ValueError('Cookie开关必须是布尔值')
        with self.lock:
            old = self._policy(platform)
            if old['enabled'] != enabled:
                path = self._path(platform).with_suffix('.policy.json')
                temporary = path.with_suffix('.tmp')
                temporary.write_text(json.dumps({'enabled': enabled, 'revision': max(old['revision'], time.time_ns()) + 1}), encoding='utf-8')
                temporary.replace(path)
                self._path(platform).with_suffix('.validation.bin').unlink(missing_ok=True)
        return self.status(platform)

    def import_text(self, platform, text):
        if len(text.encode('utf-8')) > 1024 * 1024 or not text.startswith(('# Netscape HTTP Cookie File', '# HTTP Cookie File')):
            raise ValueError('需要不超过1MiB的Netscape Cookie文件')
        rows = []
        now = time.time()
        for line in text.splitlines():
            if line.startswith('#HttpOnly_'):
                line = line[len('#HttpOnly_'):]
            elif line.startswith('#') or not line.strip():
                continue
            parts = line.split('\t')
            if len(parts) != 7:
                raise ValueError('Cookie行格式无效')
            domain = parts[0].lstrip('.').lower()
            if not any(domain == d or domain.endswith('.' + d) for d in DOMAINS[platform]):
                continue
            if int(parts[4]) and int(parts[4]) <= now:
                continue
            rows.append('\t'.join(parts))
        if not rows:
            raise ValueError('没有该平台的有效Cookie')
        content = '# Netscape HTTP Cookie File\n' + '\n'.join(rows) + '\n'
        with self.lock:
            dest = self._path(platform)
            temp = dest.with_suffix('.tmp')
            temp.write_bytes(protect(content.encode()))
            temp.replace(dest)
        return self.status(platform)

    def status(self, platform):
        with self.lock:
            path = self._path(platform)
            policy = self._policy(platform)
            return {'platform': platform, 'state': 'IMPORTED' if path.exists() else 'MISSING',
                    'enabled': policy['enabled'],
                    'generation': f'{path.stat().st_mtime_ns if path.exists() else 0}:{policy["revision"]}'}

    def record_validation(self, platform, account):
        with self.lock:
            data = dict(account, generation=self.status(platform)['generation'])
            self._path(platform).with_suffix('.validation.bin').write_bytes(protect(json.dumps(data).encode()))

    def clear(self, platform):
        with self.lock:
            self._path(platform).unlink(missing_ok=True)
            self._path(platform).with_suffix(".validation.bin").unlink(missing_ok=True)

    @contextlib.contextmanager
    def cookie_file(self, platform):
        if platform not in DOMAINS or not self.is_enabled(platform) or not self._path(platform).exists():
            yield None
            return
        with self.lock:
            data = protect(self._path(platform).read_bytes(), decrypt=True)
        fd, name = tempfile.mkstemp(prefix='omni_cookie_', suffix='.txt', dir=self.directory)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(data)
            yield name
        finally:
            Path(name).unlink(missing_ok=True)

    def jar(self, platform):
        jar = http.cookiejar.MozillaCookieJar()
        with self.cookie_file(platform) as file:
            if file:
                jar.filename = file
                jar.load(ignore_discard=True, ignore_expires=True)
                for cookie in list(jar):
                    if cookie.expires == 0:
                        cookie.expires = None
                        cookie.discard = True
                    elif cookie.is_expired():
                        jar.clear(cookie.domain, cookie.path, cookie.name)
        return jar


sessions = SessionStore()
