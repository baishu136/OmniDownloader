import contextlib
import json
from pathlib import Path
import threading
import time

import pytest
from core.browser_login import BrowserLogin, cookie_text, edge_profile
from core.session_store import SessionStore


def rows():
    return [dict(domain='.youtube.com', path='/', name=name, value='synthetic-' + name,
                 secure=True, httpOnly=True, expires=time.time()+3600, session=False)
            for name in ('SID', 'SAPISID')]


def wait(predicate):
    deadline = time.monotonic()+6
    while not predicate():
        assert time.monotonic() < deadline, 'Login worker did not reach expected state'
        time.sleep(.02)


@pytest.fixture
def broker(tmp_path):
    class Browser:
        cookies = []
        opened = True
        captures = 0
        def is_open(self): return self.opened
        def reopen(self): self.opened = True
        def read_cookies(self, stop):
            self.opened = False; self.captures += 1
            return list(self.cookies)
    browser = Browser()
    @contextlib.contextmanager
    def factory(*args): yield browser
    manager = BrowserLogin(SessionStore(tmp_path), factory)
    yield manager, browser
    manager.shutdown()


def test_google_sso_alone_never_overwrites_existing_target_credentials(broker):
    manager, browser = broker
    initial = cookie_text('YouTube', rows())[0]
    manager.store.import_text('YouTube', initial)
    generation = manager.store.status('YouTube')['generation']
    browser.cookies = [dict(rows()[0], domain='.google.com')]
    manager.open('YouTube')
    wait(lambda: manager.status('YouTube')['phase']=='WAITING_LOGIN')
    with pytest.raises(ValueError, match='LOGIN_REQUIRED'): manager.save('YouTube')
    assert manager.store.status('YouTube')['generation'] == generation
    browser.cookies = rows()
    result = manager.save('YouTube')
    assert result['phase']=='CAPTURED' and not result['running']
    assert result['session']['cookieCount']==2
    assert 'synthetic' not in json.dumps(result)
    assert manager.save('YouTube')['alreadySaved'] is True
    reopened = SessionStore(manager.store.directory.parent).jar('YouTube')
    assert len(reopened)==2 and all(c.has_nonstandard_attr('HTTPOnly') for c in reopened)


def test_logout_blocks_late_capture_and_removes_only_managed_profile(broker, tmp_path):
    manager, browser = broker
    browser.cookies = rows()
    profile = manager.profile('YouTube')
    profile.mkdir(parents=True)
    (profile/'browser-state').write_text('synthetic')
    unrelated = tmp_path/'unrelated.txt'; unrelated.write_text('keep')
    manager.open('YouTube')
    wait(lambda: manager.status('YouTube')['phase']=='WAITING_LOGIN')
    manager.save('YouTube')
    assert manager.clear('YouTube')['state']=='MISSING'
    assert not profile.exists() and unrelated.read_text()=='keep'
    assert not manager.status('YouTube')['running']


def test_profile_escape_is_rejected_before_open_or_delete(tmp_path):
    manager = BrowserLogin(SessionStore(tmp_path/'state'))
    manager.root.mkdir()
    outside = tmp_path/'outside'; outside.mkdir()
    try: manager.profile('YouTube').symlink_to(outside, target_is_directory=True)
    except OSError: pytest.skip('Symlink permission unavailable')
    with pytest.raises(RuntimeError, match='UNSAFE_PROFILE'): manager.clear('YouTube')
    assert outside.exists()


def test_partitioned_and_foreign_cookies_are_not_misrepresented_as_http_cookies():
    values=rows()+[dict(rows()[0], domain='.google.com'), dict(rows()[0], name='partitioned', partitionKey={'topLevelSite':'https://example.com'})]
    text, names, skipped = cookie_text('YouTube', values)
    assert names=={'SID','SAPISID'} and skipped==1
    assert 'google.com' not in text and '#HttpOnly_.youtube.com' in text


