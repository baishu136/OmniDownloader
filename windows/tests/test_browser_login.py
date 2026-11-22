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
        def call(self, method):
            assert method == 'Storage.getCookies'
            return dict(cookies=list(self.cookies))
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
    wait(lambda: manager.status('YouTube')['phase']=='CAPTURED')
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
    wait(lambda: manager.status('YouTube')['phase']=='CAPTURED')
    replacement=[dict(c,value='other-window') for c in rows()]
    manager.store.import_text('YouTube',cookie_text('YouTube',replacement)[0])
    with pytest.raises((ValueError,RuntimeError),match='SOURCE_EXPIRED'): manager.save('YouTube')
    assert all(c.value=='other-window' for c in manager.store.jar('YouTube'))


def test_user_closes_browser_after_auto_capture_and_save_remains_idempotent(broker):
    manager,browser=broker; browser.cookies=rows()
    manager.open('YouTube')
    wait(lambda: manager.status('YouTube')['phase']=='CAPTURED')
    # A closed native window makes the next browser RPC unavailable.
    browser.call=lambda method: (_ for _ in ()).throw(ConnectionError('closed'))
    wait(lambda: not manager.status('YouTube')['running'])
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
