import asyncio
import os
from pathlib import Path
import sys
import tempfile
import zipfile
import pytest

from engine.quality import choose, label
from engine.platforms import douyin_item, normalize, platform, unpack_frames, concat_text
from engine.media_errors import sanitize, code
from engine.bilibili_extractor import validate_playback


def f(identifier, w, h, fps=30, codec='av01'):
    return dict(format_id=identifier, width=w, height=h, fps=fps, vcodec=codec, acodec='none')


def test_pixels_before_codec_and_fps():
    formats = [f('hd', 1920, 1080, 60, 'avc1'), f('4k', 3840, 2160), f('8k', 7680, 4320)]
    for order in [formats, list(reversed(formats))]: assert choose(order)['format_id'] == '8k'
    assert label(7680, 4320).startswith('8K')


def test_strict_ceiling_portrait_and_exact():
    formats = [f('8k', 7680, 4320), f('portrait', 2160, 3840), f('hd', 1920, 1080)]
    assert choose(formats, {'maxHeight': 2160})['format_id'] == 'portrait'
    with pytest.raises(ValueError): choose(formats, {}, 'missing')
    with pytest.raises(ValueError): choose([formats[-1]], {'mode': 'TARGET_TIER', 'maxHeight': 2160})


def test_native_only_when_marked():
    assert choose([dict(f('AI', 7680, 4320), is_upscaled=True), f('original', 3840, 2160)])['format_id'] == 'original'


def test_no_recommendation_fallback():
    with pytest.raises(RuntimeError, match='MEDIA_ID_MISMATCH'): douyin_item([{'aweme_id': 'other'}], 'target')
    with pytest.raises(RuntimeError, match='NO_MEDIA'): douyin_item([{'aweme_id': 'target', 'images': [{}]}, {'aweme_id': 'other', 'video': {}}], 'target')


def test_xhs_token_one_decode_and_hosts():
    raw = 'https://www.xiaohongshu.com/login?redirectPath=https%3A%2F%2Fwww.xiaohongshu.com%2Fexplore%2Fabc%3Fxsec_token%3Da%252Bb%253D'
    assert normalize(raw).endswith('xsec_token=a%2Bb%3D')
    assert platform('https://netflix.com/') == '网络视频'
    assert platform('https://x.com/user/status/12') == 'X (Twitter)'
    with pytest.raises(ValueError): normalize('https://www.xiaohongshu.com/login?redirectPath=https://evil.test/explore/abc')


def test_preview_and_error_classification():
    with pytest.raises(RuntimeError, match='PREVIEW_ONLY'): validate_playback({'data': {'accept_description': ['试看']}})
    assert code('No video could be found in this tweet') == 'MEDIA_UNCONFIRMED'
    assert 'secret' not in sanitize('https://user:secret@host/video?xsec_token=secret&signature=secret')


def test_ugoira_path_limits_and_order(tmp_path):
    archive = tmp_path / 'source.zip'
    with zipfile.ZipFile(archive, 'w') as z: z.writestr('0.jpg', b'frame')
    frames = [{'file': '0.jpg', 'delay': 80}]
    unpack_frames(archive, tmp_path / 'frames', frames)
    assert (tmp_path / 'frames/0.jpg').read_bytes() == b'frame'
    assert 'duration 0.080' in concat_text(frames)
    with pytest.raises(ValueError): unpack_frames(archive, tmp_path / 'frames', [{'file': '../0.jpg', 'delay': 80}])


@pytest.mark.skipif(os.name != 'nt', reason='DPAPI is Windows-only')
def test_dpapi_session_isolated_and_not_plaintext(tmp_path):
    from core.session_store import SessionStore
    store = SessionStore(tmp_path)
    cookie = '# Netscape HTTP Cookie File\n.x.com\tTRUE\t/\tTRUE\t0\tauth_token\tfixture-secret\n'
    store.import_text('X (Twitter)', cookie)
    assert b'fixture-secret' not in store._path('X (Twitter)').read_bytes()
    assert len(store.jar('X (Twitter)')) == 1
    store.clear('X (Twitter)')
    assert store.status('X (Twitter)')['state'] == 'MISSING'


def test_bili_access_requires_account_and_stream_evidence():
    from engine.bilibili_extractor import access_state
    assert access_state(True, False, True, False, 60000, 60000) == 'DENIED'
    assert access_state(True, True, True, False, 60000, 60000) == 'FULL'
    assert access_state(True, True, True, True, 60000, 60000) == 'PREVIEW'
    assert access_state(True, True, True, False, 60000, 10000) == 'PREVIEW'
    assert access_state(None, False, True, False, 60000, 60000) == 'UNKNOWN'
    assert access_state(True, True, False, False, 60000, 60000) == 'UNKNOWN'


def test_bili_nav_checks_real_login_fields(monkeypatch):
    from engine.bilibili_extractor import BilibiliDirectExtractor
    from types import SimpleNamespace
    monkeypatch.setattr(BilibiliDirectExtractor.session, 'get', lambda *a, **k: SimpleNamespace(json=lambda: {'code': 0, 'data': {'isLogin': True, 'mid': 123, 'uname': 'fixture-account'}}))
    account = BilibiliDirectExtractor._verify_account({'Cookie': 'SESSDATA=fixture'})
    assert account['verified'] and account['id'] == '123'
    monkeypatch.setattr(BilibiliDirectExtractor.session, 'get', lambda *a, **k: SimpleNamespace(json=lambda: {'code': -101, 'data': {'isLogin': False}}))
    assert not BilibiliDirectExtractor._verify_account({'Cookie': 'SESSDATA=fixture'})['verified']


def test_wbi_matches_pinned_upstream_vector():
    from engine.wbi import key, query
    public = key('https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png', 'https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png')
    assert public == '1022a87ffdaf532cb45ee953dce8c96d'
    assert query({'bvid': 'BV1234567890', 'cid': '42', 'foo': "a b~!'()*"}, public, 1700000000) == 'bvid=BV1234567890&cid=42&foo=a+b~&wts=1700000000&w_rid=f7d142412f8c097d5da023f5dc9d530b'


def test_wbi_fallback_is_bounded_and_does_not_retry_auth(monkeypatch):
    from engine.bilibili_extractor import BilibiliDirectExtractor
    from types import SimpleNamespace
    called = []
    def get(url, **kwargs):
        called.append(url)
        data = {'code': 0, 'data': {}} if '/wbi/playurl?' in url else {'code': -400}
        if '/nav' in url:
            data = {'code': -101, 'data': {'wbi_img': {'img_url': 'https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png', 'sub_url': 'https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png'}}}
        return SimpleNamespace(status_code=200, raise_for_status=lambda: None, json=lambda: data)
    monkeypatch.setattr(BilibiliDirectExtractor.session, 'get', get)
    assert BilibiliDirectExtractor._playinfo('https://api.bilibili.com/x/player/playurl?bvid=BV1234567890&cid=42&qn=120', {})['code'] == 0
    assert len(called) == 3 and 'w_rid=' in called[-1]
    called.clear()
    def denied(url, **kwargs):
        called.append(url)
        return SimpleNamespace(status_code=200, raise_for_status=lambda: None, json=lambda: {'code': -101})
    monkeypatch.setattr(BilibiliDirectExtractor.session, 'get', denied)
    assert BilibiliDirectExtractor._playinfo('https://api.bilibili.com/x/player/playurl?cid=42', {})['code'] == -101
    assert len(called) == 1