def test_other_window_import_cannot_be_reported_as_this_browser_save(broker):
    manager,browser=broker; browser.cookies=rows()
    manager.open('YouTube')
    wait(lambda: manager.status('YouTube')['phase']=='WAITING_LOGIN')
    replacement=[dict(c,value='other-window') for c in rows()]
    manager.store.import_text('YouTube',cookie_text('YouTube',replacement)[0])
    with pytest.raises((ValueError,RuntimeError),match='SOURCE_EXPIRED'): manager.save('YouTube')
    assert all(c.value=='other-window' for c in manager.store.jar('YouTube'))


def test_manual_close_keeps_capture_available_and_save_remains_idempotent(broker):
    manager,browser=broker; browser.cookies=rows()
    manager.open('YouTube')
    wait(lambda: manager.status('YouTube')['phase']=='WAITING_LOGIN')
    browser.opened = False
    wait(lambda: manager.status('YouTube')['phase']=='AWAITING_CAPTURE')
    assert browser.captures == 0
    assert manager.save('YouTube')['phase']=='CAPTURED'
    assert manager.save('YouTube')['alreadySaved'] is True
    assert len(manager.store.jar('YouTube'))==2


def test_invalid_cookie_import_keeps_previous_credential(tmp_path):
    store=SessionStore(tmp_path)
    store.import_text('YouTube',cookie_text('YouTube',rows())[0])
    generation=store.status('YouTube')['generation']
    with pytest.raises(ValueError):
        store.import_text('YouTube','# Netscape HTTP Cookie File\n.youtube.com\tFALSE\t/\tTRUE\t0\tSID\tbad\n')
    assert store.status('YouTube')['generation']==generation


def test_http_only_refresh_writeback_and_logout_generation(tmp_path):
    store=SessionStore(tmp_path)
    store.import_text('YouTube', cookie_text('YouTube',rows())[0])
    with store.cookie_file('YouTube') as path:
        Path(path).write_text(Path(path).read_text().replace('synthetic-SID','refreshed-SID'))
    assert next(c for c in store.jar('YouTube') if c.name=='SID').value=='refreshed-SID'
    assert all(c.has_nonstandard_attr('HTTPOnly') for c in store.jar('YouTube'))
    with store.cookie_file('YouTube') as path:
        store.clear('YouTube')
        Path(path).write_text(Path(path).read_text().replace('refreshed-SID','late-SID'))
    assert store.status('YouTube')['state']=='MISSING'
    assert not list(store.directory.glob('omni_cookie_*'))


def test_expired_and_corrupt_credentials_are_never_reported_imported(tmp_path, monkeypatch):
    store=SessionStore(tmp_path)
    store.import_text('YouTube', cookie_text('YouTube',rows())[0])
    now=time.time()
    monkeypatch.setattr(time,'time',lambda:now+7200)
    assert store.status('YouTube')['state']=='EXPIRED'
    assert not list(store.jar('YouTube'))
    store._path('YouTube').write_bytes(b'not-an-encrypted-session')
    assert store.status('YouTube')['state']=='CORRUPT'
    with store.cookie_file('YouTube') as path: assert path is None


def test_login_api_is_local_only_and_never_returns_cookie_values(broker, monkeypatch):
    from fastapi.testclient import TestClient
    import core.browser_login as login
    from omni_server import app
    manager,browser=broker; browser.cookies=rows()
    monkeypatch.setattr(login,'browser_login',manager)
    with TestClient(app) as client:
        endpoint='/api/sessions/YouTube/login'
        assert client.post(endpoint,headers={'Origin':'https://untrusted.invalid'}).status_code==403
        assert client.post(endpoint,headers={'Host':'evil.invalid','Origin':'http://evil.invalid'}).status_code==403
        assert client.post('/api/sessions/unknown/login').status_code==400
        assert client.post(endpoint).status_code==200
        assert client.post('/api/sessions/YouTube/capture').status_code==200
        response=client.get(endpoint)
        assert 'synthetic' not in response.text and 'SAPISID' not in response.text
        assert client.delete('/api/sessions/YouTube').json()['state']=='MISSING'


