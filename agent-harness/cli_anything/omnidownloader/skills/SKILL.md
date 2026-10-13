---
name: cli-anything-omnidownloader
description: 通过已安装 CLI 操作 OmniDownloader Windows/Web 服务，解析媒体链接、提交下载、查询和取回任务文件、管理配置及中转站。适用于使用 OmniDownloader 下载媒体或管理其任务。
---

# OmniDownloader

使用 `cli-anything-omnidownloader` 控制原 Windows/Web 后端。默认服务地址为
`http://127.0.0.1:58000`，可用全局 `--server URL` 或环境变量 `OMNI_SERVER` 指定。
Android/iOS 应用不提供此 REST API，不能用本 skill 直接控制它们。

## 环境

先检查 `Get-Command cli-anything-omnidownloader` 与
`cli-anything-omnidownloader --json status`。
CLI 缺失时，在本机项目安装：

```powershell
python -m pip install -e 'D:/projects/OmniDownloader/agent-harness'
```

迁移到其他机器时使用实际仓库的 agent-harness 绝对路径；Python >= 3.10。
此包仅本地安装，尚未发布到 PyPI。
后端需要已经运行的 OmniDownloader 桌面/Web 发布版，或原始 windows/omni_server.py。
源码后端可用时安装其依赖并启动：
`python D:/projects/OmniDownloader/windows/omni_server.py --port 58000`。
若 windows/ 缺失，不要自行恢复删除或把其他下载引擎当作原应用。
连接失败时检查服务进程和地址；没有原后端就明确报告该依赖缺失。

## 下载流程

全局 `--json`、`--server`、`--timeout`、`--dry-run` 必须放在子命令前。
程序调用使用一次性命令；无参数会进入 REPL。

```powershell
cli-anything-omnidownloader --json analyze '媒体链接或分享文字'
cli-anything-omnidownloader --json download '解析结果的url' --title '解析结果的title'
cli-anything-omnidownloader --json tasks wait TASK_ID --wait-timeout 300
cli-anything-omnidownloader --json tasks fetch TASK_ID --output 'D:/Downloads/video.mp4'
```

- analyze 返回 title、url、thumbnailUrl、availableVideoFormats、availableAudioFormats、multiMediaList。
- 下载类型 `--type` 为 VIDEO_WITH_AUDIO（默认）、VIDEO_ONLY、AUDIO_ONLY、GIF、COVER。
- `--audio-format` 为 mp3（默认）、m4a、flac、opus；COVER 需传 `--thumbnail URL`。
- `--resolution` 接受原后端的 formatId、清晰度标签或原生直链。按平台分析结果选择，不凭空编造格式 ID。
- 合集读取 multiMediaList，按用户要求选择条目逐个提交；不要同时提交整个合集 URL 与每个分集导致重复。
- download 返回异步任务，读取 tasks[0].id。入队成功不等于完成，wait 遇到 FAILED/CANCELLED/超时返回非零。
- localFilePath 在服务端，fetch --output 在 CLI 本机；文件目标用绝对路径，已有目标不覆盖。

## 任务与设置

- `tasks list`：全部任务；`tasks info TASK_ID`：进度、状态、错误、服务端文件路径。
- `tasks cancel TASK_ID`：请求协作取消；服务端 I/O 可能延迟退出。
- `tasks delete TASK_ID`：删除记录；仅用户要求删除文件时加 `--delete-file`。
- `tasks clear`：清理已结束记录；`tasks clear --all` 会先取消活跃任务再清理全部记录。
- `settings show`：查看配置，Cookie 脱敏。
- `settings set --download-dir PATH --proxy-url URL --max-concurrent N --theme auto|light|dark`：仅更新提供的项。
- `settings set --cookie-file ABSOLUTE_PATH`：读取 UTF-8 Bilibili Cookie 文件，空文件清除 Cookie。不要把 Cookie 写进命令、日志或回答。
- 并发设置保存后需要重启原服务才生效；download-dir 是服务端路径。
- `relay list`、`relay add NAME URL`、`relay delete SITE_ID`、`relay title URL`：管理原服务的中转站配置及探测标题。

## 输出与预检查

成功：退出码 0，stdout 为 JSON。失败：退出码非零，stderr 为 `{"error":"..."}`。
检查退出码再解析对应流；创建任务不自动重试，避免重复下载。
`--dry-run` 抑制修改请求及 fetch 文件写入，返回拟提交内容，例如：

```powershell
cli-anything-omnidownloader --json --dry-run download 'https://example.org/video.mp4' --title '预检查'
```

dry-run 仍允许读取状态与网络解析。服务端负责任务/配置持久化，没有 CLI 会话或 undo/redo。
REPL 使用 help 查看命令，quit/exit 退出，Windows 路径用引号或正斜杠；REPL 提示不是 JSON 流。
fetch 目标文件系统须支持硬链接（NTFS 支持）。

## 验证与边界

本地项目测试入口：
`python D:/projects/OmniDownloader/agent-harness/cli_anything/omnidownloader/tests/run_isolated.py`。
它从 Git HEAD 读取原 Windows 后端到临时目录、启动隔离服务并强制测试已安装命令，不恢复工作区。
测试依赖用 `python -m pip install -e 'D:/projects/OmniDownloader/agent-harness[test]'` 安装。
直接运行 E2E 需设置 OMNI_TEST_SERVER 指向隔离服务，测试会修改配置并创建任务。
已经验证本地 MP4 的原后端下载、取回、字节一致性与解码；未验证公共平台登录、GIF/音频转换和移动端 GUI。
