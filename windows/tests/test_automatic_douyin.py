import threading
from types import SimpleNamespace
import pytest
from engine import automatic_douyin as automatic

URL='https://www.douyin.com/video/7699370307513319141'
ID='7699370307513319141'
def result(width=1280,height=720,source='native'):
    return {'id':ID,'formats':[{'url':'https://example.com/'+source+'.mp4','width':width,'height':height}]}

@pytest.fixture(autouse=True)
def isolated(monkeypatch):
    automatic._cache.clear()
    monkeypatch.setattr(automatic,'sessions',SimpleNamespace(status=lambda site:{'generation':1},is_enabled=lambda site:True))
    monkeypatch.setattr(automatic,'scope',lambda:'test-scope')
    monkeypatch.setenv('OMNI_AUTOMATIC_BROWSER','on')

def test_low_native_is_enhanced_and_reference_gap_is_kept(monkeypatch):
    page=result(1920,1080,'page');page.update(referenceWidth=3840,referenceHeight=2160)
    monkeypatch.setattr(automatic,'capture',lambda *args:(page,'FOUND'))
    value=automatic.resolve(URL,lambda:result())
    assert len(value['formats'])==2
    assert value['referenceWidth']==3840
    assert value['quality_warning']

def test_verified_high_source_skips_browser(monkeypatch):
    monkeypatch.setattr(automatic,'capture',lambda *args:pytest.fail('unnecessary browser'))
    value=automatic.resolve(URL,lambda:result(3840,2160))
    assert not value['quality_warning']

def test_disabled_cookies_prevent_page_and_preserve_anonymous_source(monkeypatch):
    monkeypatch.setattr(automatic.sessions,'is_enabled',lambda site:False)
    monkeypatch.setattr(automatic,'capture',lambda *args:pytest.fail('disabled account used'))
    assert automatic.resolve(URL,lambda:result())['formats']

def test_challenge_preserves_real_source_but_never_claims_original(monkeypatch):
    monkeypatch.setattr(automatic,'capture',lambda *args:(None,'CHALLENGE_REQUIRED'))
    value=automatic.resolve(URL,lambda:result())
    assert value['formats'][0]['height']==720
    assert '验证' in value['quality_warning']
    assert not automatic._cache

def test_wrong_target_is_rejected(monkeypatch):
    page=result(1920,1080);page['id']='999'
    monkeypatch.setattr(automatic,'capture',lambda *args:(page,'FOUND'))
    with pytest.raises(ValueError,match='MEDIA_ID_MISMATCH'):automatic.resolve(URL,lambda:result())

def test_account_change_rejects_late_page(monkeypatch):
    def capture(*args):
        monkeypatch.setattr(automatic,'scope',lambda:'changed')
        return result(1920,1080),'FOUND'
    monkeypatch.setattr(automatic,'capture',capture)
    with pytest.raises(RuntimeError,match='SOURCE_EXPIRED'):automatic.resolve(URL,lambda:result())

def test_waiting_capture_can_be_cancelled():
    cancel=threading.Event();cancel.set()
    automatic._gate.acquire()
    try:
        with pytest.raises(RuntimeError,match='CANCELLED'):
            with automatic.capture_gate(cancel):pass
    finally:automatic._gate.release()
