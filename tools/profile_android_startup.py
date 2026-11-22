"""Measure an installed APK by force-stopping the test app; optionally capture Perfetto."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--label', required=True)
parser.add_argument('--samples', type=int, default=3)
parser.add_argument('--trace', action='store_true')
parser.add_argument('--output', type=Path, default=Path('.local/startup-profile'))
args = parser.parse_args()
if not re.fullmatch(r'[\w-]+', args.label) or not 1 <= args.samples <= 10:
    parser.error('label must be a simple name and samples must be between 1 and 10')
adb_path = shutil.which('adb') or str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe')
adb = [adb_path, '-s', args.serial]
package = 'com.omni.downloader'
activity = package + '/.ui.MainActivity'
args.output.mkdir(parents=True, exist_ok=True)

def run(*command):
    return subprocess.run(adb + list(command), capture_output=True, text=True, check=True, timeout=45).stdout

def launch(kind, *flags):
    output = run('shell', 'am', 'start', '-W', *flags, '-n', activity)
    if 'Status: ok' not in output:
        raise RuntimeError(output)
    values = dict(re.findall(r'^(\w+): (.+)$', output, re.MULTILINE))
    return {'kind': kind, 'fields': values, 'output': output}

environment = {key: run('shell', 'getprop', prop).strip() for key, prop in (
    ('sdk', 'ro.build.version.sdk'), ('abi', 'ro.product.cpu.abi'), ('model', 'ro.product.model'))}
environment['serial'] = args.serial
environment['version'] = re.findall(r'version(?:Code|Name)=[^\r\n]+', run('shell', 'dumpsys', 'package', package))
(args.output / f'{args.label}-environment.json').write_text(json.dumps(environment, indent=2), encoding='utf-8')

rows = []
for index in range(args.samples):
    run('shell', 'am', 'force-stop', package)
    rows.append(launch('cold'))
    time.sleep(2)
    (args.output / f'{args.label}-frames-{index}.txt').write_text(
        run('shell', 'dumpsys', 'gfxinfo', package, 'framestats'), encoding='utf-8')
for _ in range(args.samples):
    before = run('shell', 'pidof', package).strip()
    row = launch('warm', '-f', '0x14000000')
    row['same_process'] = before == run('shell', 'pidof', package).strip()
    rows.append(row)
    time.sleep(.5)
run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
rows.append(launch('resume'))
(args.output / f'{args.label}-timings.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')

if args.trace:
    run('shell', 'am', 'force-stop', package)
    remote = f'/data/misc/perfetto-traces/omni-{args.label}.perfetto-trace'
    trace = subprocess.Popen(adb + ['shell', 'perfetto', '-o', remote, '-t', '6s',
                                   'sched', 'freq', 'idle', 'am', 'wm', 'gfx', 'view'],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(.5)
    launch('trace')
    stdout, stderr = trace.communicate(timeout=15)
    (args.output / f'{args.label}-trace.log').write_bytes(stdout + stderr)
    if trace.returncode:
        raise RuntimeError('Perfetto failed; see trace log')
    run('pull', remote, str(args.output / f'{args.label}.perfetto-trace'))
print(json.dumps(rows))
