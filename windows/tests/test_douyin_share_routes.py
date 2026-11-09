import json
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest
from engine import media_resolver as media

ID = '7567352444847574309'

def test_upload_dimensions_are_not_reused_for_unmeasured_playback_stream():
    formats = media.douyin_video_formats({'video': {'width': 3840, 'height': 2160, 'play_addr': {'url_list': ['https://cdn.test/play.mp4']}}})
    assert formats[0]['width'] == 0 and formats[0]['height'] == 0

NOTE = f'https://www.iesdouyin.com/share/note/{ID}/'
VIDEO = f'https://www.iesdouyin.com/share/video/{ID}/'


def response(url, state):
    return SimpleNamespace(url=url, text='<script>window._ROUTER_DATA=' + json.dumps(state) + '</script>')


def photo():
    return {'aweme_id': ID, 'desc': 'target', 'images': [{'width': 100, 'height': 200, 'url_list': ['https://cdn.test/photo.jpg']} for _ in range(21)]}


def client():
    value = Mock(); value.headers = {}; return value


def test_note_shell_retries_same_id_video_ssr_and_keeps_all_images():
    http = client()
    shell = response(NOTE, {'aweme_id': ID, 'statistics': {}})
    full = response(VIDEO, {'shell': {'aweme_id': ID}, 'recommendation': {'aweme_id': '111', 'images': [1]}, 'target': photo()})
    with patch.object(media, 'page', side_effect=[shell, full]) as get:
        canonical, identifier, item = media.douyin_target(http, NOTE)
    assert (canonical, identifier, len(item['images'])) == (VIDEO, ID, 21)
    assert get.call_count == 2
    assert get.call_args.args[1] == VIDEO
    http.get.assert_not_called()


def test_statistics_shell_does_not_hide_media_bearing_target():
    found = media.find_douyin_item({'stats': {'aweme_id': ID}, 'target': photo()}, ID)
    assert len(found['images']) == 21


def test_alternate_redirect_to_other_work_is_rejected():
    with patch.object(media, 'page', side_effect=[response(NOTE, {}), response('https://www.iesdouyin.com/share/video/111/', photo())]):
        with pytest.raises(media.MediaError, match='MEDIA_ID_MISMATCH'):
            media.douyin_target(client(), NOTE)


def test_auth_failure_is_not_retried_or_replaced_by_another_source():
    http = client()
    with patch.object(media, 'page', side_effect=media.MediaError('AUTH_REQUIRED', 'session required')) as get:
        with pytest.raises(media.MediaError, match='AUTH_REQUIRED'): media.douyin_target(http, NOTE)
    assert get.call_count == 1
    http.get.assert_not_called()


def test_both_shells_and_empty_detail_return_honest_browser_requirement():
    http = client(); http.get.return_value.status_code = 200; http.get.return_value.json.return_value = {'aweme_detail': {'aweme_id': ID}}
    with patch.object(media, 'page', side_effect=[response(NOTE, {}), response(VIDEO, {})]) as get:
        with pytest.raises(media.MediaError, match='BROWSER_ASSIST_REQUIRED'): media.douyin_target(http, NOTE)
    assert get.call_count == 2


def test_empty_detail_does_not_replace_valid_video_metadata():
    http = client(); http.get.return_value.status_code = 200; http.get.return_value.json.return_value = {'aweme_detail': {'aweme_id': ID}}
    video = {'aweme_id': ID, 'video': {'play_addr': {'url_list': ['https://cdn.test/video.mp4']}}}
    with patch.object(media, 'page', return_value=response(VIDEO, video)) as get:
        _, _, item = media.douyin_target(http, VIDEO)
    assert item['video']['play_addr']['url_list'] == ['https://cdn.test/video.mp4']
    assert get.call_count == 1


def test_socks_uses_remote_dns_without_changing_global_sockets():
    http = media.session(proxy='socks5://127.0.0.1:1080')
    assert http.proxies == {'http': 'socks5h://127.0.0.1:1080', 'https': 'socks5h://127.0.0.1:1080'}
