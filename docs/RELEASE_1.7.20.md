# OmniDownloader 1.7.20

本次发行包含 **Windows / iOS 1.7.20**，以及最新已验收的 **Android 1.7.19（versionCode 78）**。Android 没有改标为 1.7.20。

## 更新内容

- PC 恢复旧版桌面卡片、顶部导航、输入框内操作与靛蓝/青色渐变，保留四页导航及七类设置子页。
- 修复应用图标白边、双重圆角与小尺寸 ICO 边角溢出；桌面 EXE、托盘、网页标识与 Favicon 使用一致来源。各尺寸分别由矢量渲染，并核对 EXE 实际内嵌图标。
- iOS 主要导航、设置和操作图标使用 Android 同源的 Material Outlined 矢量；统一品牌渐变、主次按钮、选中/禁用状态、触控尺寸与按压反馈。应用图标由系统施加圆角。
- Android 1.7.19 删除 GIF 下载面板介绍卡，保留实际 GIF 下载/转换功能；账号说明改为“Cookie 与登录管理”。
- 累计提供会话管理、图文/动画、视频/音频/GIF、任务历史及导出工作流，具体能力以平台矩阵为准。

## 下载与安装

| 平台 | 文件 | 使用方式 |
| --- | --- | --- |
| Android 8.0+ ARM64 | `OmniDownloader-1.7.19-arm64-v8a.apk` | 安装 APK；`OmniDownloader_LATEST.apk` 是相同文件的固定下载别名 |
| Windows 10/11 x64 桌面 | `OmniDownloader_Desktop-1.7.20.zip` | 完整解压并运行同名 EXE；需要系统 WebView2 |
| Windows 本机 Web | `OmniDownloader_Web-1.7.20.zip` | 完整解压并运行同名 EXE；默认仅监听 `http://127.0.0.1:58000` |
| iOS/iPadOS 15+ ARM64 | `OmniDownloader-1.7.20-unsigned.ipa` | **未签名 IPA，需要自行开发者签名后安装**，也可用 Xcode 工程构建 |
| 源码 | `OmniDownloader-1.7.20-source.zip` | 与发行标签对应的完整源码和构建工程；另有固定下载别名 |

请使用附件 `SHA256SUMS.txt` 核对文件。`OmniDownloader-1.7.20-verification.zip` 包含测试汇总、图标校验和真实界面截图。运行时与第三方许可随相应包提供。最高可访问流不等于上传原片，画质受平台、账号和内容权限约束。

## 验证结果与限制

- Windows：75 项核心测试通过，Desktop/Web EXE 实际启动，完成 HTTP 视频下载及 GIF 多帧解码；全部 ICO 尺寸四角透明，EXE 内嵌图标匹配。浅色/深色及分类子页已查看。
- iOS：Xcode 16.4，iPhone 16 Pro / iOS 18.5 模拟器 10 项测试、iPad Pro 11-inch M4 / iOS 18.5 模拟器 2 项 UI 测试通过，ARM64 Release 构建成功。**未进行物理 iPhone/iPad 验收**。
- Android：Release 构建、Lint、Android 15 x86_64 模拟器 Release UI 测试通过；六个原生载荷与上一版本相同。本轮没有 ARM64 真机原生媒体流程验证。沿用项目现有 Debug 签名配置。
- iOS 不包含完整 Python/yt-dlp 运行时；YouTube 格式覆盖、系统分享扩展及后台转换与 Android 有差异，应用内代理要求 iOS 17。未验证所有平台生产链接、真实账号与代理服务器。
- 本机 Web 已更新，并核对配置、下载目录和既有历史保留；用户私有状态、Cookie、备份及签名密钥不包含在附件中。

[界面修改与验收](https://github.com/baishu136/OmniDownloader/blob/v1.7.20/docs/UI_1.7.20_2026-11-09.md) · [平台能力与限制](https://github.com/baishu136/OmniDownloader/blob/v1.7.20/docs/PARITY_1.7.19_2026-11-08.md) · [iOS SDK 测试](https://github.com/baishu136/OmniDownloader/actions/runs/42596594060)
