"""Audit owned UI copy, not user media titles, platform names or raw diagnostics."""
from pathlib import Path
from html.parser import HTMLParser
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CATALOGS = [
    'windows/web/static/strings.json',
    'windows/winui3/src/OmniDownloader.WinUI.Core/Assets/strings.json',
    'OmniDownloader-iOS/OmniDownloader/Resources/strings.json',
]
LANGUAGES = ['zh-CN', 'zh-TW', 'en', 'ja']
NATIVE_LANGUAGE_NAMES = {'简体中文', '繁體中文', '日本語'}
BRANDS = {'哔哩哔哩', '抖音', '快手', '小红书', '微博', '小黑盒', '西瓜视频', '优酷', '爱奇艺', '虎牙'}
HAN = re.compile(r'[\u3400-\u9fff]')
TOKENS = re.compile(r'%%|%[sd@]|\{\d+\}')


def android_tables():
    source = (ROOT / 'app/src/main/java/com/omni/downloader/ui/localization/AppStrings.kt').read_text('utf-8')
    result = {}
    for language, name in zip(LANGUAGES, ['ZhHansStrings', 'ZhHantStrings', 'EnStrings', 'JaStrings']):
        body = re.search(r'val ' + name + r' = appStrings \{(.*?)\n\}', source, re.S)[1]
        result[language] = {key: json.loads(re.sub(r'\\([\x27$])', r'\1', value))
                           for key, value in re.findall(r'^\s+(\w+) = ("(?:[^"\\]|\\.)*")\s*$', body, re.M)}
    return result


def owned_ui_keys():
    keys = set()

    class WebCopy(HTMLParser):
        skipped = False
        def handle_starttag(self, tag, attrs):
            if tag in ('script', 'style'): self.skipped = True
            for name, value in attrs:
                if name in ('placeholder', 'title', 'aria-label') and value and HAN.search(value): keys.add(value)
        def handle_endtag(self, tag):
            if tag in ('script', 'style'): self.skipped = False
        def handle_data(self, value):
            if not self.skipped and HAN.search(value) and len(value.strip()) < 300: keys.add(value.strip())

    def _unescape(raw: str) -> str:
        return (raw.replace('\\\\', '\\').replace('\\n', '\n')
                   .replace('\\r', '\r').replace('\\t', '\t')
                   .replace('\\"', '"').replace("\\'", "'"))

    WebCopy().feed((ROOT / 'windows/web/index.html').read_text('utf-8'))
    for path in (ROOT / 'windows/web/static/js').glob('*.js'):
        text = path.read_text('utf-8')
        for quote, raw_val in re.findall(r"(?:^|[^\w.$])(?:t|toast)\(\s*(['\"])((?:\\.|(?!\1)[^\\\n])+)\1", text):
            value = _unescape(raw_val)
            if HAN.search(value): keys.add(value)
    for path in (ROOT / 'windows/winui3/src/OmniDownloader.WinUI').rglob('*.xaml'):
        if not path.is_file() or {'bin', 'obj'} & set(path.parts): continue
        for element in ET.parse(path).iter():
            for attribute, value in element.attrib.items():
                if 'Localized.' in attribute and HAN.search(value): keys.add(value)
    for path in (ROOT / 'windows/winui3/src').rglob('*.cs'):
        if not path.is_file() or {'bin', 'obj'} & set(path.parts): continue
        text = path.read_text('utf-8')
        for raw_val in re.findall(r'(?:\bLoc\.T|\.T|\bT|\.Format|Notify)\(\s*"((?:\\.|[^"\\\n])+)"', text):
            value = _unescape(raw_val)
            if HAN.search(value): keys.add(value)
    for path in (ROOT / 'OmniDownloader-iOS/OmniDownloader').rglob('*.swift'):
        text = path.read_text('utf-8')
        for value in re.findall(r'(?:L|OmniLocalization\.text|OmniLocalization\.fmt|Text|Button|Toggle|Label|Section|navigationTitle|alert)\(\s*"([^"\n\\]+)"', text):
            if HAN.search(value): keys.add(value)
        for value in re.findall(r'OmniPlatform\.error\(\s*"[^"\n]*"\s*,\s*"([^"\n\\]+)"', text):
            if HAN.search(value): keys.add(value)
        for value in re.findall(r'YouTubeCookies\.failure\(\s*"([^"\n\\]+)"', text):
            body = re.sub(r'^\[[A-Z0-9_]+\]\s*', '', value).strip()
            if HAN.search(body): keys.add(body)
        # Catch localized literals inside ternary expressions e.g. OmniLocalization.text(cond ? "A" : "B")
        for line in text.splitlines():
            if any(tok in line for tok in ('OmniLocalization.text(', 'OmniLocalization.fmt(', 'OmniPlatform.error(')):
                for value in re.findall(r'"([^"\n\\]+)"', line):
                    if HAN.search(value) and not value.startswith('['):
                        keys.add(value)
    return keys - BRANDS - NATIVE_LANGUAGE_NAMES


