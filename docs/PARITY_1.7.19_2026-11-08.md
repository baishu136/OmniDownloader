# 1.7.19 三端对齐与验收记录

开始于 2026-11-08，交付验收于 2026-11-09。以 Android 1.7.18 为行为基线，保留已有各端代码；本次 Android 仅修改界面与版本，iOS 扩展原生实现，Windows 同步布局、设置与自动补全。未覆盖、回滚工作目录内的其他修改。

## 两处标注

1. 删除 Android 下载面板的“高保真 GIF 动图转换”说明卡，保留 GIF 模式、开始下载及实际转换流程。
2. 账号分类说明统一为“Cookie 与登录管理”，同时同步繁体中文、英文、日文；iOS/Windows 同步分类入口。

## 能力矩阵

| 能力 | Android 1.7.19 | Windows 桌面 EXE / Web EXE | iOS 1.7.19 | 权限、依赖与验收 |
| --- | --- | --- | --- | --- |
| 导航与布局 | 首页/图文/任务/设置；原布局保留 | 四页、单行输入、平台标签、分类设置、响应式底栏 | 四页、状态保留、平台两行展开、单行输入、底部导航 | Android Release UI 测试；Web 实际界面；iPhone/iPad UI 测试及截图 |
| GIF 面板 | 删除说明卡，模式和转换保留 | GIF 选项和实际转换保留 | 删除说明卡，链接/视频转标准 GIF | Windows 两种 EXE 实际 GIF 多帧解码；iOS 原生 FFmpeg + ImageIO 多帧解码 |
| 分类设置 | 七类与子页 | 七类与返回；组件随应用更新 | 七类与子页，浅/深色、四种核心界面语言 | Android 七子页及返回通过；Web 分类/返回/主题检查；iOS 导航自动测试 |
| 会话管理 | 12 平台登录/导入/清除/禁用 | Windows DPAPI；平台文件导入/清除/禁用；桌面辅助浏览器 | 12 平台 Keychain、隔离 WebKit 登录/导入/清除/禁用 | 不读取系统浏览器密码；禁用后新请求不使用会话；真实账号登录未在本次测试中执行 |
| 视频与画质 | 现有原生/Python/网页流程保留 | 原生/yt-dlp/官方独立 Edge 补全；尺寸/画质策略/成品核对 | 原生 B 站/抖音/X/Pixiv、目标作品 WebKit、MP4 Range 核对、帧率/HDR/编码选择要求 | 不把上传参考尺寸当作当前流实测尺寸；未知规格保持未知；验证码与内容权限仍受平台控制 |
| 图文/动画 | 原图、多选、ZIP、Ugoira 保留 | 图文入口、原图/ZIP、Ugoira | 原图/多选/ZIP/动画预览、Ugoira MP4/GIF/帧 ZIP | 目标 ID 与图片内容校验；iOS 实测 ZIP CRC、Deflate 帧解压；跨平台脚本样例 |
| 音频/流复制 | 保留 MP3/M4A/FLAC/OPUS、视频轨合并 | FFmpeg 随包；合流、纯画面、音频、GIF | 链接原生 FFmpeg；MP3/M4A/FLAC/OPUS、流复制、纯画面 | Windows 真实 HTTP 下载及音轨/成品解码；iOS 实测四种音频、静音视频、原 MP4 字节一致 |
| 任务与导出 | 并发/合集/取消/重试/历史保留 | 并发/取消/重试/历史/导出保留 | 并发/暂停/取消/合集/历史/预览/分享/目录与相册 | iOS 保留完整输入与验证通过的成品；导出拒绝后关闭源服务重试仍成功 |
| 生命周期 | 前台服务行为未改 | 托盘或独立窗口，关闭桌面窗口退出 | URLSession 后台传输；转换在前台恢复；退出后记录为暂停 | iOS 强制结束后不承诺继续执行转换；真机后台与锁屏尚待实际设备复核 |
| 代理 | HTTP/SOCKS 现有行为保留 | HTTP/HTTPS/SOCKS；自动 Edge 需要无认证代理 | iOS 17+ 原生 URLSession/WebKit 代理；iOS 15/16 可用系统网络设置 | 编译与策略验证；未在本次进行真实代理服务器/物理设备验收 |
| 成品 | ARM64 APK | 解压运行 Desktop EXE、Web EXE | ARM64 IPA 与 Xcode 工程 | iOS IPA 未包含 Apple 开发者签名，须自行签名后安装；未进行 App Store 发布 |

