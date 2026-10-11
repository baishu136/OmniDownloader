# OmniDownloader CLI

控制现有 OmniDownloader Windows/Web FastAPI 服务，调用原应用的提取器、任务调度、yt-dlp 和 FFmpeg。
不能直接控制 Android/iOS，也不会启动另一套下载引擎。

## 安装与后端

Python >= 3.10。在仓库根目录执行：

```powershell
python -m pip install -e ./agent-harness
cli-anything-omnidownloader --help
```

需要运行 OmniDownloader 桌面/Web 发布版，默认服务为 `http://127.0.0.1:58000`。
Windows 源码存在时，可安装其 requirements 并运行 `python windows/omni_server.py --port 58000`。
检查开始时 windows/ 为未提交删除，最终复核时已重新出现；CLI 实现没有恢复或修改这些文件。
可以连接独立的原应用发布版，用 `--server URL` 或 `OMNI_SERVER` 指定服务。
服务端不使用系统 HTTP 代理；媒体下载代理由 `settings set --proxy-url` 配置。

## 常用命令

所有全局参数放在子命令前面。成功 JSON 写 stdout，失败 JSON 写 stderr 且退出码非零。

```powershell
cli-anything-omnidownloader --json status
cli-anything-omnidownloader --json analyze "https://example.org/video.mp4"
cli-anything-omnidownloader --json download "https://example.org/video.mp4" --title "视频"
cli-anything-omnidownloader --json tasks list
cli-anything-omnidownloader --json tasks info TASK_ID
cli-anything-omnidownloader --json tasks wait TASK_ID --wait-timeout 300
cli-anything-omnidownloader --json tasks fetch TASK_ID --output "D:\Downloads\视频.mp4"
cli-anything-omnidownloader --json tasks cancel TASK_ID
cli-anything-omnidownloader --json tasks delete TASK_ID
cli-anything-omnidownloader --json tasks clear
cli-anything-omnidownloader --json settings show
cli-anything-omnidownloader --json settings set --theme dark
cli-anything-omnidownloader --json settings set --cookie-file "D:\private\bilibili-cookie.txt"
cli-anything-omnidownloader --json relay list
cli-anything-omnidownloader --json relay title "https://example.org"
cli-anything-omnidownloader --json relay add "备用站" "https://example.org"
cli-anything-omnidownloader --json relay delete SITE_ID
cli-anything-omnidownloader --json --dry-run download "https://example.org/video.mp4" --title "预检查"
```

`download --type`：VIDEO_WITH_AUDIO（默认）、VIDEO_ONLY、AUDIO_ONLY、GIF、COVER。
`--audio-format`：mp3（默认）、m4a、flac、opus。
`--resolution` 传原分析结果的 formatId/清晰度标签/原生直链，不同平台语义由后端处理。
COVER 需要 `--thumbnail`；合集用 analyze 返回的 multiMediaList 逐项提交，不自动重复整个合集。
提交成功只代表入队：通过 `tasks wait` 确认完成，再用 `tasks fetch` 获取文件。
`localFilePath` 与 `--download-dir` 是服务器路径，`tasks fetch --output` 是本机路径。
取回文件不覆盖已有目标；目标文件系统需支持硬链接（Windows NTFS 支持）。

`settings set` 仅更新提供的字段，可设置 download-dir、proxy-url、cookie-file、max-concurrent、theme。
Cookie 从 UTF-8 文件读取，默认隐藏输出；空文件清除 Cookie。
max-concurrent 保存后需重启原服务才生效。任务取消是协作取消，不保证请求返回瞬间结束所有 I/O。
`tasks clear --all` 会先发取消请求；`tasks delete --delete-file` 同时删除服务器文件。
没有本地会话文件；修改由服务端持久化。任务删除不支持 undo/redo。

无参数进入统一皮肤 REPL，输入 help 查看帮助，exit/quit 退出。Windows 路径用引号括住或使用正斜杠。
JSON 模式用于有子命令的一次性调用；REPL 包含交互提示，不是 JSON 流接口。
dry-run 不提交修改或写下载文件，但仍会读取真实任务状态，analyze/relay title 仍会访问网络。

## Skill 与测试

规范 skill：仓库根目录 `skills/cli-anything-omnidownloader/SKILL.md`。
包内也包含 `cli_anything/omnidownloader/skills/SKILL.md`，可复制至 Codex skills 目录。

```powershell
python -m pip install -e './agent-harness[test]'
python -m pytest agent-harness/cli_anything/omnidownloader/tests/test_core.py -v
python agent-harness/cli_anything/omnidownloader/tests/run_isolated.py
```

run_isolated 从 Git HEAD 提取原 Windows 后端至临时目录，运行隔离服务并强制测试已安装命令；
不会恢复工作区 Windows 文件。需要 Git HEAD 中仍有原始源码。
如果使用另一个隔离服务，则设置 OMNI_TEST_SERVER 和 CLI_ANYTHING_FORCE_INSTALLED=1 后运行整个 tests 目录。
测试会修改隔离服务配置并生成任务，请勿指向日常使用的服务。
覆盖、实际执行命令与结果见 tests/TEST.md。
