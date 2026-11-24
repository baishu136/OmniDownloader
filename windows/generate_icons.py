"""
OmniDownloader 统一 Windows 平台图标生成工具
将标准云朵下载图标 (windows/assets/icon_source.png) 裁剪并规范边距后，
统一生成 WinUI 3、Web 服务、桌面托盘与安装包使用的多分辨率 ICO / PNG / Base64 资产。
"""

import base64
import io
import struct
from pathlib import Path
from PIL import Image

ROOT_DIR = Path(__file__).resolve().parent.parent
WINDOWS_DIR = Path(__file__).resolve().parent
ASSETS_DIR = WINDOWS_DIR / "assets"
STATIC_DIR = WINDOWS_DIR / "web" / "static"
WINUI_ASSETS_DIR = WINDOWS_DIR / "winui3" / "src" / "OmniDownloader.WinUI" / "Assets"
SOURCE_IMAGE_PATH = ASSETS_DIR / "icon_source.png"


def _resize_premultiplied(img: Image.Image, size: int) -> Image.Image:
    """使用预乘 Alpha（RGBa）执行高质量 Lanczos 缩放，彻底消除白边黑晕伪影。"""
    rgba = img.convert("RGBA")
    if rgba.size == (size, size):
        return rgba
    return rgba.convert("RGBa").resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")


def _encode_ico_frame_bmp(frame: Image.Image) -> bytes:
    """将 <=128px 图标帧编码为标准 Windows 32bpp BI_RGB DIB + 1bpp AND 掩码。"""
    w, h = frame.size
    rgba = frame.convert("RGBA")
    raw_px = rgba.tobytes("raw", "BGRA")
    row_stride = w * 4
    # DIB 为自底向上行序
    xor_bytes = b"".join(raw_px[y * row_stride : (y + 1) * row_stride] for y in range(h - 1, -1, -1))

    # 1bpp AND 掩码（按 4 字节对齐，自底向上）
    and_stride = ((w + 31) // 32) * 4
    and_rows = []
    px = rgba.load()
    for y in range(h - 1, -1, -1):
        row = bytearray(and_stride)
        for x in range(w):
            if px[x, y][3] == 0:
                row[x >> 3] |= 0x80 >> (x & 7)
        and_rows.append(bytes(row))
    and_bytes = b"".join(and_rows)

    header = struct.pack(
        "<IIIHHIIIIII",
        40,              # biSize
        w,               # biWidth
        h * 2,           # biHeight (XOR + AND)
        1,               # biPlanes
        32,              # biBitCount
        0,               # biCompression (BI_RGB)
        len(xor_bytes) + len(and_bytes),
        0, 0, 0, 0,
    )
    return header + xor_bytes + and_bytes


def _save_windows_ico(master: Image.Image, dest: Path, sizes: list[int]) -> None:
    """生成兼顾传统 Win32 (BMP DIB) 与现代高 DPI (256px PNG) 的标准多分辨率 ICO。"""
    frames_data: list[tuple[int, bytes]] = []
    for sz in sizes:
        frame = _resize_premultiplied(master, sz)
        if sz >= 256:
            buf = io.BytesIO()
            frame.save(buf, format="PNG")
            frames_data.append((sz, buf.getvalue()))
        else:
            frames_data.append((sz, _encode_ico_frame_bmp(frame)))

    directory = bytearray(struct.pack("<HHH", 0, 1, len(frames_data)))
    offset = 6 + 16 * len(frames_data)
    payload = bytearray()
    for sz, data in frames_data:
        dim = 0 if sz >= 256 else sz
        directory.extend(struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset))
        payload.extend(data)
        offset += len(data)
    dest.write_bytes(bytes(directory) + bytes(payload))


def _normalize_square_icon(src: Image.Image, target_size: int = 512, padding_ratio: float = 0.04) -> Image.Image:
    """裁剪外围冗余透明边距，并将图标居中放入带均衡安全留白的正方形画布。"""
    rgba = src.convert("RGBA")
    mask = rgba.getchannel("A").point(lambda a: 255 if a > 12 else 0)
    bbox = mask.getbbox()
    if bbox:
        pad = 6
        x0 = max(0, bbox[0] - pad)
        y0 = max(0, bbox[1] - pad)
        x1 = min(rgba.width, bbox[2] + pad)
        y1 = min(rgba.height, bbox[3] + pad)
        rgba = rgba.crop((x0, y0, x1, y1))

    side = max(rgba.width, rgba.height)
    square_src = Image.new("RGBA", (side, side), (255, 255, 255, 0))
    square_src.paste(rgba, ((side - rgba.width) // 2, (side - rgba.height) // 2), rgba)

    inner = max(16, int(round(target_size * (1.0 - padding_ratio * 2))))
    resized = _resize_premultiplied(square_src, inner)
    canvas = Image.new("RGBA", (target_size, target_size), (255, 255, 255, 0))
    offset = (target_size - inner) // 2
    canvas.paste(resized, (offset, offset), resized)
    return canvas


def create_app_icons():
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    STATIC_DIR.mkdir(parents=True, exist_ok=True)
    WINUI_ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    if not SOURCE_IMAGE_PATH.exists():
        raise FileNotFoundError(f"未找到统一图标源文件: {SOURCE_IMAGE_PATH}")

    raw = Image.open(SOURCE_IMAGE_PATH)
    icon_master = _normalize_square_icon(raw, target_size=1024, padding_ratio=0.04)
    icon_512 = _resize_premultiplied(icon_master, 512)
    icon_256 = _resize_premultiplied(icon_master, 256)
    icon_128 = _resize_premultiplied(icon_master, 128)

    # PNG 输出（Web / 托盘 / WinUI3 标题栏）
    icon_512.save(STATIC_DIR / "logo.png")
    icon_512.save(WINDOWS_DIR / "web" / "logo.png")
    icon_128.save(STATIC_DIR / "logo_128.png")
    icon_128.save(STATIC_DIR / "logo_line.png")
    icon_256.save(WINUI_ASSETS_DIR / "logo.png")

    b64 = base64.b64encode((STATIC_DIR / "logo_128.png").read_bytes()).decode("ascii")
    (STATIC_DIR / "logo_b64.txt").write_text(b64, encoding="utf-8")

    # 多分辨率 Windows ICO 输出（含 Win10/11 全 DPI 常用尺寸）
    app_ico_sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    favicon_sizes = [16, 24, 32, 48, 64]

    _save_windows_ico(icon_master, WINDOWS_DIR / "app.ico", app_ico_sizes)
    _save_windows_ico(icon_master, WINUI_ASSETS_DIR / "app.ico", app_ico_sizes)
    _save_windows_ico(icon_master, STATIC_DIR / "favicon.ico", favicon_sizes)
    _save_windows_ico(icon_master, WINDOWS_DIR / "web" / "favicon.ico", favicon_sizes)

    print("[OK] 已统一生成 Windows 全部应用与网页图标资产：")
    print(f"     Windows ICO: {WINDOWS_DIR / 'app.ico'}")
    print(f"     WinUI3  ICO: {WINUI_ASSETS_DIR / 'app.ico'}")
    print(f"     Web    Logo: {STATIC_DIR / 'logo.png'}")
    print(f"     Web Favicon: {STATIC_DIR / 'favicon.ico'}")


if __name__ == "__main__":
    create_app_icons()