def test_real_edge_cdp_profile_restart_and_http_only_capture(tmp_path):
    """Native browser integration, synthetic cookies only; no platform login."""
    from engine.automatic_douyin import edge_executable
    if not edge_executable(): pytest.skip('Microsoft Edge is not installed')
    profile=tmp_path/'profile'
    with edge_profile(profile,visible=False) as browser:
        browser.call('Storage.setCookies',{'cookies':rows()+[dict(rows()[0],domain='.google.com')]})
        captured=browser.call('Storage.getCookies')['cookies']
        assert cookie_text('YouTube',captured)[1]=={'SID','SAPISID'}
    with edge_profile(profile,visible=False) as browser:
        captured=browser.call('Storage.getCookies')['cookies']
        assert any(c['domain']=='.google.com' for c in captured)
        text,names,_=cookie_text('YouTube',captured)
        assert names=={'SID','SAPISID'} and '#HttpOnly_' in text
        store=SessionStore(tmp_path/'vault'); store.import_text('YouTube',text)
        assert len(SessionStore(tmp_path/'vault').jar('YouTube'))==2


def test_login_does_not_read_any_credentials_until_explicit_save(broker):
    manager,browser=broker;browser.cookies=rows()
    manager.open('YouTube');wait(lambda:manager.status('YouTube')['phase']=='WAITING_LOGIN')
    time.sleep(.3)
    assert browser.captures==0 and manager.store.status('YouTube')['state']=='MISSING'
    assert manager.save('YouTube')['phase']=='CAPTURED'
    assert browser.captures==1


def test_interactive_command_never_enables_automation_or_disables_security(tmp_path):
    from core.browser_login import normal_edge_command
    command=normal_edge_command(tmp_path,'https://www.youtube.com/','http://127.0.0.1:7890')
    assert any(part.startswith('--user-data-dir=') for part in command)
    assert not any(any(flag in part for flag in ('remote-debugging','headless','enable-automation','disable-web-security','AutomationControlled','ignore-certificate')) for part in command)
    assert '--restore-last-session' in command


def test_cancel_during_reader_cannot_import_late_credentials(broker):
    manager,browser=broker;entered=threading.Event();release=threading.Event()
    def reader(stop):entered.set();release.wait(5);return rows()
    browser.read_cookies=reader
    manager.open('YouTube');wait(lambda:manager.status('YouTube')['phase']=='WAITING_LOGIN')
    job=manager.jobs['YouTube'];job.capture.set();assert entered.wait(5)
    manager.store.clear('YouTube');job.stop.set();release.set();job.thread.join(5)
    assert not job.thread.is_alive() and manager.store.status('YouTube')['state']=='MISSING'


