"""Execute the real translation module with a small DOM contract (no browser needed)."""
import json
from pathlib import Path
import shutil
import subprocess
import pytest


def test_language_round_trip_preserves_source_keys_metadata_and_accessible_labels():
    node = shutil.which('node')
    if not node: pytest.skip('Node.js is required for the web localization contract')
    root = Path(__file__).resolve().parents[2]
    script = r'''
import fs from 'node:fs';
import assert from 'node:assert/strict';
const root = ROOT;
function text(value, metadata = false) {
  return {textContent:value, parentElement:{tagName:'SPAN', closest:()=>metadata ? {} : null}};
}
const nav=text('下载'), video=text('视频'), size=text('自动体积');
const original=text('Video',true), chineseOriginal=text('视频',true);
const attributes={'aria-label':'切换到深色'};
const control={closest:()=>null,hasAttribute:k=>k in attributes,getAttribute:k=>attributes[k],setAttribute:(k,v)=>attributes[k]=v};
const body={nodes:[nav,video,size,original,chineseOriginal],querySelectorAll:()=>[control]};
globalThis.document={body,documentElement:{},dispatchEvent:()=>{},createTreeWalker:root=>{
  let i=-1;return{currentNode:null,nextNode(){this.currentNode=root.nodes[++i];return !!this.currentNode;}};
}};
globalThis.NodeFilter={SHOW_TEXT:4};
globalThis.CustomEvent=class{constructor(type,options){this.type=type;this.detail=options?.detail;}};
globalThis.window={};
const storage=new Map([['omni.language','en']]);
globalThis.localStorage={getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)};
globalThis.fetch=async()=>({ok:true,json:async()=>JSON.parse(fs.readFileSync(root+'/windows/web/static/strings.json','utf8'))});
const source=fs.readFileSync(root+'/windows/web/static/js/i18n.js');
const translations=await import('data:text/javascript;base64,'+source.toString('base64'));
await translations.initI18n();
assert.equal(nav.textContent,'Download');assert.equal(video.textContent,'Video');
assert.equal(size.textContent,'Estimated size unavailable');
assert.equal(attributes['aria-label'],'Switch to dark theme');
assert.equal(translations.t('共 %d 张',3),'3 images');
translations.setLanguage('zh-TW');
assert.equal(nav.textContent,'下載');assert.equal(video.textContent,'視頻');
assert.equal(attributes['aria-label'],'切換到深色');
translations.setLanguage('ja');
assert.equal(video.textContent,'動画');assert.equal(nav.textContent,'ダウンロード');
translations.setLanguage('zh-CN');
assert.equal(nav.textContent,'下载');assert.equal(video.textContent,'视频');
assert.equal(original.textContent,'Video');assert.equal(chineseOriginal.textContent,'视频');
console.log('Localization round trip, metadata exclusion and accessible labels: PASS');
'''.replace('ROOT', json.dumps(str(root)))
    result = subprocess.run([node, '--input-type=module', '-'], input=script, text=True,
                            encoding='utf-8', capture_output=True, timeout=20)
    assert result.returncode == 0, result.stdout + result.stderr
