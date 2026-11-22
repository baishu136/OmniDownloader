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
import io
import uuid


@contextlib.contextmanager
def file_lock(path, timeout=5):
    """Serialize desktop/web processes using the same Windows user state."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as stream:
        if not stream.tell(): stream.write(b'0'); stream.flush()
        deadline = time.monotonic() + timeout
        while True:
            try:
                stream.seek(0)
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline: raise RuntimeError('[SESSION_BUSY] 会话正在由另一窗口使用') from None
                time.sleep(.05)
        try: yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)

DOMAINS = {'YouTube': ('youtube.com',), 'X (Twitter)': ('x.com', 'twitter.com'),
           '小红书': ('xiaohongshu.com',), '小黑盒': ('xiaoheihe.cn', 'heybox.cn'),
           'Pixiv': ('pixiv.net',), '抖音': ('douyin.com',), '哔哩哔哩': ('bilibili.com',),
           'Pinterest': ('pinterest.com',), 'Instagram': ('instagram.com',), 'Facebook': ('facebook.com',),
           'TikTok': ('tiktok.com',), '快手': ('kuaishou.com',), '微博': ('weibo.com', 'weibo.cn')}


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
        self._local = threading.local()

    @contextlib.contextmanager
    def transaction(self):
        with self.lock:
            if getattr(self._local, 'depth', 0):
                yield
                return
            with file_lock(self.directory / '.session.lock'):
                self._local.depth = 1
                try: yield
                finally: self._local.depth = 0

    def _write(self, path, data):
        temp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
        try:
            with temp.open('wb') as stream:
                stream.write(data); stream.flush(); os.fsync(stream.fileno())
            temp.replace(path)
        finally: temp.unlink(missing_ok=True)

    def _content(self, platform):
        return protect(self._path(platform).read_bytes(), decrypt=True).decode('utf-8')

    def _jar(self, content):
        jar = http.cookiejar.MozillaCookieJar()
        jar._really_load(io.StringIO(content), '<protected session>', True, True)
        for cookie in list(jar):
            if cookie.expires == 0: cookie.expires = None; cookie.discard = True
            elif cookie.is_expired(): jar.clear(cookie.domain, cookie.path, cookie.name)
        return jar

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
        with self.transaction():
            old = self._policy(platform)
            if old['enabled'] != enabled:
                path = self._path(platform).with_suffix('.policy.json')
                self._write(path, json.dumps({'enabled': enabled, 'revision': max(old['revision'], time.time_ns()) + 1}).encode())
                self._path(platform).with_suffix('.validation.bin').unlink(missing_ok=True)
        return self.status(platform)

    def _normalize(self, platform, text, allow_empty=False):
        self._path(platform)
        if len(text.encode('utf-8')) > 1024 * 1024 or not text.startswith(('# Netscape HTTP Cookie File', '# HTTP Cookie File')):
            raise ValueError('需要不超过1MiB的Netscape Cookie文件')
        rows = []
        now = time.time()
        for line in text.splitlines():
            http_only = line.startswith('#HttpOnly_')
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
            if int(parts[4]) < 0: raise ValueError('Cookie过期时间无效')
            if int(parts[4]) and int(parts[4]) <= now:
                continue
            if parts[1] not in ('TRUE', 'FALSE') or parts[3] not in ('TRUE', 'FALSE') or not parts[2].startswith('/') or not parts[5]:
                raise ValueError('Cookie属性无效')
            if (parts[1] == 'TRUE') != parts[0].startswith('.') or any('\0' in part for part in parts):
                raise ValueError('Cookie域属性无效')
            rows.append(('#HttpOnly_' if http_only else '') + '\t'.join(parts))
        if not rows and not allow_empty:
            raise ValueError('没有该平台的有效Cookie')
        return '# Netscape HTTP Cookie File\n' + '\n'.join(rows) + '\n'

    def import_text(self, platform, text, expected_generation=None):
        content = self._normalize(platform, text)
        with self.transaction():
            if expected_generation is not None and self.status(platform)['generation'] != expected_generation:
                raise ValueError('[SOURCE_EXPIRED] 会话已变化，请重新打开登录窗口')
            dest = self._path(platform)
            self._write(dest, protect(content.encode()))
            dest.with_suffix('.validation.bin').unlink(missing_ok=True)
        return self.status(platform)

    def status(self, platform):
        with self.transaction():
            path = self._path(platform)
            policy = self._policy(platform)
            state, count = 'MISSING', 0
            if path.exists():
                try:
                    count = len(self._jar(self._content(platform)))
                    state = 'IMPORTED' if count else 'EXPIRED'
                except (OSError, ValueError, UnicodeError, AssertionError, http.cookiejar.LoadError): state = 'CORRUPT'
            return {'platform': platform, 'state': state, 'cookieCount': count,
                    'enabled': policy['enabled'],
                    'generation': f'{path.stat().st_mtime_ns if path.exists() else 0}:{policy["revision"]}'}

    def record_validation(self, platform, account):
        with self.transaction():
            data = dict(account, generation=self.status(platform)['generation'])
            self._write(self._path(platform).with_suffix('.validation.bin'), protect(json.dumps(data).encode()))

    def clear(self, platform):
        with self.transaction():
            old = self._policy(platform)
            self._write(self._path(platform).with_suffix('.policy.json'), json.dumps(dict(enabled=old['enabled'], revision=time.time_ns())).encode())
            self._path(platform).unlink(missing_ok=True)
            self._path(platform).with_suffix(".validation.bin").unlink(missing_ok=True)

    @contextlib.contextmanager
    def cookie_file(self, platform, writeback=True):
        with self.transaction():
            state = self.status(platform) if platform in DOMAINS else None
            data = self._content(platform).encode() if state and state['enabled'] and state['state'] == 'IMPORTED' else None
        if data is None:
            yield None
            return
        fd, name = tempfile.mkstemp(prefix='omni_cookie_', suffix='.txt', dir=self.directory)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(data)
            yield name
            if writeback:
                updated = Path(name).read_bytes()
                if updated != data:
                    content = self._normalize(platform, updated.decode('utf-8'), allow_empty=True)
                    with self.transaction():
                        if self.status(platform)['generation'] == state['generation']:
                            self._write(self._path(platform), protect(content.encode()))
                            self._path(platform).with_suffix('.validation.bin').unlink(missing_ok=True)
        finally:
            Path(name).unlink(missing_ok=True)

    def jar(self, platform):
        with self.cookie_file(platform, writeback=False) as file:
            return self._jar(Path(file).read_text('utf-8')) if file else http.cookiejar.MozillaCookieJar()


sessions = SessionStore()
