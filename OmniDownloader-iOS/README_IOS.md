# OmniDownloader iOS 1.7.61

本次同步 Android 的统一视频/图文入口、批量页、平台完整展示、更新检测、交叉缩放开屏和新桌面图标。图标来自用户提供的原图，应用内品牌图形保留。开屏停留 250ms、过渡 240ms，无位移；“减少动态效果”开启时关闭缩放。

批量支持多链接/TXT（UTF-8、UTF-16，最大 1 MiB）、去重、最多 100 条、下载类型/画质/音频格式/合集/封面设置、停止添加、失败重试及草稿恢复。已添加任务继续使用现有下载管理器；重启不自动重复添加已入队项。需要交互验证的链接可转到首页处理。图片选择与 ZIP 已合并为首页解析结果面板。

新增微博状态链接原生解析（核对作品 ID、实际播放规格和图片）；X 对传输 EOF/断流最多重试一次，证书错误或作品不匹配直接报错。真实账号和所有站点尚未全面端到端验证。iOS 无 Android 的 Python/yt-dlp 运行时；随包 1753 个解析器名录仅作上游参考。

更新检查读取正式 GitHub Release，仅提示包含 IPA 的较新版本；自动检查间隔六小时，也可在“下载引擎”设置中手动检查。应用不自行安装未签名 IPA。

macOS CI：根目录 `.github/workflows/ios-1.7.61.yml`，分支 `codex/mobile-1.7.61`。执行 iPhone 原生/UI 测试、ARM64 未签名 IPA 构建、iPad UI 测试及依赖审计。IPA 需自己的证书/描述文件或个人签名工具签名后安装；Windows 主机不能本地执行 Xcode，实际结果以 CI 记录为准。

原生 SwiftUI 客户端，按 Android 四页导航与分类设置移植。最低 iOS/iPadOS 15；iPhone、iPad、横竖屏、深浅色和减少动态效果。应用内自定义 HTTP/HTTPS/SOCKS5 代理需要 iOS 17；旧系统仍可使用系统网络设置。

## 功能与界面

- 首页：单行链接、剪贴板、输入框上方的平台标签、解析进度、可取消解析、中转站浏览器。
- 下载面板：音画合流、纯画面、音频、GIF、封面、真实规格与合集选择；已移除 GIF 宣传说明卡。
- 批量：多链接、TXT、类型/画质选择、失败重试；图文原图选择与预览、单图/ZIP、Pixiv Ugoira 原始帧/MP4/GIF 位于首页解析结果中。
- 任务：并发、暂停/取消/继续、合集分组、历史、分享/预览、目录导出与可选相册保存。源过期可重新解析同一媒体；尺寸、帧率、HDR、编码要求不会被静默降低。
- 设置：外观与语言、下载与存储、网络代理、中转站、账号、下载引擎、关于。账号说明为“Cookie 与登录管理”。
- 各平台独立 Cookie 文件导入、官方网页登录、清除与禁用；登录状态保存在设备钥匙串，WKWebView 使用隔离的临时数据存储。
- 抖音先读取同作品原生数据，在仅有低清/未知尺寸或参考尺寸更高时自动使用本机 WebKit 补全，保留已取得的可用源。验证码和必须登录的页面仍需本人处理。
- MP4 使用有界 Range 读取确认实际尺寸；最终文件通过 libavformat 核对真实尺寸、音轨与时长。视频源有合流 MP4 时保持原始字节，其他合并使用流复制；GIF 和音频为明确选择的转换。

## 平台差异与限制

系统后台 URLSession 负责网络传输，媒体转换在前台进行；进入后台时暂停转换，回到应用可继续。已完成的输入和校验通过的成品会保留，导出授权失效后可重选目录并重试。强制结束应用后，记录恢复为暂停；未完成的会话需要重新解析。

B 站、抖音、X、Pixiv 提供原生数据入口，其他平台按目标作品从官方网站 WebKit 数据提取。平台验证码、登录、内容删除、会员/充电/地区权益和 DRM 限制不会被绕过。YouTube 使用现有官方网页会话与可确认的直连流；仍可能需要播放和验证，不包含 Android 的完整 Python/yt-dlp 运行时，不能承诺相同的所有格式覆盖。当前没有系统分享扩展；可以粘贴链接或使用 `omnidownloader://download?url=...`。

相册“仅添加”权限允许写入照片；只有获得完整图库权限时才管理专属相簿。拒绝相册权限不删除已导出的文件。

## 可复现构建

1. 在 Mac 使用 Xcode 16.4 或更新版本打开 `OmniDownloader.xcodeproj`，选择共享 scheme `OmniDownloader`。
2. SwiftPM 固定 `jaywcjlove/ffmpeg-kit` 的提交 `0bd85df8f9693061f6f979414662baaf876a3e20`；首次构建需要网络获取依赖。
3. 模拟器测试使用本地临时签名，以便系统后台传输服务识别应用，无需上传 Apple 账户凭据：

```sh
xcodebuild test -project OmniDownloader.xcodeproj -scheme OmniDownloader -destination 'platform=iOS Simulator,name=<available iPhone>' CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=YES CODE_SIGN_IDENTITY=-
```

4. 未签名设备构建：

```sh
xcodebuild build -project OmniDownloader.xcodeproj -scheme OmniDownloader -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
```

5. 真机安装需要自己的 Apple 团队/证书或自行签名。工程默认不读取本机证书；在 Xcode 设置签名团队，并将 `CODE_SIGNING_ALLOWED`、`CODE_SIGNING_REQUIRED` 设为 `YES`。未签名 IPA 不能直接安装。

根目录 `.github/workflows/ios-1.7.20.yml` 执行 iPhone 原生媒体/界面测试、ARM64 构建与 iPad 布局测试。详细验收状态以 `docs/PARITY_1.7.20_2026-11-08.md` 和对应 Actions 运行记录为准，编译、模拟器与真机验证分别记录。

## 媒体组件与源码

FFmpegKit 的 C 桥接和原生库来自上述固定提交；`NativeProbe.c` 直接读取 libavformat 媒体信息，避免依赖 ffprobe 命令输出重定向。该依赖含 GPL v3 组件，相关许可随应用 Resources 提供。应用完整源码、工程与构建脚本及依赖固定版本一并交付。依赖的构建脚本、第三方源码版本和下载地址可在固定提交中复现：

https://github.com/jaywcjlove/ffmpeg-kit/tree/0bd85df8f9693061f6f979414662baaf876a3e20

1.7.20 UI：导航、设置及主要按键使用与 Android 同源的 Material Outlined 矢量资源；品牌、启动图标及按钮主色/禁用/选中/按压状态对齐 Android。SVG 与 SHA-256 位于 `shared/icons/material`，Apache-2.0 许可随包提供。