## 实际验证

- Android：`:app:assembleRelease :app:lintRelease --offline` 通过；最终 APK 版本 1.7.19/78，ZIP CRC 通过，签名与上一版一致；原生库文件及 CRC 与 1.7.18 完全一致。Android 15 x86_64 专用模拟器安装最终 ARM64 APK，Release 黑盒测试 `OptimizedReleaseTest` 通过（22.048 秒），覆盖历史恢复、导航、七类设置与子页返回、冷启动资源安装。此轮跳过 ARM64 原生可执行组件执行；不能把该模拟器结果视为 ARM64 真机验收。
- Windows：75 项核心测试通过，包括本地 HTTP/FFmpeg、画质与导出重试。新增 6 个共享脚本样例通过。Desktop EXE 和 Web EXE 分别在独立状态/端口启动，完成真实 HTTP 解析、视频下载/解码和 GIF 多帧解码。所有 UI 样式与字体使用本地资源。
- Web：实际检查四页导航、七分类、子页返回、账号说明、语言入口与深色主题。本机 58000 服务已替换到 1.7.19，原有 1 条历史的 ID 与配置/目录已核对；Cookie/历史/下载文件不随替换安装目录删除。旧目录与状态备份留在本机 `.local/backups/` 和版本备份目录。
- Windows 原生窗口：窗口与后端成功启动，媒体流程通过；电脑截图助手连续报 `FrameArrived timed out` / `window capture timed out`，因此没有把原生窗口截图列为通过。相同 UI 已在正式 Web 页面核对并保存截图。
- iOS：macOS 15.8.2 / Xcode 16.4，iPhone 16 Pro / iOS 18.5 模拟器 10 项测试通过（8 项原生/模型测试、2 项界面测试），iPad Pro 11-inch M4 / iOS 18.5 模拟器 2 项界面测试通过；Release ARM64 构建成功。测试签名使用本地临时签名；设备包为待签名 IPA。
- Apple SDK 构建和媒体验证： https://github.com/baishu136/OmniDownloader/actions/runs/42556269823 。横屏整屏截图已复核，截图导出运行 https://github.com/baishu136/OmniDownloader/actions/runs/42550990085 ；iPhone/iPad 亮色竖屏及深色横屏截图、汇总 JSON 随交付保存。

## iOS 尚有的平台差异

界面与主要下载工作流按 Android 制作，以下能力不能表述为所有细节完全等价：iOS 不包含完整 Python/yt-dlp 运行时，YouTube 仍使用官方网页会话与已确认的可访问直连格式，可能需要播放/验证，格式覆盖与 Android 有差异；没有系统分享扩展，采用剪贴板和 URL Scheme；转换受 iOS 前后台调度限制；应用内代理要求 iOS 17。部分网站与账户权益必须用真实账号/真机另行验证。不能将模型、合成媒体、模拟器或构建通过当作所有平台生产链接已验证。

## 构建与恢复

Windows 运行时固定在 `windows/runtime-lock.json`，已与官方 FFmpeg 7.1 原始归档及本次实际打包的二进制逐一核对；Deno 2.9.8 同样固定归档/二进制 SHA-256。打包脚本只写版本目录。iOS 使用固定 SwiftPM 提交，GPL/第三方许可随包提供，源码与构建工程一并交付。

本机升级备份含用户状态，请保留在本机。回退程序需先退出新版托盘，恢复旧目录；数据如需回退应使用对应状态备份，不要让旧版直接写入新格式。已下载成品不参与回退覆盖。未合并主分支或创建公开 Release。
