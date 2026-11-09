import json
from pathlib import Path
from unittest.mock import Mock
import pytest

from engine.media_resolver import (MediaError, bilibili_dynamic_images, bilibili_opus_images, douyin_video_formats,
    douyin_images_from_item, kuaishou_atlas, mp4_dimensions, pinterest_video_data, xhs_video_formats)
from engine.platforms import normalize, platform
from engine.media_errors import code


def box(kind, payload):
    return (len(payload)+8).to_bytes(4, 'big') + kind + payload


def movie(width=3840, height=2160):
    tkhd = bytes(76) + (width << 16).to_bytes(4, 'big') + (height << 16).to_bytes(4, 'big')
    return box(b'moov', box(b'trak', box(b'tkhd', tkhd)))


def test_mp4_probe_reads_real_dimensions_from_head_or_suffix_without_decoding():
    assert mp4_dimensions(box(b'ftyp', bytes(8)) + movie()) == (3840, 2160)
    assert mp4_dimensions(b'middle-of-mdat' + movie(1080, 1920)) == (1080, 1920)
    assert mp4_dimensions(movie()[:-2]) is None
    assert mp4_dimensions(b'not a media file moov <html>') is None
    assert mp4_dimensions(movie(0, 0)) is None


def test_xhs_preserves_all_codec_variants_and_backups_and_probes_origin():
    note = {'type':'video','video':{'consumer': {'originVideoKey': 'origin/safe.mp4'}, 'media': {'stream': {
        'h264':[{'width':1280,'height':720,'masterUrl':'https://cdn.test/720.mp4','backupUrls':['https://cdn2.test/720.mp4']}],
        'hevc':[{'width':3840,'height':2160,'masterUrl':'https://cdn.test/4k.mp4'}]}}}}
    assert len(xhs_video_formats(note)) == 3
    assert any(f['width'] == 3840 for f in xhs_video_formats(note))


def test_douyin_keeps_higher_bitrates_instead_of_first_play_addr():
    data = {'video':{'width':1280,'height':720,'play_addr':{'url_list':['https://cdn.test/sd.mp4']},
                    'bit_rate':[{'gear_name':'1080p_265','bit_rate':6000000,'play_addr':{'width':1920,'height':1080,'url_list':['https://cdn.test/hd.mp4','https://cdn2.test/hd.mp4']}}]}}
    formats = douyin_video_formats(data)
    assert formats[0]['height'] == 1080
    assert len(formats) == 3
    assert formats[0]['tbr'] == 6000


def test_browser_images_must_match_requested_post():
    with pytest.raises(MediaError, match='MEDIA_ID_MISMATCH'):
        douyin_images_from_item({'aweme_id':'other','images':[{'url_list':['https://cdn.test/img.jpg']}]}, 'https://www.douyin.com/note/123', '123')
    values = douyin_images_from_item({'aweme_id':'123','images':[{'uri':'picture','width':1080,'height':1920,'url_list':['https://cdn.test/img.jpg']}]}, 'https://www.douyin.com/note/123', '123')
    assert values[-1][0]['id'].startswith('123:')


def test_kuaishou_mobile_host_normalizes_to_same_video_without_generic_fallback():
    url='https://kphm5nf3.m.chenzhongtech.com/fw/photo/3xfsgzwdzmh8dv2?shareObjectId=999'
    assert platform(url) == '快手'
    assert normalize(url) == 'https://www.kuaishou.com/short-video/3xfsgzwdzmh8dv2'
    assert platform('https://chenzhongtech.com.evil.invalid/photo/1') == '网络视频'


def encode_cache_id(identifier):
    shifted = ''.join(chr(ord(c)+1) for c in identifier)
    return ''.join(chr(ord(c)+1) for c in 'photoId-tusjoh.' + shifted)


def atlas_page(identifier='3xabc', numeric='55'):
    data={encode_cache_id(identifier): {'photo':{'photoId':numeric,'caption':'fixture','userName':'a'},
          'atlas':{'cdn':['p2.a.yximgs.com'],'list':['/ufile/atlas/image.gif'],'size':[{'w':640,'h':480}]}}}
    return '<script>window.INIT_STATE='+json.dumps(data)+';</script>'


def test_kuaishou_atlas_checks_both_share_identity_and_own_cache_entry():
    result=kuaishou_atlas(atlas_page(),'https://mobile.chenzhongtech.com/fw/photo/3xabc?shareObjectId=55')
    assert result[-1][0]['url'].endswith('.gif')
    assert result[-1][0]['width'] == 640
    with pytest.raises(MediaError, match='MEDIA_ID_MISMATCH'):
        kuaishou_atlas(atlas_page(),'https://mobile.chenzhongtech.com/fw/photo/3xabc?shareObjectId=77')
    with pytest.raises(MediaError, match='BROWSER_ASSIST_REQUIRED'):
        kuaishou_atlas(atlas_page(),'https://mobile.chenzhongtech.com/fw/photo/other')


