from pathlib import Path
import pytest


class Registry:
    HKEY_CURRENT_USER=1;KEY_SET_VALUE=2;KEY_READ=4;REG_SZ=1
    def __init__(self): self.values={'OtherApplication':'keep'}
    def CreateKeyEx(self,*args): return self
    def OpenKey(self,*args): return self
    def __enter__(self): return self
    def __exit__(self,*args): pass
    def CloseKey(self,*args): pass
    def QueryValueEx(self,key,name):
        if name not in self.values:raise FileNotFoundError(name)
        return self.values[name],self.REG_SZ
    def SetValueEx(self,key,name,reserved,kind,value):self.values[name]=value
    def DeleteValue(self,key,name):
        if name not in self.values:raise FileNotFoundError(name)
        del self.values[name]


def test_autostart_migrates_owned_shortcuts_to_one_entry_without_touching_others(tmp_path,monkeypatch):
    import setup_shortcuts as startup
    registry=Registry();monkeypatch.setattr(startup,'winreg',registry)
    monkeypatch.setattr(startup,'get_startup_folder',lambda:tmp_path)
    exe=tmp_path/'OmniDownloader_Web.exe';exe.write_bytes(b'synthetic fixture; never executed')
    monkeypatch.setattr(startup,'get_target_path',lambda:str(exe))
    for name in ['OmniDownloader 网页版.lnk','OmniDownloaderWeb.lnk','Unrelated.lnk']:(tmp_path/name).write_bytes(b'fixture')
    assert startup.is_autostart_enabled()
    startup.enable_autostart(True);startup.enable_autostart(True)
    assert registry.values=={'OtherApplication':'keep','OmniDownloader_Web':f'"{exe}" --autostart'}
    assert (tmp_path/'Unrelated.lnk').exists()
    assert not (tmp_path/'OmniDownloader 网页版.lnk').exists()
    startup.enable_autostart(False);startup.enable_autostart(False)
    assert not startup.is_autostart_enabled() and registry.values=={'OtherApplication':'keep'}


def test_missing_startup_target_or_permission_denial_never_reports_success(tmp_path,monkeypatch):
    import setup_shortcuts as startup
    registry=Registry();monkeypatch.setattr(startup,'winreg',registry)
    monkeypatch.setattr(startup,'get_startup_folder',lambda:tmp_path)
    target=tmp_path/'missing.exe';monkeypatch.setattr(startup,'get_target_path',lambda:str(target))
    with pytest.raises(RuntimeError,match='STARTUP_TARGET_MISSING'):startup.enable_autostart(True)
    target.write_bytes(b'fixture')
    def denied(*args):raise PermissionError('synthetic denial')
    monkeypatch.setattr(registry,'SetValueEx',denied)
    with pytest.raises(PermissionError):startup.enable_autostart(True)
    assert registry.values=={'OtherApplication':'keep'}


def test_startup_api_exposes_failure_instead_of_fake_saved_state(monkeypatch):
    from fastapi.testclient import TestClient
    import setup_shortcuts as startup
    from omni_server import app
    def denied(*args):raise PermissionError('synthetic private details')
    monkeypatch.setattr(startup,'enable_autostart',denied)
    with TestClient(app) as client:
        response=client.post('/api/settings',json={'autoStart':True})
        assert response.status_code==400
        assert 'STARTUP_FAILED' in response.text and 'synthetic private' not in response.text


def test_simplified_web_does_not_boot_admin_or_update_controllers():
    """Contract: old settings hashes cannot bring admin UI back into the companion."""
    from fastapi.testclient import TestClient
    from omni_server import app
    with TestClient(app) as client:
        page=client.get('/').text
        assert 'data-client="web-lite"' in page and 'web-lite.js' in page
        assert 'id="page-settings"' not in page and 'id="platform-list"' not in page
        code=client.get('/static/js/web-lite.js').text
        assert 'initSettings' not in code and 'checkApplicationUpdate' not in code
        assert "['home', 'batch', 'tasks']" in code


def test_download_options_belong_to_home_instead_of_a_modal():
    """Navigating away must hide the options with the home page, without an overlay."""
    from html.parser import HTMLParser
    from fastapi.testclient import TestClient
    from omni_server import app

    class PageStructure(HTMLParser):
        def __init__(self):
            super().__init__()
            self.stack = []
            self.panels = []
            self.ids = []

        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            if attrs.get('id'):
                self.ids.append(attrs['id'])
            if attrs.get('id') == 'format-sheet-overlay':
                self.panels.append((attrs, [item[1] for item in self.stack]))
            if tag not in {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr'}:
                self.stack.append((tag, attrs.get('id')))

        def handle_endtag(self, tag):
            assert self.stack and self.stack[-1][0] == tag, f'Unbalanced HTML: {tag}'
            self.stack.pop()

    with TestClient(app) as client:
        parser = PageStructure()
        parser.feed(client.get('/').text)
        assert not parser.stack
        assert len(parser.ids) == len(set(parser.ids))
        assert len(parser.panels) == 1
        attrs, ancestors = parser.panels[0]
        assert 'page-home' in ancestors
        assert attrs['data-inline'] == 'true' and 'sheet-overlay' not in attrs.get('class', '')
        assert 'aria-modal' not in attrs


def test_web_companion_matches_client_fluent_design_and_hdr_tiles():
    from fastapi.testclient import TestClient
    from omni_server import app
    with TestClient(app) as client:
        page = client.get('/').text
        assert 'id="btn-paste"' in page and '解析剪切板' in page
        assert 'id="download-type-select"' in page and 'id="quality-container"' in page
        assert 'id="quality-policy"' not in page and 'id="quality-allow-lower"' not in page
        assert 'class="format-tile-grid" id="format-options-container"' in page
        assert 'src="/static/logo.png"' in page
        assert 'id="workspace-tabs-bar"' in page and 'id="web-lang-toggle"' in page
        css = client.get('/static/css/web-lite.css').text
        assert '--hdr-gold' in css and '.format-tile.is-hdr' in css
        assert '--primary: #F48FB1' in css and '--bg: #202020' in css
        home_js = client.get('/static/js/home.js').text
        assert 'isHdrFormat' in home_js and 'format-tile' in home_js
        assert 'upsertWorkspaceTab' in home_js and 'activateWorkspaceTab' in home_js

