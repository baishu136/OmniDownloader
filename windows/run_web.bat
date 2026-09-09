@echo off
chcp 65001 >nul
cd /d "%~dp0"
if exist "dist\OmniDownloader_Web\OmniDownloader_Web.exe" (
    start "" "dist\OmniDownloader_Web\OmniDownloader_Web.exe"
) else (
    python omni_tray.py
)

