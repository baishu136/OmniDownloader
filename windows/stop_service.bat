@echo off
chcp 65001 >nul
title 停止 OmniDownloader 后台服务
echo 正在停止 OmniDownloader 后台服务 (端口 58000)...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":58000" ^| findstr "LISTENING"') do (
    echo 发现后台进程 PID: %%a，正在终止...
    taskkill /F /PID %%a >nul 2>&1
)
echo 后台服务已完全停止。
timeout /t 2 >nul
