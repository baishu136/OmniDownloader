import io
from fastapi.testclient import TestClient
from PIL import Image

import omni_server


def test_media_thumb_rejects_loopback_and_private_urls():
    client = TestClient(omni_server.app)
    for bad in [
        'http://127.0.0.1:58000/secret',
        'http://localhost/admin',
        'http://192.168.1.1/cam.jpg',
        'file:///C:/Windows/win.ini',
    ]:
        resp = client.get('/api/media/thumb', params={'url': bad})
        assert resp.status_code == 400


def test_media_thumb_proxies_with_referer_and_converts_webp(monkeypatch):
    buf = io.BytesIO()
    Image.new('RGB', (24, 16), (220, 90, 40)).save(buf, format='WEBP')
    webp_bytes = buf.getvalue()
    captured = {}

    class FakeResp:
        ok = True
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def iter_content(self, size): yield webp_bytes

    class FakeSession:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def get(self, url, headers=None, stream=True, timeout=None):
            captured['url'] = url
            captured['headers'] = dict(headers or {})
            return FakeResp()

    import engine.media_resolver as media_resolver
    monkeypatch.setattr(media_resolver, 'session', lambda *a, **kw: FakeSession())

    client = TestClient(omni_server.app)
    resp = client.get('/api/media/thumb', params={'url': 'https://i0.hdslb.com/bfs/archive/test.webp'})
    assert resp.status_code == 200
    assert resp.headers['content-type'].startswith('image/jpeg')
    assert resp.content.startswith(b'\xff\xd8\xff')
    assert captured['headers']['Referer'] == 'https://www.bilibili.com/'


def test_media_thumb_falls_back_to_local_image_task(tmp_path, monkeypatch):
    cover_file = tmp_path / 'sample.png'
    Image.new('RGB', (12, 12), (40, 140, 220)).save(cover_file, format='PNG')

    fake_task = type('Task', (), {'local_file_path': str(cover_file)})()
    monkeypatch.setattr(omni_server.task_manager, 'get_task', lambda tid: fake_task if tid == 't1' else None)

    client = TestClient(omni_server.app)
    resp = client.get('/api/media/thumb', params={'task_id': 't1'})
    assert resp.status_code == 200
    assert resp.headers['content-type'].startswith('image/png')
    assert resp.content.startswith(b'\x89PNG')
