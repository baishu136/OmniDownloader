import json
from types import SimpleNamespace
import pytest
from core.session_store import SessionStore
from engine.bilibili_extractor import collection_items
from engine.kuaishou import parse_page


def test_cookie_disable_preserves_encrypted_profile_and_never_reuses_generation(tmp_path):
    store = SessionStore(tmp_path)
    text = '# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tfixture-secret\n'
    store.import_text('YouTube', text)
    encrypted = store._path('YouTube').read_bytes()
    original = store.status('YouTube')['generation']
    store.set_enabled('YouTube', False)
    assert store.status('YouTube')['state'] == 'IMPORTED'
    assert store._path('YouTube').read_bytes() == encrypted
    assert b'fixture-secret' not in encrypted
    assert not SessionStore(tmp_path).is_enabled('YouTube')
    with store.cookie_file('YouTube') as filename: assert filename is None
    assert len(store.jar('YouTube')) == 0
    store.import_text('YouTube', text)
    assert not store.is_enabled('YouTube')
    store.set_enabled('YouTube', True)
    assert store.status('YouTube')['generation'] != original
    assert len(store.jar('YouTube')) == 1
    assert store.is_enabled('X (Twitter)')


def test_cookie_policy_api_rejects_cross_origin_and_keeps_imported_profile(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    from omni_server import app
    import core.session_store
    store = SessionStore(tmp_path)
    monkeypatch.setattr(core.session_store, 'sessions', store)
    with TestClient(app) as client:
        path = '/api/sessions/YouTube'
        assert client.patch(path, json={'enabled': False}, headers={'Origin': 'https://untrusted.invalid'}).status_code == 403
        response = client.patch(path, json={'enabled': False})
        assert response.status_code == 200 and not response.json()['enabled']
        assert client.post('/api/sessions', json={'platform': 'YouTube', 'cookies': '# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tfixture\n'}).status_code == 200
        assert not store.status('YouTube')['enabled']


def test_bilibili_ugc_collection_children_have_their_own_ids_and_durations():
    data = {'pages': [{'page': 1}], 'ugc_season': {'title': 'series', 'sections': [{'episodes': [
        {'bvid': 'BV1234567890', 'title': 'one', 'arc': {'duration': 12, 'pic': '//img.invalid/one'}, 'page': {'cid': 101}},
        {'bvid': 'BV0987654321', 'title': 'two', 'arc': {'duration': 21}, 'page': {'cid': 202}},
        {'bvid': 'malformed', 'page': {'cid': 303}}]}]}}
    children = collection_items(data, 'BV1234567890', 'author', '', [], {'id': '', 'name': '', 'checkedAt': 0})
    assert len(children) == 2
    assert children[0].media_id == 'BV1234567890:101' and children[1].media_id == 'BV0987654321:202'
    assert children[1].url.endswith('BV0987654321') and children[1].expected_duration_ms == 21000
    assert children[0].thumbnail_url == 'https://img.invalid/one'


def test_kuaishou_apollo_target_and_recommendation_are_isolated():
    identifier = '3x7drpm369pyk2i'
    photo = {'id': identifier, 'duration': 33000, 'caption': 'undefined fixture', 'videoResource': {'type': 'json', 'json': {'h264': {
        'adaptationSet': [{'representation': [{'url': 'https://cdn.invalid/target.mp4', 'width': 1280, 'height': 720}]}]}}}}
    def html(key):
        return '<script>window.__APOLLO_STATE__=' + json.dumps({'defaultClient': {'VisionVideoDetailPhoto:' + key: photo}}) + ';window.later={};</script>'
    url = 'https://www.kuaishou.com/short-video/' + identifier
    result = parse_page(html(identifier), url)
    assert result['id'] == identifier and result['duration_ms'] == 33000
    assert result['formats'][0]['width'] == 1280
    with pytest.raises(RuntimeError, match='MEDIA_ID_MISMATCH'): parse_page(html('other'), url)
    with pytest.raises(RuntimeError, match='BROWSER_ASSIST_REQUIRED'): parse_page('<html>risk</html>', url)


def test_kuaishou_backup_hosts_have_distinct_selectable_format_ids(monkeypatch):
    import engine.platforms as platforms
    identifier = '3x7drpm369pyk2i'
    photo = {'id': identifier, 'videoResource': {'type': 'json', 'json': {'h264': {'adaptationSet': [
        {'representation': [{'url': 'https://one.invalid/same.mp4', 'backupUrl': ['https://two.invalid/same.mp4'], 'width': 1280, 'height': 720}]}]}}}}
    text = '<script>window.__APOLLO_STATE__=' + json.dumps({'defaultClient': {'VisionVideoDetailPhoto:' + identifier: photo}}) + ';</script>'
    url = 'https://www.kuaishou.com/short-video/' + identifier
    monkeypatch.setattr(platforms, 'http', lambda *args: SimpleNamespace(headers={}))
    monkeypatch.setattr(platforms, 'page', lambda *args: SimpleNamespace(url=url, text=text))
    metadata = platforms.PlatformEngine._extract(url, '快手')
    assert len(metadata.available_video_formats) == 2
    assert len({f.format_id for f in metadata.available_video_formats}) == 2
