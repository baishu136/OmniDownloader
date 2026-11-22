# Android / iOS 1.7.61

Android versionCode 118；iOS CFBundleVersion 118。安装包集中放在 `release/1.7.61/`，校验值见同目录 `SHA256SUMS.txt`。

## Android

- 包含当前工作目录的 Android 修复和既有功能；保留 1.7.58 的 250ms 停留、240ms 交叉缩放及用户提供的桌面图标。
- X 原生公共接口对临时 TLS EOF、断流等传输异常最多重试一次，等待 400ms，受原有总超时限制。证书、取消、拒绝访问、限流和作品 ID 不符均不因此放宽。
- 重新生成 Python extras，包含 Windows 已修正的 X 公共元数据处理；APK 内嵌资源哈希已与源文件核对。
- 修复 HTTPS 代理双向转发正常关闭时，将 `Socket closed` 误记为 TLS 错误的状态问题。
- 包含既有 GIF 实际转码与验证、MediaStore 导出、合集打开、批量下载、更新检测及 yt-dlp 平台解析。

Windows 构建：`gradlew.bat :app:assembleRelease --console=plain`。默认 ARM64；QA 使用 `-PomniTestAbi=x86_64` 单独生成模拟器包。签名证书 SHA-256 为 `0bdc5678e09d4508829ff43f7c63f7ee6ab8af64134f0079cc186d8aedd41f4e`，与前版一致。

## iOS 同步

- 首页统一解析视频和图文，图片选择、查看、单图和 ZIP 在结果面板内完成。
- 批量替换旧图文标签页。支持多链接/TXT、去重、类型/画质/音频格式、全部分集、附带封面、逐条异步解析、停止添加、失败重试及草稿恢复。复用 DownloadManager，无第二套下载任务系统。
- 平台标签全部展示，追加微博等主流平台名称；可搜索 1753 个 yt-dlp 解析器参考名录，并明确标注 iOS 未内置 yt-dlp。
- 新增微博状态链接原生数据入口，检查作品 ID，保留实际播放规格；X 公共请求有界重试，不接受浮点舍入后的作品 ID、引用帖身份或无效证书。
- GitHub 正式发布更新检测：自动六小时节流，设置页可手动检查，仅提示带 IPA 的较新版本。
- 开屏采用单一进度驱动透明度和中心缩放，250ms 停留、240ms 退出，无上浮；尊重系统减少动态效果。
- 桌面图标使用同一用户原图进行确定性适配：按 Android 自适应前景可见区域取中心 2/3，白底、1024×1024 RGB，无透明度；未重新绘制图形。应用内 OmniBrand 资源保留。
- “同时保存封面”创建独立可重试任务；封面源过期后重新核对原作品。

新增 Swift：`Services/AppUpdates.swift`、`Services/BatchDownloads.swift`、`Engines/WeiboExtractor.swift`、`Engines/XPublicResponse.swift`、`Views/BatchDownloadView.swift`、`Views/LaunchTransition.swift`、`Views/PlatformCatalogView.swift`。原生测试增加 `MobileParityTests.swift`。

微博播放字段参考 [yt-dlp 官方解析器](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/weibo.py)，没有复制绕过平台权限的逻辑。

## 构建与验证边界

- Android 单元测试 303/303 通过；25 项 API 35 / x86_64 仪器测试通过，覆盖 GIF 转码、系统/应用 GIF 解码、损坏文件拒绝、FFmpeg 资源恢复、合集 MediaStore URI、X 有界重试及启动动画/生命周期。
- ARM64 Release 已构建并验证版本、ABI 和 APK 签名；本机没有连接 ARM64 手机，未声称 ARM64 真机运行通过。
- 首轮单元测试发现正常代理断开被错误记录，修复后全量重跑通过。早期模拟器系统 ANR、首次权限弹窗阻挡了 UI 测试；均不计入成功记录。
- 旧 Release 验收脚本使用固定 Google 档案名、在英文界面寻找中文旧批量按钮，已改为唯一档案名和实际英文标签；没有为迁就脚本修改产品界面。
- iOS 在 Windows 仅能做语法和资源检查；真正编译与测试由 macOS GitHub Actions 执行。首轮原生测试通过后，为包含最后改动重新构建；过程中实际编译发现可空封面字段处理问题，已修复。最终结果以本文件后续验收记录及 CI 日志为准。

## 已知差异

iOS 使用 SwiftUI、WebKit、URLSession、原生 FFmpeg，不包含 Android Python/yt-dlp 运行时。主流平台展示与上游参考名录不保证逐站点下载成功；部分浏览器解析需要可定位的作品及交互验证。YouTube 的全部格式、Google 多档案行为与 Android 不完全相同，未对真实登录账号做全面验收。验证码、DRM、会员及地区限制不绕过。

本机没有 Apple 分发证书/描述文件，CI 仅输出 ARM64 **未签名 IPA**。必须使用自己的合法签名方式签名后安装，不能直接双击安装。暂无 iPhone 真机验证，也没有把模拟器/本地媒体用例描述为真实平台验收。

macOS 流水线 `.github/workflows/ios-1.7.61.yml` 位于 `codex/mobile-1.7.61`。通过独立 Git index 提交 iOS 与 CI 相关文件，未切换当前 main 分支或覆盖用户暂存区；本次没有修改其他发行版。

Android 最终 Release 黑盒测试 1/1 通过，包含冷启动运行时解包、导航、Google 本地档案加密保存/退出、批量页、实际 yt-dlp 本地 HTTP 下载和 FFmpeg 音频/GIF/静音输出。仅使用合成档案和本地媒体，不属于真实 Google 账号或线上平台端到端验收。

## 最终安装包及验收

- Android：`release/1.7.61/OmniDownloader-1.7.61-arm64-v8a.apk`，60,998,320 字节。SHA-256：`36255dea6232d83a1d45d7c8068eca8be4a64c9dadc332853e2afd527c0d6b2b`。ARM64 包和已通过黑盒测试的 x86_64 包的 DEX 完全相同；JNI 仍需 ARM64 真机验证。
- iOS：`release/1.7.61/OmniDownloader-1.7.61-unsigned.ipa`，12,045,282 字节。SHA-256：`373ee83b1b76e26e73ed5bd4a90d833560e47ba418964d844cfc6c1f179fe838`。包内版本 1.7.61（118）、最低系统 15.0、Bundle ID `com.omnidownloader.ios`，无分发签名及描述文件。
- [最终 macOS 构建记录](https://github.com/baishu136/OmniDownloader/actions/runs/45039182886) 全部通过，源提交 `ded0d2834122a171df0eca2060dc2d5140144f7d`。iPhone 16 Pro / iOS 18.5 模拟器通过 17 项测试（15 项原生、2 项 UI），iPad Pro 11-inch (M4) / iOS 18.5 模拟器通过 2 项 UI 测试，无失败或跳过。
- 最终增加批量取消回归：取消后清空列表，再到达的网络取消/错误回调不能写入旧行或重复排队；该用例已在 macOS 上通过。
- IPA ZIP 完整性、版本、图标入口和实际编译图标均已检查。Apple CgBI 优化图标解码后确认是新黑白图标。原生依赖审计覆盖 9 个 ARM64 二进制，未发现缺失依赖；未把此项等同于 iPhone 真机安装验收。
- `release/1.7.61/` 同时包含两种安装包、`SHA256SUMS.txt`、`test-results.json`、iPhone/iPad 原始测试摘要及 `ios-dependency-audit.json`。
