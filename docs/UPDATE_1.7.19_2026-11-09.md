# OmniDownloader 1.7.19

按本次两张标注图调整 Android，并提供 Windows 桌面、Windows 本机 Web 和原生 iOS 工程及待签名 IPA。

## 界面调整

- 删除 GIF 下载模式下的“高保真 GIF 动图转换”介绍卡，保留 GIF 下载与转换功能。
- 设置中的账号分类说明改为“Cookie 与登录管理”，同步四种界面语言。
- Windows 与 iOS 使用 Android 的首页、图文、任务、设置四页结构，设置分为七类及子页。

## 平台实现

Windows 提供解压运行的 Desktop EXE 和 Web EXE，前端字体与样式随包提供。加入独立 Edge 会话的抖音自动补全、严格目标匹配、画质规格与成品核对。运行时版本和 SHA-256 固定，包含 FFmpeg、Deno 的许可与来源说明。

iOS 使用 SwiftUI、隔离 WebKit 会话、Keychain、URLSession 后台下载及原生 FFmpeg。支持视频、音频、GIF、原图、ZIP、Ugoira、任务历史、暂停与重试、相册及目录导出。已完成 Apple SDK 编译、iPhone/iPad 模拟器测试和 ARM64 设备包构建。

本机 Web 已升级至 1.7.19，保留下载目录、配置、原有历史记录及文件。升级前的程序和私有状态备份只保存在本机。

## 安装与限制

- Android APK 沿用上一版签名，适用于 ARM64 设备。
- Windows 下载对应 ZIP，完整解压后运行同名 EXE；桌面版依赖系统 WebView2。本机 Web 地址为 `http://127.0.0.1:58000`。
- iOS IPA 没有 Apple 开发者签名，需自行签名后安装，或在 macOS/Xcode 中打开工程并设置开发团队。最低 iOS 15，应用内代理要求 iOS 17。
- iOS 尚不能称为 Android 的全部能力完全等价：YouTube 格式覆盖不同、没有系统分享扩展，转换受系统后台调度限制。未完成物理 iPhone/iPad、全部生产平台链接和真实账号的完整验收。

详细能力矩阵、测试证据与回退说明见 [验收记录](PARITY_1.7.19_2026-11-08.md)。源码同步至开发分支，不自动合并 main 或创建公开 Release。