@pytest.mark.parametrize('hidden_available',[True,False])
def test_real_edge_normal_login_has_no_webdriver_and_retains_session_cookies(tmp_path,monkeypatch,hidden_available):
    """Actual Edge and local fixture only, not a real Google account acceptance."""
    from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
    from queue import Queue,Empty
    from core.browser_login import normal_edge,profile_browser_pid
    from engine.automatic_douyin import edge_executable,CDP
    if not edge_executable():pytest.skip('Microsoft Edge is not installed')
    original=CDP.call
    def capability(self,method,params=None,**kwargs):
        if not hidden_available and method=='Target.createTarget' and (params or {}).get('hidden'):
            self.last_error={'code':-32000,'message':'Hidden target can be created only when remote debugging is enabled'}
            raise RuntimeError('[BROWSER_ASSIST_REQUIRED] unsupported display capability')
        return original(self,method,params,**kwargs)
    monkeypatch.setattr(CDP,'call',capability)
    received=Queue()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            if not self.path.startswith('/fixture'):self.send_error(404);return
            body=b'''<!doctype html><title>OmniDownloader local login fixture</title><script>
            const prior=localStorage.getItem('omni-fixture');localStorage.setItem('omni-fixture','saved');
            fetch('/probe',{method:'POST',body:JSON.stringify({webdriver:navigator.webdriver,prior:prior})});
            </script>Local synthetic session fixture.'''
            self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(body)))
            self.send_header('Set-Cookie','omni-session=synthetic; HttpOnly; Path=/');self.end_headers();self.wfile.write(body)
        def do_POST(self):
            received.put(json.loads(self.rfile.read(int(self.headers['Content-Length']))));self.send_response(204);self.end_headers()
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    url=f'http://127.0.0.1:{server.server_port}/fixture'
    try:
        with edge_profile(tmp_path/'old-debug',url=url,visible=False):
            assert received.get(timeout=20)['webdriver'] is True
        with normal_edge(tmp_path/'normal',url) as browser:
            report=received.get(timeout=20)
            assert report['webdriver'] is False
            assert not (browser.profile/'DevToolsActivePort').exists()
            assert profile_browser_pid(browser.profile)==browser.process.pid
            cookies=browser.read_cookies(threading.Event())
            assert any(c['name']=='omni-session' and c['httpOnly'] and c['session'] for c in cookies)
            with pytest.raises(Empty):received.get(timeout=.5)  # reader must not restore/navigate old pages
            browser.reopen();assert received.get(timeout=20)['prior']=='saved'
        assert not profile_browser_pid(tmp_path/'normal')
    finally:server.shutdown();server.server_close();thread.join(3)


def test_pinterest_is_excluded_from_cookie_and_browser_login_services():
    from fastapi.testclient import TestClient
    from core.browser_login import AUTH_NAMES, LOGIN_URLS
    from core.session_store import DOMAINS
    from omni_server import app
    assert 'Pinterest' not in DOMAINS
    assert 'Pinterest' not in LOGIN_URLS
    assert 'Pinterest' not in AUTH_NAMES
    with TestClient(app) as client:
        platforms = [row['platform'] for row in client.get('/api/sessions').json()]
        assert 'Pinterest' not in platforms
        assert client.post('/api/sessions/Pinterest/login').status_code == 400


def test_bilibili_browser_login_opens_passport_and_collects_process_tree():
    import os
    from core.browser_login import LOGIN_URLS, _owned_process_tree_pids, normal_edge_command
    from engine.automatic_douyin import edge_executable
    assert LOGIN_URLS['哔哩哔哩'] == 'https://passport.bilibili.com/login'
    assert os.getpid() in _owned_process_tree_pids(os.getpid())
    if edge_executable():
        cmd = normal_edge_command('tmp_profile', LOGIN_URLS['哔哩哔哩'])
        assert '--edge-skip-compat-layer-relaunch' in cmd


def test_open_reports_immediate_launch_failure_without_hiding_error(tmp_path):
    @contextlib.contextmanager
    def failing_browser(*args):
        raise RuntimeError('[PROFILE_BUSY] 独立浏览器未启动，请关闭旧登录窗口后重试')
        yield
    manager = BrowserLogin(SessionStore(tmp_path), failing_browser)
    try:
        result = manager.open('哔哩哔哩')
        assert result['phase'] == 'ERROR'
        assert result['running'] is False
        assert result['errorCode'] == 'PROFILE_BUSY'
        assert '[PROFILE_BUSY]' in result['errorMessage']
    finally:
        manager.shutdown()


def test_cookie_input_accepts_header_prefix_bare_value_and_rejects_bad_input(tmp_path):
    store = SessionStore(tmp_path)
    st1 = store.import_cookie_input('哔哩哔哩', 'Cookie: SESSDATA=abc123; bili_jct=xyz')
    assert st1['state'] == 'IMPORTED' and st1['cookieCount'] == 2
    st2 = store.import_cookie_input('哔哩哔哩', 'bare_sessdata_token')
    assert st2['state'] == 'IMPORTED' and st2['cookieCount'] == 1
    with pytest.raises(ValueError):
        store.import_cookie_input('哔哩哔哩', 'bad token with spaces')



