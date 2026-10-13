# OmniDownloader CLI 架构分析

## 现状与依据

- Android `app/.../engine/DownloadEngine.kt`：依赖 Android Context、yt-dlp、FFmpeg 和原生平台提取器，没有独立命令入口。
- iOS `Services/DownloadManager.swift`：原生应用服务，没有 CLI。
- Git HEAD 中 `windows/omni_server.py`：argparse 仅接受 host/port，用于启动 FastAPI；不提供解析、下载、任务管理 CLI。
- 检查开始时 windows/ 为已有的未提交删除，最终复核时已重新出现。新增 harness 未恢复或修改这些文件。

## 推荐实现

Click CLI → 原 Windows FastAPI REST API → TaskManager → 原生提取器 / yt-dlp / FFmpeg。
默认地址为 http://127.0.0.1:58000，可用 --server 或 OMNI_SERVER 指定。
实际运行需要已有 Windows 服务/发布版；不把另一套下载器作为替代实现。

命令域：status、analyze、download、tasks、settings、relay、repl。
服务端负责 config.json/tasks.json 持久化，CLI 不维护第二套会话文件，不实现虚假的 undo/redo。
REPL 使用统一 ReplSkin，执行相同 Click 命令；修改命令支持全局 --dry-run。
下载为异步任务，tasks wait 轮询最终状态，tasks fetch 从服务器流式取回最终文件。
所有 JSON 成功结果写 stdout，失败写 stderr 并返回非零退出码。Cookie 默认脱敏。

## 已知服务端边界

- 修改并发配置不会重建已有 TaskManager semaphore，CLI 不声称能实时调整并发。
- clearAll 服务端移除记录/取消事件但不可靠地停止正在运行的协程；CLI 先逐个 cancel 再清理。
- 删除/取消不存在的任务时 API 也可能返回 success；CLI 先检查任务存在。
- 批量请求不是事务，没有自动重试，避免重复创建任务。
- 本 harness 控制 Windows/Web 后端，不能直接控制 Android/iOS 应用。
- 预览可直接用 analyze 与 tasks list 检查真实状态；没有可渲染的编辑项目，因此不增加 preview bundle。

## 验证

测试计划与实际结果见 cli_anything/omnidownloader/tests/TEST.md。
真实 E2E 需要 OMNI_TEST_SERVER 指向隔离服务；测试会更新设置与生成下载任务。
当前 Windows 源码缺失时，可从 Git HEAD 提取至临时目录运行原始服务，不能将此当作工作区恢复。
