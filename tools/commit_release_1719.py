"""Publish the validated multi-platform source on the task branch, preserving the real index."""
import os
from pathlib import Path
import subprocess
from commit_ios_ci import ROOT,BRANCH,git

parent=git('rev-parse','refs/heads/'+BRANCH)
env=os.environ.copy();env['GIT_INDEX_FILE']=str(ROOT/'.git/release1719-source.index')
git('read-tree',parent,env=env)
paths=['.gitignore','README.md','app','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle','gradlew','gradlew.bat','release-smoke','shared',
       'windows/core','windows/engine','windows/web','windows/tests','windows/tools','windows/licenses','windows/runtime-lock.json','windows/README.md','windows/THIRD_PARTY_NOTICES.md',
       'docs/PARITY_1.7.19_2026-11-08.md','docs/UPDATE_1.7.19_2026-11-09.md','docs/TEST_RESULTS_1.7.19.json',
       'tools/sync_ios_project.py','tools/sync_client_assets.py','tools/package_python_extras.py','tools/run_android_unit_tests.py','tools/test-classpath.init.gradle',
       'tools/commit_ios_ci.py','tools/commit_release_1719.py','tools/finalize_release_1719.py','tools/migrate_legacy_web_state.py','tools/update_local_web_1719.ps1','tools/verify_windows_package.py']
paths += [str(p.relative_to(ROOT)).replace('\\','/') for p in (ROOT/'windows').glob('*.py')]
paths += ['windows/requirements.txt','windows/requirements-lock.txt','windows/app.ico']
git('add','--',*[p for p in paths if (ROOT/p).exists()],env=env)
git('add','-f','--','app/src/main/assets/engine/python-extras.zip',env=env)
names=git('ls-files',env=env).splitlines()
assert not any(p.startswith(('.local/','.venv/','scratch/')) or p in ('local.properties','windows/config.json','windows/tasks.json') or p.endswith(('.jks','.keystore','.private.json')) for p in names)
tree=git('write-tree',env=env)
sha=git('commit-tree',tree,'-p',parent,'-m','Deliver Android UI adjustments and validated Windows/iOS 1.7.19 sources')
git('update-ref','refs/heads/'+BRANCH,sha,parent)
subprocess.run(['git','push','git@github.com:baishu136/OmniDownloader.git','refs/heads/'+BRANCH+':refs/heads/'+BRANCH],cwd=ROOT,check=True)
print('Multi-platform source commit',sha)
