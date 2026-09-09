@echo off
cd /d "%~dp0"
start "" http://127.0.0.1:58000
python omni_server.py --host 127.0.0.1 --port 58000
pause
