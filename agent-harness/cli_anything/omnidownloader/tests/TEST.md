# Test plan (written before test implementation)

## Inventory

- test_core.py: approximately 18 unit cases using Click runner and a transport double.
- test_full_e2e.py: approximately 5 installed-command tests against the real original backend.

## Unit plan

Backend: invalid server URLs, connection/HTTP/non-JSON errors, cookie redaction,
dry-run without mutation, empty transfers, no overwrite and cleanup.
Tasks: missing IDs, completed/failed/cancelled/timeout waiting.
CLI: help, validation, JSON errors, request mapping, dry-run, REPL quit.

## Real E2E plan

OMNI_TEST_SERVER must point to an isolated original Windows server; missing backend fails tests.
Use installed console script via _resolve_cli and CLI_ANYTHING_FORCE_INSTALLED=1.
Inspect status/settings, update settings and verify persistence, manage relay entries,
analyze a local generated MP4, create a real download task, wait, fetch output.
Verify MP4 bytes and ffmpeg-decodable frames, not only successful exit codes.
Check dry-run adds no task and errors have nonzero exit status with parseable JSON.

## Workflows

1. Settings/relay roundtrip: get → modify → inspect → remove, isolate configuration.
2. Real media: generate MP4 via ffmpeg → serve local file → original API analyze →
   download through original yt-dlp → wait → fetch → verify container and decoded video.

## Coverage boundaries

No Android/iOS GUI roundtrip. No live Bilibili/TikTok authentication or public-site stability test.
No GIF/audio/cover conversion validation in this suite; those remain original-backend behavior.
No GUI automation; REPL and CLI cover the API control surface.

## Passing results

Backend: unmodified Windows Python files from Git 5868b9048383ad6a89df142d23e84208d5fbed7b, isolated temporary service.
Command: `python agent-harness/cli_anything/omnidownloader/tests/run_isolated.py`.
Installed command required; local FFmpeg-generated MP4 downloaded through the real yt-dlp engine.
Artifacts use pytest temporary directories; backend temporary files are cleaned on exit.

```text
============================= test session starts =============================
platform win32 -- Python 3.13.0, pytest-9.1.1, pluggy-1.6.0 -- C:\Users\LiuTw\AppData\Local\Programs\Python\Python313\python.exe
cachedir: .pytest_cache
rootdir: D:\projects\OmniDownloader\agent-harness
plugins: anyio-4.12.1
collecting ... collected 31 items

agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[ftp://host] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://user:pass@host] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://host?q=x] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://host/#x] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_redact_nested PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_dry_run_no_request PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_connection_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_http_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_non_json PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_failure[FAILED] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_failure[CANCELLED] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_completed PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_timeout PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_missing_and_encoded_id PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_dry_run_payload PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args0] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args1] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args2] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args3] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args4] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_main_json_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_fetch_dry_run_and_no_overwrite PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_fetch_empty_cleanup PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_default_repl_quit PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_status_and_settings [_resolve_cli] Using installed command: C:\Users\LiuTw\AppData\Local\Programs\Python\Python313\Scripts\cli-anything-omnidownloader.EXE
PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_relay_roundtrip PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_dry_run_no_task PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_missing_task_json_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_real_download_fetch 
  MP4: C:\Users\LiuTw\AppData\Local\Temp\pytest-of-LiuTw\pytest-1\test_real_download_fetch0\fetched.mp4 (2,246 bytes)
PASSED

============================= 31 passed in 9.28s ==============================

```

## Passing results

Backend: unmodified Windows Python files from Git 5868b9048383ad6a89df142d23e84208d5fbed7b, isolated temporary service.
Command: `python agent-harness/cli_anything/omnidownloader/tests/run_isolated.py`.
Installed command required; local FFmpeg-generated MP4 downloaded through the real yt-dlp engine.
Artifacts use pytest temporary directories; backend temporary files are cleaned on exit.

```text
============================= test session starts =============================
platform win32 -- Python 3.13.0, pytest-9.1.1, pluggy-1.6.0 -- C:\Users\LiuTw\AppData\Local\Programs\Python\Python313\python.exe
cachedir: .pytest_cache
rootdir: D:\projects\OmniDownloader\agent-harness
plugins: anyio-4.12.1
collecting ... collected 31 items

agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[ftp://host] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://user:pass@host] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://host?q=x] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://host/#x] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_redact_nested PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_dry_run_no_request PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_connection_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_http_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_non_json PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_failure[FAILED] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_failure[CANCELLED] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_completed PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_timeout PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_missing_and_encoded_id PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_dry_run_payload PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args0] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args1] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args2] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args3] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args4] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_main_json_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_fetch_dry_run_and_no_overwrite PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_fetch_empty_cleanup PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_default_repl_quit PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_status_and_settings [_resolve_cli] Using installed command: C:\Users\LiuTw\AppData\Local\Programs\Python\Python313\Scripts\cli-anything-omnidownloader.EXE
PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_relay_roundtrip PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_dry_run_no_task PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_missing_task_json_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_real_download_fetch 
  MP4: C:\Users\LiuTw\AppData\Local\Temp\pytest-of-LiuTw\pytest-2\test_real_download_fetch0\fetched.mp4 (2,246 bytes)
PASSED

============================= 31 passed in 7.22s ==============================

```

## Passing results

Backend: unmodified Windows Python files from Git 5868b9048383ad6a89df142d23e84208d5fbed7b, isolated temporary service.
Command: `python agent-harness/cli_anything/omnidownloader/tests/run_isolated.py`.
Installed command required; local FFmpeg-generated MP4 downloaded through the real yt-dlp engine.
Artifacts use pytest temporary directories; backend temporary files are cleaned on exit.

```text
============================= test session starts =============================
platform win32 -- Python 3.13.0, pytest-9.1.1, pluggy-1.6.0 -- C:\Users\LiuTw\AppData\Local\Programs\Python\Python313\python.exe
cachedir: .pytest_cache
rootdir: D:\projects\OmniDownloader\agent-harness
plugins: anyio-4.12.1
collecting ... collected 34 items

agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[ftp://host] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://user:pass@host] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://host?q=x] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_server_validation[http://host/#x] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_redact_nested PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_dry_run_no_request PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_connection_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_http_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_non_json PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_failure[FAILED] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_failure[CANCELLED] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_completed PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_wait_timeout PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_missing_and_encoded_id PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_dry_run_payload PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args0] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args1] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args2] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args3] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cli_invalid_arguments[args4] PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_main_json_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_fetch_dry_run_and_no_overwrite PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_fetch_empty_cleanup PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_default_repl_quit PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_analysis_available_in_dry_run PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_clear_all_dry_run_lists_cancels PASSED
agent-harness\cli_anything\omnidownloader\tests\test_core.py::test_cookie_file_redacted_in_dry_run PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_status_and_settings [_resolve_cli] Using installed command: C:\Users\LiuTw\AppData\Local\Programs\Python\Python313\Scripts\cli-anything-omnidownloader.EXE
PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_relay_roundtrip PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_dry_run_no_task PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_missing_task_json_error PASSED
agent-harness\cli_anything\omnidownloader\tests\test_full_e2e.py::test_real_download_fetch 
  MP4: C:\Users\LiuTw\AppData\Local\Temp\pytest-of-LiuTw\pytest-3\test_real_download_fetch0\fetched.mp4 (2,246 bytes)
PASSED

============================= 34 passed in 7.00s ==============================

```
