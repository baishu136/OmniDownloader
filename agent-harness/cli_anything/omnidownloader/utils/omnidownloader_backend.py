"""Transport to the real OmniDownloader server; never retries mutations."""
import os
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

import requests


class BackendError(RuntimeError):
    pass


def redact(value):
    if isinstance(value, dict):
        return {k: ("***" if "cookie" in k.lower() and v else redact(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


class Backend:
    def __init__(self, server, timeout=30, dry_run=False):
        parsed = urlsplit(server)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.query or parsed.fragment or parsed.username:
            raise BackendError("Server must be an HTTP(S) URL without credentials, query or fragment")
        self.server = server.rstrip("/")
        self.timeout = timeout
        self.dry_run = dry_run
        self.http = requests.Session()
        # Local API must not accidentally be sent through a system proxy.
        self.http.trust_env = False

    def close(self):
        self.http.close()

    def _request(self, method, path, **kwargs):
        try:
            response = self.http.request(method, self.server + path, timeout=self.timeout, **kwargs)
        except requests.RequestException as exc:
            raise BackendError(f"Cannot reach OmniDownloader at {self.server}. Start the Windows/Web service or set --server. {type(exc).__name__}") from exc
        if not response.ok:
            try:
                detail = response.json().get("detail", response.reason)
            except (ValueError, AttributeError):
                detail = response.reason
            response.close()
            raise BackendError(f"HTTP {response.status_code}: {detail}")
        return response

    def request(self, method, path, payload=None, params=None, *, read_only=False):
        if self.dry_run and method != "GET" and not read_only:
            return redact({"dryRun": True, "method": method, "path": path, "body": payload, "params": params})
        with self._request(method, path, json=payload, params=params) as response:
            try:
                return redact(response.json())
            except ValueError as exc:
                raise BackendError("Server returned non-JSON data; check --server points to OmniDownloader") from exc

    def fetch(self, path, output):
        target = Path(output).resolve()
        if target.exists():
            raise BackendError(f"Output already exists: {target}")
        if self.dry_run:
            return {"dryRun": True, "path": path, "output": str(target)}
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with self._request("GET", path, stream=True) as response:
                with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as handle:
                    temporary = Path(handle.name)
                    for chunk in response.iter_content(1024 * 1024):
                        handle.write(chunk)
            size = temporary.stat().st_size
            if not size:
                raise BackendError("Server returned an empty file")
            # Atomic publication without replacing an existing destination.
            os.link(temporary, target)
            return {"output": str(target), "bytes": size}
        except (OSError, requests.RequestException) as exc:
            raise BackendError(f"File transfer failed: {exc}") from exc
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
