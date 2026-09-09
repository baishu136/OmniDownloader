@echo off
chcp 65001 >nul
title 打包 OmniDownloader 桌面独立客户端版
echo ========================================================
echo   OmniDownloader - 桌面独立客户端版 一键打包工具
echo ========================================================
echo.
cd /d "%~dp0"
python build_desktop.py
echo.
echo 打包完成，请按任意键退出...
pause >nul
