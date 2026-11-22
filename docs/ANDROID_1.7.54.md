# Android 1.7.54 开屏回退

按 1.7.50 的开屏行为恢复启动流程，移除自定义上浮效果。对照 `release/1.7.50/OmniDownloader-1.7.50-arm64-v8a.apk` 的入口字节码确认系统版本分支，并核对其深浅色开屏配色。

- Android 12 及以上恢复系统默认开屏退出。
- Android 8 至 11 保留原有 400ms 遮罩淡出，移除首页 16dp 上移。
- 恢复原有最短 500ms 开屏条件及后台引擎预热时机。
- 移除 1.7.52、1.7.53 的图标和首页位移动画、首页渐显与预先隐藏逻辑。淡出取消或页面停止时，遮罩仍可安全移除。

应用版本递增为 1.7.54，versionCode 为 113，用于覆盖安装。

验证环境为 Android 15 / API 35、x86_64 模拟器。5 项仪器测试通过，覆盖兼容淡出函数的中间态、取消、关闭动画，以及真实页面启动和重建后的不透明度、零位移和页面状态保留。浅色、深色分别执行开启与关闭系统动画的冷启动，共 4 次，启动返回成功，首页文字及截图均检查通过，最终 crash 日志为空。已检查冷启动录屏。

测试日志位于 `.local/splash-rollback-tests.log`，冷启动记录位于 `.local/splash-rollback-validation.json`，录屏位于 `.local/splash-rollback-light.mp4` 和 `.local/splash-rollback-dark.mp4`。

没有连接 ARM64 真机，Android 8 至 11 的实际系统分支未执行运行验证；其淡出函数已在 API 35 仪器测试中直接验证。

ARM64 发行包构建成功，已检查版本、ABI 并通过 APK v2 签名校验，签名证书与 1.7.53 一致。安装文件位于 `release/1.7.54/OmniDownloader-1.7.54-arm64-v8a.apk`，校验值位于同目录 `SHA256SUMS.txt`。构建日志位于 `.local/splash-rollback-release.log`。
