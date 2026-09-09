@echo off
chcp 65001 >nul
title OmniDownloader 深度清洁与重置助手

echo ========================================================
echo        OmniDownloader 深度清洁与重置工具 (ADB版)
echo ========================================================
echo.
echo 正在检测连接的安卓设备...
adb devices
echo.

where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [提示] 您的电脑环境变量中未检测到 adb 命令。
    echo 如果您没有安装 Android SDK / ADB，建议直接在手机上：
    echo 1. 长按桌面的旧版应用图标选择【卸载】；
    echo 2. 或安装随附的 OmniCleaner-debug.apk 进行一键清理。
    echo.
    pause
    exit /b
)

echo 请选择清理模式:
echo [1] 完全卸载旧版、清除残留缓存并全新安装最新 v1.4.6 (推荐)
echo [2] 仅清除手机中的 OmniDownloader 下载碎片文件 (.part / .ytdl)
echo [3] 彻底卸载 OmniDownloader 与 OmniCleaner (不重新安装)
echo [4] 仅安装最新版 OmniDownloader-v1.4.6-debug.apk
echo [0] 退出
echo.
set /p choice="请输入数字 (1-4, 0): "

if "%choice%"=="1" goto clean_and_install
if "%choice%"=="2" goto clean_debris
if "%choice%"=="3" goto uninstall_all
if "%choice%"=="4" goto install_only
if "%choice%"=="0" exit /b
goto invalid

:clean_and_install
echo.
echo [1/4] 正在卸载旧版 com.omni.downloader (清理私有数据和旧版解压环境)...
adb uninstall com.omni.downloader
echo [2/4] 正在清理存储中的中断碎片文件...
adb shell "rm -rf /sdcard/Download/OmniDownloader/*.part /sdcard/Download/OmniDownloader/*.ytdl /sdcard/Download/OmniDownloader/*.tmp 2>/dev/null"
echo [3/4] 正在全新安装最新 OmniDownloader-v1.4.6-debug.apk...
adb install -r OmniDownloader-v1.4.6-debug.apk
echo.
echo [4/4] 安装完成！v1.4.6 已修复合集下载弹窗吸底操作栏与下载按键显示！
echo.
pause
exit /b

:clean_debris
echo.
echo 正在清理 /sdcard/Download/OmniDownloader 目录下的临时下载碎片...
adb shell "rm -rf /sdcard/Download/OmniDownloader/*.part /sdcard/Download/OmniDownloader/*.ytdl /sdcard/Download/OmniDownloader/*.tmp 2>/dev/null"
echo 碎片清理完毕！已下载完成的完整视频未受任何影响。
echo.
pause
exit /b

:uninstall_all
echo.
echo 正在彻底卸载 OmniDownloader 与 OmniCleaner...
adb uninstall com.omni.downloader
adb uninstall com.omni.cleaner
echo 卸载完成。
echo.
pause
exit /b

:install_only
echo.
echo 正在安装 OmniDownloader-v1.4.6-debug.apk...
adb install -r OmniDownloader-v1.4.6-debug.apk
echo.
pause
exit /b

:invalid
echo 无效选项，请重新运行。
pause
