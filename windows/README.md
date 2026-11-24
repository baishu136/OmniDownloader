# OmniDownloader Windows 客户端与后台服务

Windows 10/11 (x64) 桌面方案由原生 **.NET 8 WinUI 3 Fluent 客户端**（`OmniDownloader_WinUI.exe`）与内置 **FastAPI 共享后台服务**（`OmniDownloader_Web.exe`）组成，亦可单独使用独立 Web 后台版。两种形态共用下载队列、配置及加密凭据；完整便携包免预先安装 Python、FFmpeg 或 yt-dlp（[WinUI 3 工程说明](winui3/README.md)）。

## 使用

- **整合便携包**：下载并完整解压 `OmniDownloader-Windows-x64.zip` 后，双击根目录 `OmniDownloader_WinUI.exe` 启动；请保留同目录 DLL、运行时文件及 `OmniDownloader_Web` 子目录。
- **独立 Web 版**：下载并完整解压 `OmniDownloader_Web.zip` 后，双击 `OmniDownloader_Web.exe` 启动本地后台服务并在浏览器中使用（默认仅监听 `http://127.0.0.1:58000`）。
- 独立 Edge 隔离登录窗口与抖音自动网页补全需要本机安装 Microsoft Edge；客户端首次启动提供引导配置（自定义主题色、视频/图片含 GIF/音频分类保存目录、网页服务开关）。

## 界面和功能

- **客户端与网页版共享引擎**：
  - 客户端包含首页解析、批量下载、下载任务管理、分层设置（主题配色/语言、组件管理、下载与存储、网络代理、中转站、账号与 Cookie 等）及内置原生视频播放器与图片查看器。
  - 网页版提供单条/批量下载、进度管理与深浅色主题，视觉配色自动跟随客户端设置；当客户端关闭「网页服务」时，网页端口访问将被安全拦截，并在退出主程序时同步结束全部关联后台进程。
  - 原生 Win32 系统托盘提供「显示窗口」「拉起网页版（开启网页服务时显示）」「退出程序」，关闭窗口时支持弹窗选择退出或最小化到托盘并记住偏好。
- **平台与格式支持**：
  - 音画合流 (`MP4`)、纯画面、高保真音频 (`MP3`/`M4A`/`FLAC`/`OPUS`)、真实双向 `GIF` ↔ `MP4` 转换、高清封面、HDR 轮廓标示、合集多选与 X 多视频。
  - 图文原图选择、预览与 ZIP 打包；Pixiv Ugoira 原始帧、MP4 与 GIF。
- **凭据与数据安全**：
  - 设置 → 账号 → 选择平台 → 浏览器登录 / 导入 Netscape Cookie；登录 Profile 独立保存在 `%LOCALAPPDATA%/OmniDownloader/login-profiles`，凭据与媒体历史采用 Windows 用户 DPAPI 加密存储，接口不回传 Cookie 明文。
  - 下载前根据分类目录规则解析实际存储路径；导出前执行媒体内容校验，导出失败时保留已验证缓存以便免重下重试。

## 限制

- 最高源画质按目标作品实际可访问流排序，不等同于平台原始上传母片；会员、付费/充电权益、地区与 DRM 限制不会被绕过。
- 验证码及部分强交互动态页面需要用户在内置浏览器或隔离登录窗口中手动确认。
- 网页版与客户端共用 `%LOCALAPPDATA%/OmniDownloader`；如需同时迁移或更新正在运行的后台，请先从托盘正常退出旧服务，再重连新版本，避免直接覆盖运行中的二进制文件。

## 开发与打包

开发运行环境为 Python 3.12（依赖锁定于 `requirements-lock.txt`）与 .NET 8 / Windows App SDK。本地开发调试后台可运行 `python -m uvicorn omni_server:app --app-dir windows --host 127.0.0.1 --port 58000`。

```powershell
.venv\Scripts\python.exe -m pytest windows/tests -q
.venv\Scripts\python.exe tools/audit_localization.py
.venv\Scripts\python.exe tools/build_unified_windows.py
```

样式资产构建依赖 Node/npm，最终发布包不依赖任何开发工具。`runtime-lock.json` 锁定 Deno 与 FFmpeg 的官方归档及 SHA-256 校验，构建前自动校验并在缺失时恢复。测试请使用 `OMNI_STATE_DIR` 与 `OMNI_PORT` 指定隔离目录和端口，避免影响本机真实配置与任务记录。
