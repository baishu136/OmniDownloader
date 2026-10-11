"""Validate against unmodified backend source from Git in a temporary directory."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time

import requests


def main():
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix="omni-cli-e2e-") as directory:
        root = Path(directory)
        entries = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", "HEAD", "windows"], cwd=repo, text=True).splitlines()
        for entry in entries:
            if entry.endswith(".py") and (entry == "windows/omni_server.py" or entry.startswith(("windows/core/", "windows/engine/"))):
                target = root / Path(entry).relative_to("windows")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(subprocess.check_output(["git", "show", f"HEAD:{entry}"], cwd=repo))
        if not (root / "omni_server.py").exists():
            raise RuntimeError("Git HEAD has no Windows backend. Set OMNI_TEST_SERVER and run pytest directly.")
        (root / "config.json").write_text(json.dumps({"download_dir": str(root / "downloads"), "proxy_url": ""}), encoding="utf-8")
        with socket.socket() as bound:
            bound.bind(("127.0.0.1", 0))
            port = bound.getsockname()[1]
        env = {**os.environ, "PYTHONIOENCODING": "utf-8", "CLI_ANYTHING_FORCE_INSTALLED": "1", "OMNI_TEST_SERVER": f"http://127.0.0.1:{port}"}
        # Log stream is a file, avoiding subprocess-pipe deadlocks during downloads.
        with (root / "backend.log").open("w", encoding="utf-8") as log:
            server = subprocess.Popen([sys.executable, str(root / "omni_server.py"), "--port", str(port)], cwd=root, env=env, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    if server.poll() is not None:
                        raise RuntimeError((root / "backend.log").read_text(encoding="utf-8"))
                    try:
                        if requests.get(env["OMNI_TEST_SERVER"] + "/api/tasks", timeout=1, proxies={"http": "", "https": ""}).ok:
                            break
                    except requests.RequestException:
                        pass
                    time.sleep(.1)
                else:
                    raise RuntimeError("Original server did not become ready")
                result = subprocess.run([sys.executable, "-m", "pytest", str(tests), "-v", "--tb=no", "-s"], env=env, capture_output=True, text=True, encoding="utf-8")
                print(result.stdout)
                if result.returncode:
                    print(result.stderr)
                    print((root / "backend.log").read_text(encoding="utf-8"))
                else:
                    with (tests / "TEST.md").open("a", encoding="utf-8") as report:
                        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
                        report.write(f"\n## Passing results\n\nBackend: unmodified Windows Python files from Git {commit}, isolated temporary service.\nCommand: `python agent-harness/cli_anything/omnidownloader/tests/run_isolated.py`.\nInstalled command required; local FFmpeg-generated MP4 downloaded through the real yt-dlp engine.\nArtifacts use pytest temporary directories; backend temporary files are cleaned on exit.\n\n```text\n" + result.stdout + "\n```\n")
                return result.returncode
            finally:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
