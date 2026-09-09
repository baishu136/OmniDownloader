"""
OmniDownloader 桌面独立客户端版 (Desktop App) 独立自动化打包脚本
特点：
1. 打包原生独立桌面应用 (omni_app.py + pywebview)；
2. 包含系统 WebView2 / Edge 内核通信与窗口依赖；
3. 自动生成 release/OmniDownloader_桌面独立版.zip 免安装便携压缩包。
"""

import os
import sys
import shutil
import zipfile
import subprocess
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()
DIST_DIR = BASE_DIR / "dist" / "OmniDownloader_Desktop"
RELEASE_DIR = BASE_DIR / "release"


def kill_running_process():
    """清理可能占用的旧进程"""
    if sys.platform == "win32":
        try:
            subprocess.run(
                ["taskkill", "/F", "/IM", "OmniDownloader_Desktop.exe", "/T"],
                capture_output=True,
                check=False
            )
        except Exception:
            pass


def ensure_ffmpeg():
    """确保 bin/ffmpeg.exe 存在"""
    bin_dir = BASE_DIR / "bin"
    bin_dir.mkdir(exist_ok=True)
    target_ffmpeg = bin_dir / "ffmpeg.exe"

    if not target_ffmpeg.exists() or target_ffmpeg.stat().st_size == 0:
        print("[1/4] 正在内置独立 FFmpeg 编解码组件...")
        try:
            import imageio_ffmpeg
            src = imageio_ffmpeg.get_ffmpeg_exe()
            shutil.copy2(src, target_ffmpeg)
            print(f"      FFmpeg 就绪: {target_ffmpeg} ({target_ffmpeg.stat().st_size / (1024*1024):.1f} MB)")
        except Exception as e:
            print(f"      [警告] 提取 FFmpeg 失败: {e}")
    else:
        print(f"[1/4] 检测到已存在内置 FFmpeg 组件 ({target_ffmpeg.stat().st_size / (1024*1024):.1f} MB)")


def clean_previous_build():
    """安全清理历史构建目录"""
    kill_running_process()
    if DIST_DIR.exists():
        try:
            shutil.rmtree(DIST_DIR)
        except Exception as e:
            print(f"      [清理警告] 移除旧目录失败: {e}")


def run_pyinstaller():
    print("[2/4] 执行 PyInstaller 打包构建 (桌面独立客户端版)...")
    clean_previous_build()

    entry_file = str(BASE_DIR / "omni_app.py")
    icon_file = BASE_DIR / "app.ico"

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm",
        "--clean",
        "--name", "OmniDownloader_Desktop",
        "--windowed",
        "--add-data", f"{BASE_DIR / 'web'};web",
        "--add-data", f"{BASE_DIR / 'bin'};bin",
    ]

    if icon_file.exists():
        cmd.extend(["--icon", str(icon_file)])
        cmd.extend(["--add-data", f"{icon_file};."])

    # 包含的依赖模块
    cmd.extend([
        "--hidden-import", "uvicorn.logging",
        "--hidden-import", "uvicorn.loops",
        "--hidden-import", "uvicorn.loops.auto",
        "--hidden-import", "uvicorn.protocols",
        "--hidden-import", "uvicorn.protocols.http",
        "--hidden-import", "uvicorn.protocols.http.auto",
        "--hidden-import", "uvicorn.protocols.websockets",
        "--hidden-import", "uvicorn.protocols.websockets.auto",
        "--hidden-import", "fastapi",
        "--hidden-import", "fastapi.staticfiles",
        "--hidden-import", "yt_dlp",
        "--hidden-import", "webview",
        "--hidden-import", "clr_loader",
        "--hidden-import", "pythonnet",
    ])

    # 剔除无用的大型库
    cmd.extend([
        "--exclude-module", "pystray",
        "--exclude-module", "matplotlib",
        "--exclude-module", "scipy",
        "--exclude-module", "pandas",
        "--exclude-module", "torch",
        "--exclude-module", "pytest",
    ])

    cmd.append(entry_file)

    print(f"      构建命令: {' '.join(cmd)}")
    proc = subprocess.run(cmd, cwd=str(BASE_DIR))
    if proc.returncode != 0:
        raise RuntimeError("PyInstaller 构建过程异常退出")


