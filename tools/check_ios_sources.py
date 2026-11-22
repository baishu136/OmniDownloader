"""Host-side Swift grammar/project and browser-script fixtures; NOT an iOS SDK build.

Dev-only requirements: tree-sitter==0.26.0, tree-sitter-swift==0.7.4; Node.js.
"""
from pathlib import Path
import json
import re
import subprocess
import tree_sitter
import tree_sitter_swift

ROOT = Path(__file__).resolve().parents[1]
files = list((ROOT / 'OmniDownloader-iOS/OmniDownloader').rglob('*.swift'))
parser = tree_sitter.Parser(tree_sitter.Language(tree_sitter_swift.language()))
for file in files:
    assert not parser.parse(file.read_bytes()).root_node.has_error, file
project = (ROOT / 'OmniDownloader-iOS/OmniDownloader.xcodeproj/project.pbxproj').read_text('utf-8')
for path in re.findall(r'path = "(OmniDownloader/[^"]+)";', project):
    assert (ROOT / 'OmniDownloader-iOS' / path).is_file(), path
ids = re.findall(r'^\s*([A-F0-9]{24}) /\*.*?\*/ = \{', project, re.M)
assert len(ids) == len(set(ids)), 'Duplicate project IDs'
source = (ROOT / 'OmniDownloader-iOS/OmniDownloader/Engines/YouTubeBrowser.swift').read_text('utf-8')
script = source.split('private static let script = #"""', 1)[1].split('"""#', 1)[0]
test = r'''
const vm = require('node:vm'), assert = require('node:assert/strict');
const script = SCRIPT;
const media='https://r1---sn-fixture.googlevideo.com/videoplayback?id=fixture&itag=137&clen=100';
function run(mode) {
  const messages=[];
  const format={itag:137,mimeType:'video/mp4; codecs="avc1"',contentLength:'100',width:1920,height:1080,url:media};
  if(mode==='cipher'){delete format.url;format.signatureCipher=new URLSearchParams({url:media,s:'undecoded-fixture'}).toString();}
  const player={videoDetails:{videoId:'fixture0000',title:'fixture',author:'fixture',lengthSeconds:'10'},playabilityStatus:{status:mode==='auth'?'LOGIN_REQUIRED':'OK'},streamingData:{adaptiveFormats:[format]}};
  if(mode==='drm')player.streamingData.drmFamilies=['WIDEVINE'];
  const window={ytInitialPlayerResponse:player,webkit:{messageHandlers:{omniYouTube:{postMessage:m=>messages.push(m)}}},fetch:()=>Promise.resolve()};
  function XHR(){};XHR.prototype.open=()=>{};
  const sandbox={window,document:{getElementById:()=>null,querySelector:()=>mode==='ad'?{}:null},location:{href:'https://m.youtube.com/watch?v=fixture0000'},URL,URLSearchParams,Map,XMLHttpRequest:XHR,PerformanceObserver:class{observe(){}},setInterval:fn=>fn()};
  vm.runInNewContext(script,sandbox);
  if(mode==='cipher')window.fetch(media+'&range=0-10');
  return messages;
}
assert.equal(run('normal').at(-1).formats.length,1);
assert.equal(run('normal').at(-1).formats[0].bytes,'100');
assert.equal(run('cipher').at(-1).formats[0].reference,media);
assert.equal(run('auth').at(-1).error,'LOGIN_REQUIRED');
assert.equal(run('ad').length,0);
assert.equal(run('drm').length,0);
console.log('YouTube browser script: 5 fixtures passed');
'''.replace('SCRIPT', json.dumps(script))
result = subprocess.run(['node', '-e', test], capture_output=True, text=True, check=True)
print(f'{len(files)} Swift files parsed; project paths and IDs verified')
print(result.stdout.strip())
print('Apple SDK compilation, signing and iPhone execution: NOT RUN on Windows')
