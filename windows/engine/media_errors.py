import re


def sanitize(text):
    text = re.sub(r'(?i)\b(cookie|set-cookie|authorization)\s*:\s*[^\r\n]+', r'\1: ***', str(text))
    text = re.sub(r'(?i)(https?://)[^/\s@]+@', r'\1***@', text)
    return re.sub(r'(?i)([?&](?:[^=&\s]*(?:token|sign|secret|auth|key|sessdata|expire)[^=&\s]*)=)[^&\s]+', r'\1***', text)


def code(text):
    text = str(text)
    match = re.search(r'\[([A-Z_]+)\]', text)
    if match:
        return match[1]
    lower = text.lower()
    if any(v in lower for v in ("isn't available to everyone", "can't be seen by certain audiences", 'not available to everyone')): return 'ACCESS_DENIED'
    if any(value in lower for value in ('tunnel connection failed', 'unable to connect to proxy', 'proxyerror', 'https proxy')): return 'PROXY_ERROR'
    for needles, value in [
        (('no video could be found',), 'MEDIA_UNCONFIRMED'),
        (('not a bot', 'captcha', 'challenge'), 'CHALLENGE_REQUIRED'),
        (('login', 'log in', 'sign in', 'authentication'), 'AUTH_REQUIRED'),
        (('429', 'rate limit'), 'RATE_LIMITED'),
        (('requested format',), 'QUALITY_UNAVAILABLE'),
        (('timeout', 'timed out', 'connection', 'proxy', 'unexpected_eof', 'unexpected eof', 'eof occurred', 'server disconnected'), 'NETWORK_ERROR'),
        (('deleted', 'unavailable', '404'), 'CONTENT_UNAVAILABLE'),
    ]:
        if any(n in lower for n in needles):
            return value
    return 'EXTRACTOR_CHANGED'
