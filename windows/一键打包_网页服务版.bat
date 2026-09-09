@echo off
chcp 65001 >nul
title 打包 OmniDownloader 网页纯后台版
echo ========================================================
echo   OmniDownloader - 网页纯后台服务版 一键打包工具
echo ========================================================
echo.
cd /d "%~dp0"
python build_web.py
echo.
echo 打包完成，请按任意键退出...
pause >nul
