import json
import zipfile
from pathlib import Path
import pytest
from engine import media_resolver as media
from engine.ytdlp_engine import YtDlpEngine


@pytest.mark.parametrize('layout', ['mobile', 'desktop'])
def test_xhs_share_state_supports_both_layouts_without_recommendation(monkeypatch, layout):
    identifier = '6ae92bb20000000018038a89'
    note = {'noteId': identifier, 'type': 'normal', 'title': 'undefined and } braces',
            'imageList': [{'fileId': 'image1', 'width': 2625, 'height': 3500,
                           'url': 'https://image.example.invalid/one.jpg'}]}
    if layout == 'mobile':
        state = {'noteData': {'data': {'noteData': note}, 'recommend': {'noteId': 'other'}}}
    else:
        state = {'note': {'noteDetailMap': {identifier: {'note': note}}}}
    raw = json.dumps(state)[:-1] + ',"unused":[undefined]}'
    class Response:
        url = 'https://www.xiaohongshu.com/discovery/item/' + identifier
        text = '<script>window.__INITIAL_STATE__=' + raw + '; window.other={};</script><script>later={};</script>'
    client = type('Client', (), {'headers': {}})()
    monkeypatch.setattr(media, 'page', lambda *args: Response())
    monkeypatch.setattr(media, 'probe_image_dimensions', lambda *args: (2625, 3500))
    values = media.resolve_xhs(client, 'https://xhslink.cn/o/fixture')
    assert values[1] == identifier and values[2] == 'undefined and } braces'
    assert len(values[4]) == 1 and values[4][0]['width'] == 2625
    assert 'Mobile' in client.headers['User-Agent']


@pytest.mark.parametrize('note_type,expected', [('video', 'NO_MEDIA'), ('wrong_id', 'MEDIA_ID_MISMATCH')])
def test_xhs_images_reject_video_covers_and_other_notes(monkeypatch, note_type, expected):
    identifier = '6ae92bb20000000018038a89'
    note = {'noteId': 'other' if note_type == 'wrong_id' else identifier,
            'type': 'video', 'imageList': [{'url': 'https://image.example.invalid/cover.jpg'}]}
    state = {'noteData': {'data': {'noteData': note}}}
    class Response:
        url = 'https://www.xiaohongshu.com/discovery/item/' + identifier
        text = '<script>window.__INITIAL_STATE__=' + json.dumps(state) + ';</script>'
    monkeypatch.setattr(media, 'page', lambda *args: Response())
    with pytest.raises(media.MediaError, match=expected):
        media.resolve_xhs(type('Client', (), {'headers': {}})(), Response.url)


def test_xhs_does_not_find_images_inside_unrelated_nested_objects(monkeypatch):
    identifier = '6ae92bb20000000018038a89'
    state = {'tracking': {'noteId': identifier, 'imageList': [{'url': 'https://image.example.invalid/wrong.jpg'}]}}
    class Response:
        url = 'https://www.xiaohongshu.com/discovery/item/' + identifier
        text = '<script>window.__INITIAL_STATE__=' + json.dumps(state) + ';</script>'
    monkeypatch.setattr(media, 'page', lambda *args: Response())
    with pytest.raises(media.MediaError, match='MEDIA_ID_MISMATCH'):
        media.resolve_xhs(type('Client', (), {'headers': {}})(), Response.url)


def test_exact_post_never_uses_recommendation():
    payload = {'list': [{'aweme_id':'2','images':[{'url':'https://example.invalid/wrong.jpg'}]}]}
    assert media.find_object(payload,'1',('aweme_id',)) is None
    assert media.find_object(payload,'2',('aweme_id',))['aweme_id'] == '2'


def test_original_priority_and_no_watermark_claim_for_unknown():
    assert media.choose_image({'urlDefault':'https://example.invalid/preview','original':'https://example.invalid/original'}) == ('https://example.invalid/original',True)
    assert media.resource('https://example.invalid/image','1','小红书')['watermark'] == 'UNCONFIRMED'
    assert media.resource('file:///tmp/a','1','Pixiv') is None


def test_html_is_rejected_as_image():
    with pytest.raises(media.MediaError,match='POSTPROCESS_FAILED'):
        media.image_extension(b'<html>login required</html>')


