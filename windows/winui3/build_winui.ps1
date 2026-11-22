<#
.SYNOPSIS
    构建并发布独立可双击启动的 OmniDownloader WinUI 3 原生桌面客户端 EXE 目录（不替换本机正在运行的 Web 版）
#>
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [string]$PublishDir = "",
    [switch]$RunApp
)

$ErrorActionPreference = "Stop"
$WinUiRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$WindowsDir = Split-Path -Parent $WinUiRoot
if ([string]::IsNullOrWhiteSpace($PublishDir)) {
    $PublishDir = Join-Path $WindowsDir "dist\OmniDownloader_WinUI"
}

Write-Host "[1/4] 停止可能已打开的旧 OmniDownloader_WinUI 窗口..."
Get-Process -Name "OmniDownloader_WinUI","OmniDownloader.WinUI" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 400

Write-Host "[2/4] 运行 Core 逻辑单元测试..."
dotnet test "$WinUiRoot\tests\OmniDownloader.WinUI.Tests\OmniDownloader.WinUI.Tests.csproj" -c $Configuration -v minimal
if ($LASTEXITCODE -ne 0) { throw "单元测试失败" }

Write-Host "[3/4] 发布自包含 WinUI 3 启动程序目录 ($Configuration | win-x64 -> $PublishDir)..."
if (Test-Path $PublishDir) {
    Remove-Item -LiteralPath $PublishDir -Recurse -Force
}
New-Item -ItemType Directory -Path $PublishDir -Force | Out-Null

dotnet publish "$WinUiRoot\src\OmniDownloader.WinUI\OmniDownloader.WinUI.csproj" `
    -c $Configuration `
    -r win-x64 `
    -p:Platform=x64 `
    --self-contained true `
    -p:WindowsAppSDKSelfContained=true `
    -o $PublishDir `
    -v minimal
if ($LASTEXITCODE -ne 0) { throw "WinUI 3 发布打包失败" }

@'
OmniDownloader WinUI 3 原生桌面客户端 (v1.7.60)
==================================================

1. 启动方式
   直接双击运行当前目录下的：
     OmniDownloader_WinUI.exe

2. 运行与数据说明
   - 本程序内置 .NET 8 与 Windows App SDK 自包含运行环境，双击即可直接打开原生桌面窗口。
   - 如本机已有 OmniDownloader 核心/网页服务在运行（默认 127.0.0.1:58000），客户端将直接连接并共用现有下载队列；
     若本机服务尚未启动，客户端会自动静默拉起同仓库内嵌 Python 核心引擎并在窗口关闭时自动清理。
   - 用户配置、Cookie 会话及任务历史与 Web 版完全共享（存储于 %LOCALAPPDATA%\OmniDownloader）。
'@ | Set-Content -Path (Join-Path $PublishDir "使用说明.txt") -Encoding UTF8

$ExePath = Join-Path $PublishDir "OmniDownloader_WinUI.exe"
if (-not (Test-Path $ExePath)) { throw "未找到发布输出文件: $ExePath" }

Write-Host "[4/4] 构建发布完成! 启动程序: $ExePath" -ForegroundColor Green

if ($RunApp) {
    Start-Process -FilePath $ExePath -WorkingDirectory $PublishDir
}
