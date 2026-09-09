@echo off
chcp 65001 >nul
title 取消 OmniDownloader 开机自启动
echo ========================================================
echo       OmniDownloader 网页版 - 取消开机自启动
echo ========================================================
echo.
cd /d "%~dp0"

if exist "setup_shortcuts.py" (
    python setup_shortcuts.py --autostart off
) else (
    powershell -Command "Remove-Item -Force ([Environment]::GetFolderPath('Startup') + '\OmniDownloader 网页版.lnk') -ErrorAction SilentlyContinue"
    reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "OmniDownloader_Web" /f >nul 2>&1
    reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "OmniDownloaderWeb" /f >nul 2>&1
    echo [OK] 已删除启动项快捷方式与注册表自启项。
)

echo.
echo 开机自启动已取消。
echo.
pause