def test_public_fallback_keeps_original_media_index(monkeypatch):
    import yt_dlp
    class Extractor:
        pass
    class FakeYdl:
        def __init__(self,*args): pass
        def __enter__(self): return self
        def __exit__(self,*args): pass
        def get_info_extractor(self,*args): return Extractor()
    monkeypatch.setattr(yt_dlp,'YoutubeDL',FakeYdl)
    monkeypatch.setattr(media,'twitter_status',lambda *a,**k: ({'_omni_media':[
        {'type':'photo'}, {'type':'video','id_str':'media2','video_info':{'variants':[{'content_type':'video/mp4','url':'https://video.twimg.com/ext_tw_video/2/vid/1280x720/x.mp4'}]}}
    ]},'public_syndication','123'))
    result=media.resolve_x_video('https://x.com/a/status/123/video/2')
    assert result['id']=='media2' and result['webpage_url'].endswith('/video/2')
    with pytest.raises(media.MediaError,match='MEDIA_ID_MISMATCH'):
        media.resolve_x_video('https://x.com/a/status/123/video/1')


def test_zero_dimension_x_gif_is_real_media():
    result=YtDlpEngine._metadata({'id':'gif','formats':[{'format_id':'http','url':'https://video.twimg.com/tweet_video/test.mp4','ext':'mp4','vcodec':'h264','acodec':'none'}]},'https://x.com/a/status/1','X (Twitter)')
    assert result.is_gif and len(result.available_video_formats)==1


def test_x_connection_reset_uses_independent_public_route(monkeypatch):
    import yt_dlp
    calls=[]
    class Interrupted:
        params={'proxy':''}
        def extract_info(self,*args,**kwargs): raise yt_dlp.utils.DownloadError('Connection reset by peer')
    monkeypatch.setattr(media,'resolve_x_video',lambda url,**kwargs: calls.append(url) or {'id':'same-media','formats':[{'format_id':'http','url':'https://video.twimg.com/test.mp4'}]})
    YtDlpEngine._cache.clear()
    info=YtDlpEngine._resolve('https://x.com/a/status/999','X (Twitter)',Interrupted())
    assert info['id']=='same-media' and calls==['https://x.com/a/status/999']


def test_youtube_cookie_scope_and_temporary_file_cleanup(tmp_path):
    from core.session_store import SessionStore
    store=SessionStore(tmp_path)
    store.import_text('YouTube','# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tfixture-session\n.evil.invalid\tTRUE\t/\tTRUE\t0\tsecret\tdiscard-this\n')
    with store.cookie_file('YouTube') as filename:
        text=Path(filename).read_text('utf-8')
        assert 'fixture-session' in text and 'discard-this' not in text
    assert not Path(filename).exists()
    assert b'fixture-session' not in store._path('YouTube').read_bytes()


def test_archive_preserves_bytes_and_excludes_signed_urls(tmp_path,monkeypatch):
    png=bytes.fromhex('89504e470d0a1a0a') + b'original-image-fixture'
    monkeypatch.setattr(media,'resolve_images',lambda *a: {'contentId':'123','resources':[{'id':'123:p0','url':'https://example.invalid/a?token=SECRET','headers':{},'watermark':'SERVER_ORIGINAL'}]})
    class Response:
        status_code=200
        def __enter__(self): return self
        def __exit__(self,*args): pass
        def raise_for_status(self): pass
        def iter_content(self,*args): yield png
    class Client:
        def get(self,*a,**k): return Response()
        def close(self): pass
    monkeypatch.setattr(media,'session',lambda *a: Client())
    result=media.download_images('https://www.pixiv.net/artworks/123','Pixiv',['123:p0'],tmp_path,True)
    with zipfile.ZipFile(result['path']) as archive:
        assert archive.read('123_001.png') == png
        assert b'SECRET' not in archive.read('manifest.json')
        assert archive.testzip() is None
    with pytest.raises(media.MediaError,match='MEDIA_ID_MISMATCH'):
        media.download_images('https://www.pixiv.net/artworks/123','Pixiv',['other'],tmp_path,True)