@pytest.mark.parametrize('kind', ['draw','opus'])
def test_bilibili_dynamic_preserves_gifs_and_never_uses_avatar(kind):
    major = {kind:{'items' if kind=='draw' else 'pics':[{'src':'https://i0.hdslb.com/bfs/a.gif','width':640,'height':480}]}}
    item={'id_str':'123','type':'DYNAMIC_TYPE_DRAW','modules':{'module_dynamic':{'major':major},'module_author':{'name':'a','face':'https://evil/avatar.jpg'}}}
    data={'code':0,'data':{'item':item}}
    result=bilibili_dynamic_images(data,'123','https://t.bilibili.com/123')
    assert len(result[-1]) == 1
    assert result[-1][0]['url'].endswith('.gif')
    item['type']='DYNAMIC_TYPE_FORWARD'
    with pytest.raises(MediaError, match='NO_MEDIA'): bilibili_dynamic_images(data,'123','https://t.bilibili.com/123')
    with pytest.raises(MediaError, match='MEDIA_ID_MISMATCH'): bilibili_dynamic_images(data,'456','https://t.bilibili.com/456')


def test_pinterest_manifest_timeout_does_not_discard_independent_mp4():
    extractor=Mock();extractor._extract_m3u8_formats.side_effect=TimeoutError('CDN timeout')
    data={'id':'123','videos':{'video_list':{'HLS':{'url':'https://cdn.test/video.m3u8'},'MP4':{'url':'https://cdn2.test/video.mp4','width':1080,'height':1920}}}}
    result=pinterest_video_data(data,'123','https://www.pinterest.com/pin/123',extractor)
    assert result['formats'][0]['height'] == 1920
    del data['videos']['video_list']['MP4']
    with pytest.raises(MediaError, match='NETWORK_ERROR'): pinterest_video_data(data,'123','https://www.pinterest.com/pin/123',extractor)


def test_instagram_audience_restriction_is_not_unknown_or_retried_as_network():
    assert code("This content isn't available to everyone: It can't be seen by certain audiences.") == 'ACCESS_DENIED'


def test_bilibili_opus_uses_body_gifs_not_cover_album_and_respects_paywall():
    state={'id':'123','detail':{'id_str':'123','basic':{'title':'post'},'modules':[
        {'module_top':{'display':{'album':{'pics':[{'url':'https://cdn.test/cover.jpg'}]}}}},
        {'module_content':{'paragraphs':[{'pic':{'pics':[{'url':'https://cdn.test/animation.gif','width':640,'height':480}]}}]}}]}}
    values=bilibili_opus_images(state,'123','https://www.bilibili.com/opus/123')
    assert len(values[-1]) == 1
    assert values[-1][0]['url'].endswith('animation.gif')
    state['detail']['modules'].append({'module_paywall':{'title':'paid'}})
    with pytest.raises(MediaError, match='ACCESS_DENIED'): bilibili_opus_images(state,'123','https://www.bilibili.com/opus/123')


def test_instagram_retries_transport_failure_once_but_never_audience_denial(monkeypatch):
    import yt_dlp
    import engine.ytdlp_engine as module
    engine=module.YtDlpEngine; engine._cache.clear()
    monkeypatch.setattr(module.time,'sleep',lambda _:None)
    ydl=Mock();ydl.params={};ydl.extract_info.side_effect=[yt_dlp.utils.DownloadError('SSL UNEXPECTED_EOF_WHILE_READING'),{'id':'same','formats':[]}]
    assert engine._resolve('https://www.instagram.com/reel/fixture/','Instagram',ydl)['id']=='same'
    assert ydl.extract_info.call_count==2
    ydl.extract_info.reset_mock();ydl.extract_info.side_effect=yt_dlp.utils.DownloadError("This content isn't available to everyone")
    with pytest.raises(yt_dlp.utils.DownloadError): engine._resolve('https://www.instagram.com/reel/restricted/','Instagram',ydl)
    assert ydl.extract_info.call_count==1


def test_browser_image_capture_requires_same_post_and_preserves_media_snapshot(tmp_path, monkeypatch):
    from engine.browser_assist import BrowserAssist
    from core.session_store import SessionStore
    import core.session_store
    from engine.images import ImageEngine
    monkeypatch.setattr(core.session_store,'sessions',SessionStore(tmp_path))
    saved=[];monkeypatch.setattr(ImageEngine,'remember',lambda post:saved.append(post))
    assist=BrowserAssist();assist.target='https://www.douyin.com/note/1234567890123456789'
    window=Mock();window.get_current_url.return_value=assist.target
    item={'aweme_id':'1234567890123456789','images':[{'uri':'fixture','width':640,'height':480,'url_list':['https://cdn.test/img.gif']}]}
    window.evaluate_js.side_effect=[None,json.dumps(item)]
    assist.window=window
    post=assist.images();assert saved==[post];assert post['resources'][0]['url'].endswith('.gif')
    window.get_current_url.return_value='https://www.douyin.com/note/9999999999999999999'
    with pytest.raises(ValueError, match='MEDIA_ID_MISMATCH'):assist.images()
    store=core.session_store.sessions;store.set_enabled('抖音',False)
    with pytest.raises(ValueError, match='COOKIE_DISABLED'):assist.images()
