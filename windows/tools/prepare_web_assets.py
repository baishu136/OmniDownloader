"""Compile the existing Tailwind theme and vendor pinned icons for offline EXE/Web use."""
from pathlib import Path
import re
import shutil
import subprocess
import requests

BASE = Path(__file__).resolve().parents[1]


def main():
    page = BASE / 'web/index.html'
    source = page.read_text('utf-8')
    match = re.search(r'<script>\s*tailwind\.config\s*=\s*(.*?)</script>', source, re.S)
    configuration = BASE / 'tailwind.config.cjs'
    if match:
        configuration.write_text('module.exports = ' + match[1].strip().rstrip(';') + ';\n'
                                 'module.exports.content = ["./web/index.html", "./web/static/*.js"];\n', encoding='utf-8')
    assert configuration.is_file()
    style = BASE / 'web/theme-input.css'
    style.write_text('@tailwind base;\n@tailwind components;\n@tailwind utilities;\n', encoding='utf-8')
    npm = shutil.which('npm.cmd') or shutil.which('npm')
    if not npm:
        raise RuntimeError('Node/npm is needed only when building the local CSS assets')
    subprocess.run([npm, 'exec', '--yes', '--package=tailwindcss@3.4.17', '--', 'tailwindcss',
                    '-c', str(configuration), '-i', str(style), '-o', str(BASE/'web/static/theme.css'), '--minify'],
                   cwd=BASE, check=True)
    vendor = BASE / 'web/static/vendor/fontawesome'
    css = vendor / 'css/all.min.css'
    css.parent.mkdir(parents=True, exist_ok=True)
    client = requests.Session()
    base = 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/'
    if not css.is_file():
        response = client.get(base+'css/all.min.css', timeout=30); response.raise_for_status(); css.write_bytes(response.content)
    for relative in set(re.findall(r'url\(\.\./(webfonts/[^)]+)\)', css.read_text('utf-8'))):
        target = vendor / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.is_file():
            response = client.get(base+relative, timeout=30); response.raise_for_status(); target.write_bytes(response.content)
    license_file = vendor / 'LICENSE.txt'
    if not license_file.is_file():
        response = client.get('https://raw.githubusercontent.com/FortAwesome/Font-Awesome/6.4.0/LICENSE.txt', timeout=30)
        response.raise_for_status(); license_file.write_bytes(response.content)
    source = source.replace('<script src="https://cdn.tailwindcss.com"></script>', '<link rel="stylesheet" href="/static/theme.css">')
    if match: source = source.replace(match[0], '')
    source = source.replace('https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css', '/static/vendor/fontawesome/css/all.min.css')
    page.write_text(source, encoding='utf-8')
    print('Offline theme and Font Awesome 6.4.0 prepared')


if __name__ == '__main__': main()
