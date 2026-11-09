from engine import media_resolver as media


def test_bounded_headers_support_transcoded_image_sizes_without_upload_metadata():
    png = b'\x89PNG\r\n\x1a\n' + bytes(8) + (1080).to_bytes(4, 'big') + (2347).to_bytes(4, 'big')
    assert media.image_dimensions(png) == (1080, 2347)
    jpeg = b'\xff\xd8\xff\xc0\x00\x0b\x08' + (2347).to_bytes(2, 'big') + (1080).to_bytes(2, 'big') + bytes(4)
    assert media.image_dimensions(jpeg) == (1080, 2347)
    webp = b'RIFF' + bytes(4) + b'WEBPVP8X' + bytes(8) + (1079).to_bytes(3, 'little') + (2346).to_bytes(3, 'little')
    assert media.image_dimensions(webp) == (1080, 2347)
    assert media.image_dimensions(b'<html>error') is None
    assert media.image_dimensions(jpeg[:-3]) is None


def test_xhs_uses_current_resource_dimensions_instead_of_upload_size(monkeypatch):
    monkeypatch.setattr(media, 'xhs_target', lambda *a: ('https://www.xiaohongshu.com/explore/abc', 'abc', {
        'type': 'normal', 'imageList': [{'width': 1320, 'height': 2868, 'url': 'https://cdn.test/preview.webp'}]}))
    monkeypatch.setattr(media, 'probe_image_dimensions', lambda *a: (1080, 2347))
    resources = media.resolve_xhs(None, 'https://www.xiaohongshu.com/explore/abc')[-1]
    assert (resources[0]['width'], resources[0]['height']) == (1080, 2347)
    assert not resources[0]['original']
