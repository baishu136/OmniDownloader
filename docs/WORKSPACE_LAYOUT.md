# 工作目录说明

- `app/`、`cleaner/`、`release-smoke/`：Android 工程及验证工程。
- `OmniDownloader-iOS/`：iOS 工程。
- `windows/`：Windows 与 Web 工程。`windows/dist/` 是本机 Web 版正在使用的运行目录，请保留；`windows/release/` 保存历史交付文件。
- `shared/`、`testdata/`、`tools/`、`docs/`：共享数据、测试样例、工具和文档。
- `release/1.7.29/`：当前 Android 发布包、源码快照和验证记录。
- `release/legacy-root/`：从根目录归档的旧安装包和源码压缩包，保留原文件名与内容。名称含 `LATEST` 的文件仍是历史版本，不代表当前最新版。
- `.local/`、`.venv/`、`.gradle/`：本地配置、依赖与工具缓存，保留。
- `scratch/`、各工程 `build/`、Python 字节码缓存：临时测试和构建数据。本次删除命令被自动审批拒绝，尚未清除；完整待清理列表见 `WORKSPACE_CLEANUP_2026-11-13.json`。

`clean_device.bat` 的安装包路径已指向归档目录；该脚本用于清理手机上的应用，不用于整理本工作目录，请勿为目录清理而运行它。
