"""Player WBI protocol matching the pinned yt-dlp implementation."""
from urllib.parse import urlencode, urlsplit
import hashlib

PERMUTATION = (46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)


def key(image_url, sub_url):
    lookup = ''.join(urlsplit(url).path.rsplit('/', 1)[-1].split('.')[0] for url in (image_url, sub_url))
    if len(lookup) != 64 or any(c not in '0123456789abcdefABCDEF' for c in lookup): raise ValueError('[EXTRACTOR_CHANGED] 无效WBI公钥')
    return ''.join(lookup[i] for i in PERMUTATION)


def query(params, public_key, timestamp):
    if len(public_key) != 32: raise ValueError('无效WBI公钥')
    values = {k: ''.join(c for c in str(v) if c not in "!'()*") for k, v in sorted(dict(params, wts=timestamp).items())}
    encoded = urlencode(values)
    return encoded + '&w_rid=' + hashlib.md5((encoded + public_key).encode()).hexdigest()