def add_helper_files():
    """在打包产物目录中补充多国语言使用说明与便捷脚本"""
    print("[3/4] 补充多国语言便携说明与便捷启动脚本...")
    
    # 1. 简体中文说明
    readme_zh = DIST_DIR / "使用说明_简体中文.txt"
    readme_zh.write_text(
        "OmniDownloader 桌面独立客户端版 · 使用说明 (简体中文)\n"
        "====================================================\n\n"
        "【快速上手】\n"
        "1. 双击运行 'OmniDownloader_Desktop.exe'（或 '启动客户端.bat'）。\n"
        "2. 程序会调起系统内置 WebView2 / Edge 现代化独立桌面窗口，界面清新流畅。\n"
        "3. 在搜索框粘贴视频或分享链接（支持哔哩哔哩多P/合集、抖音去水印、快手、小红书、TikTok、YouTube 等），点击【解析】选择画质下载。\n"
        "4. 关闭独立客户端窗口即可完全退出程序，干净无残留。\n\n"
        "【B站 4K / 1080P60 极清画质解锁方法】\n"
        "B站官方接口对未登录游客限制最高仅 480P。配置大会员账户的 SESSDATA 即可免费解锁 4K：\n"
        "1. 电脑浏览器打开 bilibili.com 并登录；\n"
        "2. 按键盘 F12 打开开发者工具，切换到【Application / 存储】-> 左侧展开【Cookies】-> 点击 https://www.bilibili.com；\n"
        "3. 找到 SESSDATA 项并复制其 Value 值；\n"
        "4. 在本软件点击顶部【系统设置】-> 粘贴到【B站登录凭证】并保存即可！\n",
        encoding="utf-8"
    )

    # 2. 繁體中文說明
    readme_zh_tw = DIST_DIR / "使用說明_繁體中文.txt"
    readme_zh_tw.write_text(
        "OmniDownloader 桌面獨立客戶端版 · 使用說明 (繁體中文)\n"
        "====================================================\n\n"
        "【快速上手】\n"
        "1. 雙擊執行 'OmniDownloader_Desktop.exe'（或 '啟動客戶端.bat'）。\n"
        "2. 程式會喚起系統內建 WebView2 / Edge 引擎，展現獨立桌面操作視窗。\n"
        "3. 複製 Bilibili、抖音、快手、小紅書、TikTok、YouTube 等影音連結，點擊【貼上】後按【解析】即可自選畫質下載。\n"
        "4. 關閉獨立視窗即完全退出程式，純淨無殘留。\n\n"
        "【B站 4K / 1080P60 超高畫質解鎖教學】\n"
        "1. 電腦瀏覽器開啟 bilibili.com 並登入帳號；\n"
        "2. 按 F12 開啟開發者工具，點選【Application / 儲存】->【Cookies】-> 找到 SESSDATA；\n"
        "3. 複製 SESSDATA 的 Value 數值；\n"
        "4. 回到本軟體【系統設定】貼上並儲存，立即解鎖 4K 極致畫質！\n",
        encoding="utf-8"
    )

    # 3. English Instructions
    readme_en = DIST_DIR / "Instructions_English.txt"
    readme_en.write_text(
        "OmniDownloader Desktop Standalone App · User Guide (English)\n"
        "============================================================\n\n"
        "【Quick Start】\n"
        "1. Double-click 'OmniDownloader_Desktop.exe' (or '启动客户端.bat').\n"
        "2. The app launches a modern standalone desktop window powered by Microsoft Edge WebView2.\n"
        "3. Paste any video or playlist link (Bilibili, Douyin, TikTok, YouTube, Xiaohongshu, Kuaishou, Twitter/X), click 'Parse', and select your resolution to download.\n"
        "4. Close the window to cleanly and completely exit the application.\n\n"
        "【Unlocking Bilibili 4K & 1080P60 with SESSDATA】\n"
        "1. Log in to bilibili.com in your PC browser;\n"
        "2. Press F12 -> Go to 'Application' -> 'Cookies' -> 'https://www.bilibili.com';\n"
        "3. Copy the Value of 'SESSDATA';\n"
        "4. Go to 'Settings' tab in OmniDownloader, paste into 'Bilibili Cookie', and save!\n",
        encoding="utf-8"
    )

    # 4. 日本語取扱説明書
    readme_ja = DIST_DIR / "説明書_日本語.txt"
    readme_ja.write_text(
        "OmniDownloader デスクトップ独立版 · 取扱説明書 (日本語)\n"
        "============================================================\n\n"
        "【クイックスタート】\n"
        "1. 'OmniDownloader_Desktop.exe' をダブルクリックして起動します。\n"
        "2. Microsoft Edge WebView2 を利用した独立したウィンドウが立ち上がります。\n"
        "3. 動画URL（Bilibili、TikTok、YouTube、Twitter/X、小紅書等）を貼り付け、【解析】をクリックしてお好みの画質でダウンロードを開始します。\n"
        "4. ウィンドウを閉じるとプロセスが完全に終了します。\n\n"
        "【Bilibili 4K 最高画質の解放方法】\n"
        "ブラウザで bilibili.com にログイン後、F12キーを押して「Application」->「Cookies」から「SESSDATA」の値をコピーし、本アプリの【設定】に貼り付けて保存してください。\n",
        encoding="utf-8"
    )

    # 默认通用中文说明 txt 兼容历史
    readme_txt = DIST_DIR / "使用说明.txt"
    readme_txt.write_text(readme_zh.read_text(encoding="utf-8"), encoding="utf-8")

    # 5. 多语言通用离线 HTML 说明手册
    guide_html = DIST_DIR / "多语言说明手册.html"
    guide_html.write_text(
        "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'><title>OmniDownloader Desktop User Manual</title>"
        "<style>body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;max-width:860px;margin:40px auto;padding:0 20px;line-height:1.6;color:#1e293b;background:#f8fafc}"
        ".card{background:#fff;padding:24px;border-radius:16px;box-shadow:0 4px 6px -1px rgba(0,0,0,0.1);margin-bottom:24px;border:1px solid #e2e8f0}"
        "h1{color:#4f46e5}h2{color:#0284c7;border-bottom:2px solid #e0e7ff;padding-bottom:8px}code{background:#f1f5f9;padding:2px 6px;border-radius:6px;color:#0f172a}"
        "</style></head><body><h1>OmniDownloader 桌面独立版使用手册 · Multilingual Manual</h1>"
        "<div class='card'><h2>🇨🇳 简体中文使用说明</h2><p>双击 <code>OmniDownloader_Desktop.exe</code> 打开独立客户端。支持 B站4K/合集、抖音免水印、TikTok、YouTube等全平台直连下载。在设置中填入 B站 SESSDATA 凭证即可解锁 4K 高清。</p></div>"
        "<div class='card'><h2>🇭🇰 繁體中文使用說明</h2><p>雙擊 <code>OmniDownloader_Desktop.exe</code> 開啟獨立客戶端。支援各大主流影音平台極速下載與無損音訊提取。</p></div>"
        "<div class='card'><h2>🇺🇸 English User Guide</h2><p>Double-click <code>OmniDownloader_Desktop.exe</code> to launch the standalone desktop app. Paste any video link, choose your preferred format (4K/1080P/Audio), and download instantly.</p></div>"
        "<div class='card'><h2>🇯🇵 日本語マニュアル</h2><p><code>OmniDownloader_Desktop.exe</code> をダブルクリックして起動します。Bilibili、TikTok、YouTubeなどの高画質動画・音声を即座に保存できます。</p></div>"
        "</body></html>",
        encoding="utf-8"
    )

    start_bat = DIST_DIR / "启动客户端.bat"
    start_bat.write_text(
        "@echo off\n"
        "cd /d \"%~dp0\"\n"
        "start \"\" \"OmniDownloader_Desktop.exe\"\n"
        "exit\n",
        encoding="gbk"
    )


def package_to_zip():
    """打包发布 ZIP 归档文件"""
    print("[4/4] 正在将产物打包归档为 ZIP 发布包...")
    RELEASE_DIR.mkdir(exist_ok=True)
    zip_path = RELEASE_DIR / "OmniDownloader_桌面独立版.zip"

    if zip_path.exists():
        try:
            zip_path.unlink()
        except Exception:
            pass

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in DIST_DIR.rglob("*"):
            if file.is_file():
                arcname = file.relative_to(DIST_DIR.parent)
                zf.write(file, arcname)

    size_mb = zip_path.stat().st_size / (1024 * 1024)
    print("\n" + "=" * 65)
    print("【桌面独立客户端版打包成功】")
    print(f"产物目录: {DIST_DIR}")
    print(f"发布压缩包: {zip_path} ({size_mb:.1f} MB)")
    print("=" * 65 + "\n")


def main():
    print("=== OmniDownloader 桌面独立客户端版打包工程开始 ===")
    ensure_ffmpeg()
    run_pyinstaller()
    add_helper_files()
    package_to_zip()


if __name__ == "__main__":
    main()
