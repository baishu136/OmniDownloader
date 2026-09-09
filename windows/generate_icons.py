"""
OmniDownloader 图标生成与处理工具
将用户上传的云朵下载图标转换为多尺寸 Windows .ico 图标与网页 Favicon
并融合 Android 原版 Indigo-Cyan 渐变质感
"""

import os
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

SOURCE_IMAGE_PATH = Path(r"C:\Users\GUDGA\.gemini\antigravity\brain\fef0d399-3f50-465f-81f5-0066c72b481e\.user_uploaded\media_1788748400071.png")
WINDOWS_DIR = Path(__file__).parent.resolve()
STATIC_DIR = WINDOWS_DIR / "web" / "static"
STATIC_DIR.mkdir(parents=True, exist_ok=True)


def create_app_icons():
    if not SOURCE_IMAGE_PATH.exists():
        print(f"[Error] 未找到源图片: {SOURCE_IMAGE_PATH}")
        return

    orig = Image.open(SOURCE_IMAGE_PATH).convert("RGBA")
    w, h = orig.size

    # 1. 提取黑色线稿并转化为带透明度的白色/黑色蒙版
    gray = ImageOps.grayscale(orig)
    # 反相：黑色线条变成高亮 (255)
    inverted = ImageOps.invert(gray)

    # 裁剪到正方形中心
    target_size = 512
    square = Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))

    # 缩放原图保持比例居中
    ratio = min((target_size - 60) / w, (target_size - 60) / h)
    new_w, new_h = int(w * ratio), int(h * ratio)
    resized_mask = inverted.resize((new_w, new_h), Image.Resampling.LANCZOS)

    # 制作纯透明背景下的深色/彩色线稿图标 (logo_raw.png)
    raw_logo = Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))
    paste_x = (target_size - new_w) // 2
    paste_y = (target_size - new_h) // 2

    # 使用 Android 主题色 Indigo (#4F46E5) 填充线条
    indigo_fill = Image.new("RGBA", (new_w, new_h), (79, 70, 229, 255))
    raw_logo.paste(indigo_fill, (paste_x, paste_y), resized_mask)
    raw_logo.save(STATIC_DIR / "logo_line.png")

    # 2. 制作 App 质感图标 (带有安卓 Indigo-Cyan 渐变底的现代图标，极其醒目美观)
    app_icon = Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(app_icon)

    # 绘制平滑圆角矩形
    radius = 110
    # 渐变底色模拟 (Indigo 4F46E5 -> Cyan 0284C7)
    base_layer = Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))
    base_draw = ImageDraw.Draw(base_layer)
    base_draw.rounded_rectangle([16, 16, target_size - 16, target_size - 16], radius=radius, fill=(79, 70, 229, 255))

    # 在顶部叠加轻微青色渐变感
    cyan_gradient = Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))
    cyan_draw = ImageDraw.Draw(cyan_gradient)
    cyan_draw.rounded_rectangle([16, 16, target_size - 16, target_size - 16], radius=radius, fill=(2, 132, 199, 120))
    app_icon = Image.alpha_composite(base_layer, cyan_gradient)

    # 将白色云朵+箭头粘贴到渐变底中间
    white_fill = Image.new("RGBA", (new_w, new_h), (255, 255, 255, 255))
    app_icon.paste(white_fill, (paste_x, paste_y), resized_mask)

    # 保存主 Web Logo
    app_icon.save(STATIC_DIR / "logo.png")
    app_icon.save(WINDOWS_DIR / "web" / "logo.png")

    # 3. 输出 Windows .ico 图标 (内嵌多种标准分辨率)
    ico_sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    app_icon.save(WINDOWS_DIR / "app.ico", format="ICO", sizes=ico_sizes)
    app_icon.save(STATIC_DIR / "favicon.ico", format="ICO", sizes=[(16, 16), (32, 32), (48, 48)])
    app_icon.save(WINDOWS_DIR / "web" / "favicon.ico", format="ICO", sizes=[(16, 16), (32, 32), (48, 48)])

    print(f"[OK] 图标生成成功！")
    print(f"     ICO 文件: {WINDOWS_DIR / 'app.ico'}")
    print(f"     Logo 文件: {STATIC_DIR / 'logo.png'}")
    print(f"     Favicon 文件: {WINDOWS_DIR / 'web' / 'favicon.ico'}")


if __name__ == "__main__":
    create_app_icons()
