# Android 1.7.55 启动与开屏衔接

保留首次冷启动最短 500ms 的开屏条件，以及 Android 8–11 的 400ms 淡出。首页和图标均不添加位移动画。Android 12 及以上继续使用系统默认退出。

## 实现

- `startup/LaunchSession.kt` 区分进程内第一次创建、同进程重开和状态恢复。第一次正常冷启动保留 500ms；恢复与同进程重开不重复添加这段等待。
- `ui/FirstDrawWork.kt` 在实际绘制之后排队执行工作，避免预绘制被开屏条件阻止时提前运行。Activity 停止或销毁时取消等待，返回前台可重新安排。
- `OmniApp` 用原子标记保证下载引擎预热只安排一次。Android 12+ 在首帧绘制后预热，Android 8–11 在淡出结束后预热。实际下载仍走原有引擎按需初始化入口。会话存储准备与临时凭证清理继续在早期 IO 线程执行。
- `ui/theme/LaunchTheme.kt` 只读取主题偏好，进入页面前选择对应的开屏及窗口样式。页面首次绘制后同步下一次系统开屏的主题，切换设置时保持当前 Activity 和页面状态。
- 新增固定浅色和深色开屏、窗口样式，图标颜色通过主题属性解析；系统模式恢复平台默认主题，继续跟随设备。

Android 12+ 通过 [SplashScreen.setSplashScreenTheme](https://developer.android.com/reference/android/window/SplashScreen#setSplashScreenTheme(int)) 持久化开屏主题，样式名称需在后续版本中保持稳定。首次升级、系统尚未保存应用主题时，最早的系统窗口可能仍采用系统配色；进入一次应用并绘制后，后续启动会使用同步后的主题。

## 可重复验证

`tools/profile_android_startup.py` 对指定设备上的安装包执行冷启动、同进程重开、返回前台测量，保存原始启动结果和帧统计，可同时采集 Perfetto。该工具会停止测试应用以产生冷启动，应在测试安装中运行。

```powershell
python tools/profile_android_startup.py --serial emulator-5586 --label candidate --trace
```

输出位于 `.local/startup-profile/`。`release-smoke` 中的 `StartupThemeFixtureTest` 用于配置隔离测试安装的主题，让已优化的 Release APK 能执行真实冷启动配色验证，无须引用被 R8 混淆的应用类。

Android 8–11 系统分支和 ARM64 真机需另行运行验证，模拟器结果不能代表真机帧率或启动耗时。

## 本轮结果

- 3 项启动会话 JUnit 测试通过，日志 `.local/startup-polish-unit.log`。Gradle 默认 worker 在当前中文路径中出现类加载失败，已改用仓库已有的 Unicode 类路径方式直接运行 JUnit；原 Gradle 测试报告不作为通过依据。
- 最终代码的 10 项界面测试通过，日志 `.local/startup-polish-tests-final.log`。覆盖 400ms 淡出时长、无位移、中断、关闭动画、首帧任务取消及去重、显式主题与系统主题相反时的颜色、实时主题切换保留 Activity、页面重建与原有主题渐变。
- 实际 R8 发行 APK 的 4 种配色组合通过录屏像素检查，包括系统浅色/应用深色、系统深色/应用浅色及跟随系统的深浅两种状态。检查到了对应的系统开屏图标、背景及最终首页颜色。记录位于 `.local/startup-profile/theme-validation.json`。
- 发行 APK 在关闭三种系统动画缩放后，深浅色两次冷启动检查通过，记录位于 `.local/startup-profile/no-motion-validation.json`。最终 crash 日志为空。
- ARM64 APK 构建成功，通过版本、ABI、APK v2 签名校验，签名证书与 1.7.54 一致。安装文件为 `release/1.7.55/OmniDownloader-1.7.55-arm64-v8a.apk`，同目录包含 SHA256SUMS.txt。

## 发行构建测量

设备为 Android 15 / API 35、`sdk_gphone64_x86_64` 软件渲染模拟器。对照包为 1.7.54，新包为最终 1.7.55，均为 R8 Release x86_64 APK。每版执行 3 次强制冷启动、3 次同进程 CLEAR_TOP 重开和 1 次返回已有任务，缓存与应用数据保留。使用 `am start -W`，重开时确认进程未变化。其他系统负载未完全控制。

| 场景 | 1.7.54 样本 / ms | 1.7.55 样本 / ms | 中位值 / ms |
| --- | --- | --- | --- |
| 冷启动 TotalTime | 1760、1458、1203 | 1536、1791、1547 | 1458 → 1547 |
| 同进程重开 TotalTime | 569、592、575 | 209、219、208 | 575 → 209 |
| 返回已有任务 WaitTime | 39 | 132 | 单次样本 |

同进程重开减少了重复等待。冷启动中位值略高，本轮没有测得稳定的冷启动加速；返回已有任务仅有单次样本，不能判断改善。此次调整不构成真机性能验收。

原始结果为 `.local/startup-profile/baseline-release-timings.json`、`optimized-final-timings.json`，同时保存了帧统计和两版 Perfetto 调度轨迹。优化过程中发现单纯推迟所有后台准备未带来冷启动收益，最终保留早期 IO 会话准备，仅推迟媒体运行时加载，并把主题缓存写入安排在首帧之后。
