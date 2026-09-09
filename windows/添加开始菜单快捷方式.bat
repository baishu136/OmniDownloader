@echo off
chcp 65001 >nul
title 添加 OmniDownloader 到开始菜单
echo ========================================================
echo       OmniDownloader 网页版 - 添加到开始菜单常驻
echo ========================================================
echo.
cd /d "%~dp0"

if exist "setup_shortcuts.py" (
    python setup_shortcuts.py --startmenu
) else (
    powershell -NoProfile -Command ^
        "$programs = [Environment]::GetFolderPath('Programs');" ^
        "$group = Join-Path $programs 'OmniDownloader';" ^
        "if (!(Test-Path $group)) { New-Item -ItemType Directory -Path $group -Force | Out-Null };" ^
        "$ws = New-Object -ComObject WScript.Shell;" ^
        "$exe = Join-Path $PSScriptRoot 'OmniDownloader_Web.exe';" ^
        "$ico = Join-Path $PSScriptRoot 'app.ico';" ^
        "$lnk1 = Join-Path $programs 'OmniDownloader 网页版.lnk';" ^
        "$s1 = $ws.CreateShortcut($lnk1);" ^
        "$s1.TargetPath = $exe;" ^
        "$s1.WorkingDirectory = $PSScriptRoot;" ^
        "if (Test-Path $ico) { $s1.IconLocation = \"$ico,0\" };" ^
        "$s1.Description = 'OmniDownloader 全能音视频下载器 (网页版)';" ^
        "$s1.Save();" ^
        "$lnk2 = Join-Path $group 'OmniDownloader 网页版.lnk';" ^
        "$s2 = $ws.CreateShortcut($lnk2);" ^
        "$s2.TargetPath = $exe;" ^
        "$s2.WorkingDirectory = $PSScriptRoot;" ^
        "if (Test-Path $ico) { $s2.IconLocation = \"$ico,0\" };" ^
        "$s2.Description = 'OmniDownloader 全能音视频下载器 (网页版)';" ^
        "$s2.Save();" ^
        "Write-Host '[OK] 开始菜单快捷方式创建成功！' -ForegroundColor Green"
)

echo.
echo ========================================================
echo  已成功常驻于 Windows 开始菜单！
echo  - 随时按 Win 键或点击“开始”菜单直接找到 OmniDownloader
echo  - 也可在 Windows 搜索栏直接键入 "OmniDownloader" 秒开
echo ========================================================
echo.
pause
