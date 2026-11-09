"""Keep shared capture code and Android UI translations in the native/web clients."""
from pathlib import Path
import json
import re

ROOT=Path(__file__).resolve().parents[1]


def main():
    source=(ROOT/'app/src/main/java/com/omni/downloader/ui/localization/AppStrings.kt').read_text('utf-8')
    values={}
    for key,literal in re.findall(r'^\s+(\w+) = ("(?:[^"\\]|\\.)*"),?\s*$',source,re.M):
        try: values.setdefault(key,[]).append(json.loads(literal))
        except ValueError: continue
    translations={language:{} for language in ['zh-CN','zh-TW','en','ja']}
    for rows in values.values():
        if len(rows)==4:
            for language,value in zip(translations,rows): translations[language][rows[0]]=value
    extras={'图文':['圖文','Images','画像'],'首页':['首頁','Home','ホーム'],'任务':['任務','Tasks','タスク'],
        '设置':['設定','Settings','設定'],'返回':['返回','Back','戻る'],'关闭':['關閉','Close','閉じる'],
        'Cookie 与登录管理':['Cookie 與登入管理','Cookies and sign-in management','Cookie とログイン管理'],
        '原图':['原圖','Original','元画像'],'选择原图':['選擇原圖','Select originals','元画像を選択'],
        '下载选中图片':['下載選取圖片','Download selected images','選択した画像を保存'],
        '导出 ZIP':['匯出 ZIP','Export ZIP','ZIP を書き出す']}
    for base,rows in extras.items():
        translations['zh-CN'][base]=base
        for language,value in zip(['zh-TW','en','ja'],rows):translations[language][base]=value
    payload=json.dumps(translations,ensure_ascii=False,separators=(',',':'))+'\n'
    for path in ['OmniDownloader-iOS/OmniDownloader/Resources/strings.json','windows/web/static/strings.json']:
        dest=ROOT/path;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(payload,encoding='utf-8')
    script=(ROOT/'shared/browser/target-capture.js').read_bytes()
    for path in ['OmniDownloader-iOS/OmniDownloader/Resources/target-capture.js','windows/web/static/target-capture.js']:(ROOT/path).write_bytes(script)
    app=ROOT/'OmniDownloader-iOS/OmniDownloader/Models/AppStrings.swift'
    text=app.read_text('utf-8')
    text=re.sub(r'public let (\w+) = ("(?:[^"\\]|\\.)*")',lambda m:('public var '+m[1]+': String { '+('"1.7.19"' if m[1]=='version' else 'OmniLocalization.text('+m[2]+')')+' }'),text)
    app.write_text(text,encoding='utf-8')
    print('Shared capture and',len(translations['en']),'Android translation values synchronized')


if __name__=='__main__':main()
