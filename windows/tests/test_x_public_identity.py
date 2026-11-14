import pytest
import yt_dlp
from engine import media_resolver as media

ID = '2119233448888217846'
URL = f'https://x.com/i/status/{ID}'


def endpoint(monkeypatch, responses):
    calls, options = [], []
    class Extractor:
        def _generate_syndication_token(self, identifier):
            assert identifier == ID
            return 'fixture-token'
        def _download_json(self, url, identifier, **kwargs):
            calls.append((url, identifier, kwargs))
            return responses[min(len(calls) - 1, len(responses) - 1)]
    class Engine:
        def __init__(self, opts): options.append(opts)
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def get_info_extractor(self, *args): return Extractor()
    monkeypatch.setattr(yt_dlp, 'YoutubeDL', Engine)
    return calls, options


def test_empty_then_valid_response_recovers_without_guessing_the_post(monkeypatch):
    calls, options = endpoint(monkeypatch, [{}, {'id_str': ID, 'mediaDetails': [{'type': 'photo'}]}])
    status, route, identifier = media.twitter_status(URL, cookie_file='never-send-account-cookie', public_only=True)
    assert identifier == ID and route == 'public_syndication'
    assert status['_omni_media'] == [{'type': 'photo'}] and len(calls) == 2
    assert all('cookiefile' not in value for value in options)


@pytest.mark.parametrize('response', [{}, None, [], {'id_str': ''}, {'quoted_tweet': {'id_str': ID}}])
def test_missing_root_id_is_not_proof_of_mismatch_but_still_cannot_be_used(monkeypatch, response):
    calls, _ = endpoint(monkeypatch, [response])
    with pytest.raises(media.MediaError) as caught:
        media.twitter_status(URL, public_only=True)
    assert caught.value.code == 'MEDIA_UNCONFIRMED' and len(calls) == 2


def test_explicit_wrong_root_is_a_hard_mismatch_without_retry(monkeypatch):
    calls, _ = endpoint(monkeypatch, [{'id_str': '999', 'quoted_tweet': {'id_str': ID}}, {'id_str': ID}])
    with pytest.raises(media.MediaError) as caught:
        media.twitter_status(URL, public_only=True)
    assert caught.value.code == 'MEDIA_ID_MISMATCH' and len(calls) == 1


def test_blank_string_id_can_fall_back_to_exact_id(monkeypatch):
    calls, _ = endpoint(monkeypatch, [{'id_str': '', 'id': ID}])
    assert media.twitter_status(URL, public_only=True)[2] == ID
    assert len(calls) == 1
