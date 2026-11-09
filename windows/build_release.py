"""Build versioned outputs without stopping or replacing a running older release."""
import argparse
from pathlib import Path
import subprocess
import sys
import zipfile
from tools.prepare_runtime import prepare

BASE = Path(__file__).resolve().parent
VERSION = '1.7.19'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['desktop', 'web', 'all'], default='all')
    mode = parser.parse_args().mode
    prepare()
    for kind, name, entry in [('desktop', 'OmniDownloader_Desktop', 'omni_app.py'), ('web', 'OmniDownloader_Web', 'omni_tray.py')]:
        if mode not in ('all', kind): continue
        command = [sys.executable, '-m', 'PyInstaller', '--noconfirm', '--windowed', '--name', name,
                   '--distpath', str(BASE / 'dist' / VERSION), '--workpath', str(BASE / 'build' / VERSION / kind),
                   '--specpath', str(BASE / 'build' / VERSION / kind), '--paths', str(BASE),
                   '--add-data', f'{BASE / "web"};web', '--add-data', f'{BASE / "bin"};bin',
                   '--collect-all', 'yt_dlp_ejs', '--collect-all', 'yt_dlp', '--collect-all', 'websockets', '--copy-metadata', 'yt-dlp', '--copy-metadata', 'yt-dlp-ejs',
                   '--hidden-import', 'yt_dlp', '--hidden-import', 'uvicorn.logging', '--hidden-import', 'uvicorn.loops.auto',
                   '--hidden-import', 'uvicorn.protocols.http.auto', '--hidden-import', 'uvicorn.protocols.websockets.auto']
        if (BASE / 'app.ico').is_file(): command += ['--icon', str(BASE / 'app.ico'), '--add-data', f'{BASE / "app.ico"};.']
        command += [str(BASE / entry)]
        subprocess.run(command, cwd=BASE, check=True)
        result = BASE / 'dist' / VERSION / name
        import shutil
        for document in ('runtime-lock.json', 'THIRD_PARTY_NOTICES.md', 'README.md'):
            if (BASE / document).is_file(): shutil.copy2(BASE / document, result / document)
        if (BASE / 'licenses').is_dir(): shutil.copytree(BASE / 'licenses', result / 'licenses', dirs_exist_ok=True)
        (result / '使用说明.txt').write_text(
            f'OmniDownloader {VERSION}\n双击{name}.exe启动。\n设置中导入对应平台Netscape Cookie文件；凭据由Windows用户DPAPI保护。\n'
            '最高画质按实际可访问源排序，不保证账户可取得4K/8K。\n充电专属视频需要对应账户权益；试看不会作为完整成品。\n'
            '小黑盒动态页面请使用桌面独立版辅助浏览器，播放目标后手动确认捕获媒体。\nPixiv支持Ugoira MP4/GIF/帧ZIP；独立图文页支持抖音、快手、B站动态、Pixiv、小红书、X、Pinterest的可访问图片及ZIP。\n'
            '故障任务可重试；导出失败会保留已验证成品。用户数据位于%LOCALAPPDATA%\\OmniDownloader。\n', encoding='utf-8')
        release = BASE / 'release'; release.mkdir(exist_ok=True)
        with zipfile.ZipFile(release / f'{name}-{VERSION}.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for file in result.rglob('*'):
                if file.is_file(): archive.write(file, str(file.relative_to(result.parent)))
        print('Built', result)


if __name__ == '__main__': main()
