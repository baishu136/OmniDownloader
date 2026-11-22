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
            response = responses[min(len(calls) - 1, len(responses) - 1)]
            if isinstance(response, BaseException): raise response
            return response
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


def test_transient_tls_eof_is_retried_without_account_cookies(monkeypatch):
    failure = yt_dlp.utils.ExtractorError('SSL UNEXPECTED_EOF_WHILE_READING token=synthetic')
    calls, options = endpoint(monkeypatch, [failure, {'id_str': ID, 'mediaDetails': [{'type': 'photo'}]}])
    monkeypatch.setattr(media.time, 'sleep', lambda _: None)
    result = media.twitter_status(URL, cookie_file='never-send-account-cookie', public_only=True)
    assert result[0]['_omni_media'] == [{'type': 'photo'}]
    assert len(calls) == 2 and all('cookiefile' not in value for value in options)


def test_repeated_tls_failure_is_bounded_and_has_safe_structured_error(monkeypatch):
    calls, _ = endpoint(monkeypatch, [yt_dlp.utils.ExtractorError('SSLEOFError UNEXPECTED_EOF_WHILE_READING ?token=synthetic')])
    monkeypatch.setattr(media.time, 'sleep', lambda _: None)
    with pytest.raises(media.MediaError) as error: media.twitter_status(URL, public_only=True)
    assert error.value.code == 'NETWORK_ERROR' and len(calls) == 2
    assert 'synthetic' not in str(error.value)


@pytest.mark.parametrize('message', ['CERTIFICATE_VERIFY_FAILED', 'HTTP Error 403: Forbidden', 'HTTP Error 401: Unauthorized'])
def test_certificates_and_access_failures_are_not_retried_or_bypassed(monkeypatch, message):
    calls, options = endpoint(monkeypatch, [yt_dlp.utils.ExtractorError(message)])
    with pytest.raises(yt_dlp.utils.ExtractorError): media.twitter_status(URL, public_only=True)
    assert len(calls) == 1 and all(not value.get('nocheckcertificate') for value in options)
