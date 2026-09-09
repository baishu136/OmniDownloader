@echo off
chcp 65001 >nul
title 设置 OmniDownloader 网页版开机自启动
echo ========================================================
echo       OmniDownloader 网页版 - 设置开机自启动
echo ========================================================
echo.
cd /d "%~dp0"

if exist "dist\OmniDownloader_Web\OmniDownloader_Web.exe" (
    python setup_shortcuts.py --autostart on
) else if exist "OmniDownloader_Web.exe" (
    powershell -Command "$s=(New-Object -COM WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Startup') + '\OmniDownloader 网页版.lnk'); $s.TargetPath='%~dp0OmniDownloader_Web.exe'; $s.WorkingDirectory='%~dp0'; $s.Arguments='--autostart'; $s.IconLocation='%~dp0app.ico,0'; $s.Save()"
    reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "OmniDownloader_Web" /t REG_SZ /d "\"%~dp0OmniDownloader_Web.exe\" --autostart" /f >nul 2>&1
    echo [OK] 已成功为当前发布包配置开机自启动！
) else (
    python setup_shortcuts.py --autostart on
)

echo.
echo 开机自启配置完成！电脑开机后将自动在后台静默运行并常驻托盘。
echo 随时在浏览器输入 http://127.0.0.1:58000 即可秒开使用。
echo.
pause
