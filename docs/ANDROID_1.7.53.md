# Android 1.7.53 开屏衔接修正

修正 1.7.52 的双重透明度叠加。首页保持不透明，开屏遮罩提前淡出，在首页仍有 60% 上浮距离时完全露出内容。图标与首页共用一个 280ms 动画时间轴和减速曲线，上移距离为 24dp，避免四个不同结束时间造成分段感。

可选的下载引擎预热改在开屏过渡结束后启动，减少 Python、FFmpeg 初始化与首屏绘制竞争。实际下载仍通过原有 ensureInitialized 按需初始化。动画关闭、中断、Activity 停止和系统未提供图标时沿用安全收尾。

系统移交开屏视图前暂不显示首页，退出回调中再显示并上浮，修正逐帧录屏中发现的首页先闪现一帧、随后重新开始上浮的问题。

验证使用 Android 15 / API 35、x86_64、SwiftShader 模拟器。

- 6 项仪器测试通过，覆盖页面提前露出、连续位移、终态、取消、关闭动画、无图标和真实 Activity 重建。
- 浅色、深色分别执行开启与关闭系统动画的冷启动，共 4 次，启动返回成功，首页文字和截图均检查通过。
- 冷启动录屏已逐帧检查，最终运行的 crash 日志为空。初次录屏受到模拟器 System UI 异常弹窗影响，已排除该录屏并重新执行。
- 仪器测试日志位于 `.local/splash-continuity-tests-final.log`，冷启动结果位于 `.local/splash-continuity-validation.json`，录屏位于 `.local/splash-continuity-light.mp4` 和 `.local/splash-continuity-dark.mp4`。

模拟器录屏用于检查过渡顺序，不构成真机帧率或启动耗时的性能验收。当前没有连接 ARM64 真机，Android 8 至 11 的运行表现也尚未验证。

ARM64 发行包构建成功，并通过 APK v2 签名校验及版本、ABI 检查。版本为 1.7.53，versionCode 为 112。安装文件位于 `release/1.7.53/OmniDownloader-1.7.53-arm64-v8a.apk`，同目录提供 `SHA256SUMS.txt` 和截取自实际录屏、未变速的 `splash-preview.mp4`。发行构建日志位于 `.local/splash-continuity-release.log`。
