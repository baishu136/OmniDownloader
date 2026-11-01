# OmniDownloader v1.5.10 — Android 合集、中转与界面更新

## 本次修复

- 合集保持列表顺序，默认定位分享链接对应的原视频；修正 Bilibili 分P/CID、YouTube 与 X 视频身份处理，使用真实视频地址。
- 静默解析和窗口排查共用中转捕获管线；保留媒体请求、播放器与下载链接观察，移除网页资源面板和深度扫描入口。
- 自动填入/提交按文档去重，限制响应大小、递归深度、轮询和 URL 长度；取消或换站后不再接受迟到回调。
- 下载页使用单个连续移动的选中背景；设置 → 关于提供 GitHub 项目链接。
- 包含此前尚未在 GitHub 发行的切页/列表性能修复、紧凑首页、七类设置子页、Material 3 按钮与启动交互、R8/资源裁剪及移除未使用 Aria2c。

## 下载

Android 8.0+，ARM64，versionCode 51；正式 APK 53.35 MiB。提供带版本号 APK、LATEST 别名、源码归档、SHA-256 清单和对应混淆映射。请使用 SHA256.txt 核对文件完整性。

APK SHA-256：`736140D712BFD06A9BF8F0A92D13AB5A67A7F6DCFFFDC035050459BB63CBFD27`。

## 验证与限制

从本地完整验收记录恢复并重新核对源码/产物哈希：27 项 JVM 测试、8 项 Android 15 模拟器设备用例通过；x86_64 优化包完成原生命令、转码及本地下载验证；ARM64 正式包完成 UI 和冷解包验证。Release Lint 0 错误、40 警告，六个原生载荷保持一致。

未在 ARM64 真机验证完整媒体命令；ARM64 命令在 x86_64 转译环境的 FFmpeg 加载失败保留记录。未提供原始合集故障链接；不保证所有第三方站点、验证码、DRM/blob-only 或 DASH MPD。沿用项目原有 Debug 签名配置。

[修改详情](https://github.com/baishu136/OmniDownloader/blob/v1.5.10/docs/RELAY_AND_COLLECTION_1.5.10.md) · [验收记录](https://github.com/baishu136/OmniDownloader/blob/v1.5.10/docs/RELEASE_VERIFICATION_1.5.10.md)
