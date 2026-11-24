"""Validate GIF content independently of names or a server's Content-Type."""
from pathlib import Path


def inspect_gif(path):
    try:
        with Path(path).open('rb') as source:
            if source.read(6) not in (b'GIF87a', b'GIF89a'):
                return None
            size = Path(path).stat().st_size

            def read(n):
                value = source.read(n)
                if len(value) != n:
                    raise ValueError('truncated GIF')
                return value

            def byte(): return read(1)[0]
            def word(): return int.from_bytes(read(2), 'little')
            def skip(n):
                if source.tell() + n > size: raise ValueError('truncated GIF block')
                source.seek(n, 1)
            def blocks():
                total = 0
                while True:
                    n = byte()
                    if not n: return total
                    skip(n); total += n

            width, height, packed = word(), word(), byte()
            if not width or not height: return None
            skip(2)
            if packed & 128: skip(3 * 2 ** ((packed & 7) + 1))
            frames, duration, delay = 0, 0, 0
            while True:
                kind = byte()
                if kind == 0x21:
                    if byte() == 0xf9:
                        if byte() != 4: return None
                        byte(); delay = word() * 10; byte()
                        if byte() != 0: return None
                    else: blocks()
                elif kind == 0x2c:
                    word(); word()
                    if not word() or not word(): return None
                    packed = byte()
                    if packed & 128: skip(3 * 2 ** ((packed & 7) + 1))
                    if not 2 <= byte() <= 8 or blocks() == 0: return None
                    frames += 1; duration += delay; delay = 0
                elif kind == 0x3b:
                    return {'width': width, 'height': height, 'frames': frames, 'durationMs': duration} if frames else None
                else: return None
    except (OSError, ValueError):
        return None


def verify_gif(path):
    if inspect_gif(path) is None:
        raise RuntimeError('[POSTPROCESS_FAILED] GIF 内容无效或不完整，拒绝导出仅改后缀的文件')
    # Decode every frame as well as checking the block stream/trailer.
    from PIL import Image, ImageSequence
    try:
        with Image.open(path) as image:
            if image.format != 'GIF': raise ValueError('not GIF')
            for frame in ImageSequence.Iterator(image): frame.load()
    except (OSError, ValueError, EOFError) as error:
        raise RuntimeError('[POSTPROCESS_FAILED] GIF 帧无法解码') from error
