"""Move the previous bundled Web state into the user-bound v3 store, retaining backups."""
import json
import os
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
backup=Path(sys.argv[1]).resolve()
assert backup.is_relative_to(ROOT/'.local/backups')
before=json.loads((backup/'before.private.json').read_text(encoding='utf-8-sig'))
rows=before['tasks']
if len(rows)==1 and isinstance(rows[0],list):rows=rows[0]
state=Path(os.environ['LOCALAPPDATA'])/'OmniDownloader';state.mkdir(exist_ok=True)
os.environ['OMNI_STATE_DIR']=str(state)
sys.path.insert(0,str(ROOT/'windows'))
from core.session_store import protect
from core.models import DownloadTask
records={}
file=state/'tasks.json'
if file.is_file():
    raw=file.read_bytes();existing=json.loads(protect(raw[6:],decrypt=True) if raw.startswith(b'OMNI3:') else raw)
    records.update({item['id']:item for item in existing})
for row in rows:
    value=DownloadTask.model_validate(row).model_dump(by_alias=True)
    records.setdefault(value['id'],value)
temporary=state/'tasks.migrating.tmp';temporary.write_bytes(b'OMNI3:'+protect(json.dumps(list(records.values()),ensure_ascii=False).encode('utf-8')));temporary.replace(file)
config=state/'config.json';settings=json.loads(config.read_text(encoding='utf-8')) if config.exists() else {}
mapping={'downloadDir':'download_dir','proxyUrl':'proxy_url','maxConcurrentTasks':'max_concurrent_tasks','theme':'theme','relaySites':'relay_sites','bilibiliCookie':'bilibili_cookie','hasShownRelayCompatTip':'has_shown_relay_compat_tip'}
for source,destination in mapping.items():
    if source in before['settings']:settings[destination]=before['settings'][source]
temporary=state/'config.migrating.tmp';temporary.write_text(json.dumps(settings,ensure_ascii=False,indent=2),encoding='utf-8');temporary.replace(config)
print('Migrated',len(records),'validated history records; retained preferences and original backups')
