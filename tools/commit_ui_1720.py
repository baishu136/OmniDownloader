"""Commit the UI revision on its own branch without changing the user's index or checkout."""
import os
import subprocess
import sys
from commit_ios_ci import ROOT,git

BRANCH='codex/ui-1.7.20'
try: parent=git('rev-parse','--verify','refs/heads/'+BRANCH);previous=parent
except subprocess.CalledProcessError:
    parent=git('rev-parse','refs/heads/codex/ios-1.7.19');previous='0'*40
env=os.environ.copy();env['GIT_INDEX_FILE']=str(ROOT/'.git/ui1720.index')
git('read-tree',parent,env=env)
paths=['OmniDownloader-iOS','.github/workflows/ios-1.7.20.yml','shared/icons',
       'windows/web','windows/app.ico','windows/build_release.py','windows/omni_server.py',
       'windows/licenses/MaterialIcons-Apache-2.0.txt','windows/README.md','windows/THIRD_PARTY_NOTICES.md',
       'tools/prepare_ui_assets.py','tools/sync_ios_project.py','tools/commit_ui_1720.py',
       'tools/update_local_web_1720.ps1','tools/verify_windows_package.py','tools/finalize_ui_1720.py','README.md',
       'README_ZH.md','README_ZH_TW.md','README_JA.md','docs/RELEASE_1.7.20.md',
       'docs/UI_1.7.20_2026-11-09.md']
git('add','--',*[p for p in paths if (ROOT/p).exists()],env=env)
tree=git('write-tree',env=env)
sha=git('commit-tree',tree,'-p',parent,'-m',sys.argv[1] if len(sys.argv)>1 else 'Restore PC styling and align iOS icons and buttons with Android')
git('update-ref','refs/heads/'+BRANCH,sha,previous)
subprocess.run(['git','push','git@github.com:baishu136/OmniDownloader.git','refs/heads/'+BRANCH+':refs/heads/'+BRANCH],cwd=ROOT,check=True)
print('UI source commit',sha)
