# OmniDownloader WinUI 3 原生桌面客户端

基于 **.NET 8 + Windows App SDK 1.6 (WinUI 3)** 构建的 Windows 原生桌面客户端工程，采用 **Fluent Design**（Mica/Acrylic 窗口材质、Windows 系统主题与系统强调色跟随、`NavigationView` 导航架构、`BreadcrumbBar` 分层设置及系统原生文件/目录选择器），并与既有 Python 核心引擎（[windows/omni_server.py](../omni_server.py)）通过本机 HTTP REST API 与 SSE 实时事件流无缝协作。

---

## 目录结构

- [OmniDownloader.WinUI.sln](OmniDownloader.WinUI.sln)：解决方案文件，包含以下 3 个项目：
  - `src/OmniDownloader.WinUI.Core/`：纯 .NET 8 核心业务逻辑（DTO 模型、链接提取与去重、画质策略构建、任务合集聚合、API/SSE 客户端、4 语本地化字典、本地服务进程探测与伴随启动管理器）。
  - `src/OmniDownloader.WinUI/`：WinUI 3 原生界面工程（首页解析、图文预览网格与全屏灯箱、格式与合集弹窗、批量下载、任务管理、分层设置及内置 WebView2 辅助浏览器提取窗口）。
  - `tests/OmniDownloader.WinUI.Tests/`：xUnit 单元测试工程，验证模型反序列化、画质策略、批量链接提取、合集分组、多语言格式化与本地状态持久化。
- [build_winui.ps1](build_winui.ps1)：一键测试与构建脚本。

---

## 核心特性

1. **Windows 原生外观与系统集成**：
   - 自动启用 Windows 11 Mica 材质（在 Windows 10 自动优雅回落至 Acrylic 或系统主题背景）。
   - 按钮、进度条、导航选中状态直接跟随 Windows 系统强调色（`SystemAccentColor`），支持「跟随系统 / 浅色 / 深色」及「纯黑背景（AMOLED）」。
   - 原生调用系统 `FolderPicker` / `FileOpenPicker` 选择保存目录、导入 TXT 链接表与 Netscape `cookies.txt`。
2. **复用现有核心引擎与用户数据**：
   - 默认优先探测并复用本机已在 `127.0.0.1:58000` 运行的 OmniDownloader 服务（与 Web 版共享 `%LOCALAPPDATA%\OmniDownloader` 配置、Cookie 会话及任务历史）。
   - 若本机服务未运行且启用自动伴随，客户端将静默后台拉起仓库中的 `omni_server.py`，并在窗口关闭时安全清理自身伴随子进程。
3. **完整功能保留 + 内置 WebView2 辅助提取**：
   - 涵盖 22 个主流平台及 1600+ `yt-dlp` 解析器目录、多分辨率与策略选择、合集多选/分P切换、伴随封面保存、图文多选与键盘翻页预览、批量智能识别与 TXT 导入、任务 SSE 实时进度与合集折叠卡、13 平台 Cookie 会话隔离仓库、B站 SESSDATA 快捷配置与第三方中转站管理。
   - 若连接的纯服务模式未挂载 `pywebview`，点击「打开辅助浏览器」将自动弹出客户端内置的 WinUI 3 `WebView2` 辅助捕获窗口。

---

## 构建与启动 EXE 程序

在 PowerShell 中执行以下命令（将运行全部单元测试，并将自包含、可直接双击运行的 `OmniDownloader_WinUI.exe` 及其全部依赖发布到 [windows/dist/OmniDownloader_WinUI/](../dist/OmniDownloader_WinUI/) 目录，**不会修改或替换**本机正在运行的 Web 版）：

```powershell
powershell -ExecutionPolicy Bypass -File "windows\winui3\build_winui.ps1"
```

如需构建完成后直接启动 `OmniDownloader_WinUI.exe`：

```powershell
powershell -ExecutionPolicy Bypass -File "windows\winui3\build_winui.ps1" -RunApp
```
