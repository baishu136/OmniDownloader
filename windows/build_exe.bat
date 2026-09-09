@echo off
chcp 65001 >nul
title OmniDownloader 打包构建中心
cd /d "%~dp0"

:menu
cls
echo ========================================================
echo          OmniDownloader Windows 打包构建中心
echo ========================================================
echo.
echo   [1] 打包 网页纯后台服务版 (推荐: 极速启动、静默后台、体积精简)
echo   [2] 打包 桌面独立客户端版 (WebView2独立窗口体验)
echo   [3] 一键打包 双版本 (全部构建)
echo   [0] 退出
echo.
echo ========================================================
set /p choice=请输入选项编号 (默认为 3): 

if "%choice%"=="" set choice=3
if "%choice%"=="1" goto build_web
if "%choice%"=="2" goto build_desktop
if "%choice%"=="3" goto build_all
if "%choice%"=="0" exit
goto menu

:build_web
echo.
python build_web.py
goto finish

:build_desktop
echo.
python build_desktop.py
goto finish

:build_all
echo.
python build_exe.py --mode all
goto finish

:finish
echo.
echo ========================================================
echo 构建流程结束，发布包位于 release/ 目录。
echo 按任意键退出...
pause >nul