def owned_progress_keys():
    keys = set()
    for relative in ['app/src/main/java/com/omni/downloader/engine', 'windows/engine',
                     'OmniDownloader-iOS/OmniDownloader/Media', 'OmniDownloader-iOS/OmniDownloader/Services']:
        for path in (ROOT / relative).rglob('*'):
            if path.suffix not in ('.kt', '.py', '.swift'): continue
            for value in re.findall(r'[\x22\x27]([^\x22\x27\n]*?)[\x22\x27]', path.read_text('utf-8')):
                if value.startswith(('正在', '准备下载', '等待下载', '自动切换', '核验原作品', '检测到 HLS', '刷新同一媒体地址')):
                    if '$' not in value and '\\(' not in value and '{' not in value: keys.add(value)
    return keys


def audit():
    errors = []
    static_keys = owned_ui_keys()
    progress_keys = owned_progress_keys()
    keys = static_keys | progress_keys
    counts = {}
    for relative in CATALOGS:
        catalog = json.loads((ROOT / relative).read_text('utf-8'))
        counts[relative] = len(catalog['zh-CN'])
        for language in LANGUAGES:
            missing = keys - set(catalog[language])
            errors.extend(f'{relative} [{language}] missing: {key}' for key in sorted(missing))
            for source, translated in catalog[language].items():
                if not translated: errors.append(f'{relative} [{language}] empty: {source}')
                if language == 'en' and source not in NATIVE_LANGUAGE_NAMES and re.search(r'[\u3400-\u9fff\u3040-\u30ff]', translated):
                    errors.append(f'{relative} [en] foreign script: {source}')
                if sorted(TOKENS.findall(source)) != sorted(TOKENS.findall(translated)):
                    errors.append(f'{relative} [{language}] placeholders: {source}')
        for language, expected in zip(LANGUAGES, [('视频', '纯画面', '音频', 'GIF'), ('視頻', '純畫面', '音訊', 'GIF'), ('Video', 'Video only', 'Audio', 'GIF'), ('動画', '映像のみ', '音声', 'GIF')]):
            if tuple(catalog[language][key] for key in ('视频', '纯画面', '音频', 'GIF')) != expected:
                errors.append(f'{relative} [{language}] inconsistent download labels')
    tables = android_tables()
    for language, table in tables.items():
        if set(table) != set(tables['zh-CN']): errors.append(f'Android [{language}] fields differ')
        for field, value in table.items():
            if language == 'en' and not field.startswith('language') and re.search(r'[\u3400-\u9fff\u3040-\u30ff]', value): errors.append(f'Android [en] foreign script: {field}')
            if sorted(TOKENS.findall(tables['zh-CN'][field])) != sorted(TOKENS.findall(value)):
                errors.append(f'Android [{language}] placeholders: {field}')
    return {'ownedUiKeys': len(static_keys), 'ownedProgressKeys': len(progress_keys), 'androidFieldsPerLanguage': len(tables['en']), 'catalogs': counts, 'errors': errors,
            'scope': 'Static application UI keys, locale fields and format placeholders. Media metadata, native language names, brands and raw diagnostic details retain their original language. Runtime/manual review is separate.'}


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(); parser.add_argument('--report', type=Path); args = parser.parse_args()
    report = audit()
    if args.report: args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(bool(report['errors']))
