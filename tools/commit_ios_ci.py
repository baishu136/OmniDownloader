"""Commit only reviewed iOS/CI sources without touching the user's index or checkout."""
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
BRANCH='codex/ios-1.7.19'
def git(*args,**kwargs):
    return subprocess.check_output(['git',*args],cwd=ROOT,**kwargs).decode().strip()

if __name__=='__main__':
    try: parent=git('rev-parse','--verify','refs/heads/'+BRANCH); previous=parent
    except subprocess.CalledProcessError:
        git('fetch','origin','main');parent=git('rev-parse','refs/remotes/origin/main');previous='0'*40
    env=os.environ.copy();env['GIT_INDEX_FILE']=str(ROOT/'.git/ios1719-ci.index')
    git('read-tree',parent,env=env)
    git('add','--','OmniDownloader-iOS','.github/workflows/ios-1.7.19.yml','.github/workflows/ios-artifact-review.yml','shared/browser/target-capture.js','tools/sync_ios_project.py','tools/sync_client_assets.py',env=env)
    tree=git('write-tree',env=env)
    sha=git('commit-tree',tree,'-p',parent,'-m',sys.argv[1] if len(sys.argv)>1 else 'Build iOS 1.7.19 with Android page structure and native media pipeline')
    git('update-ref','refs/heads/'+BRANCH,sha,previous)
    print(sha)
    subprocess.run(['git','push','git@github.com:baishu136/OmniDownloader.git','refs/heads/'+BRANCH+':refs/heads/'+BRANCH],cwd=ROOT,check=True)
