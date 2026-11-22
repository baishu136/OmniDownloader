# OmniDownloader 1.7.0 实现与验证记录（2026-11-02 至 11-03）

本次在已有未提交修改上继续实现，未提交 Git、未公开发布，保留了原有 iOS 下载管理修改。原始回归链接保存在 `testdata/platform-links.private.json`：19 类、38 条；主链接与备用链接属于不同作品，绝不把备用作品用作原任务的自动替代。

## 三端能力与限制

| 能力 | Android | Windows | iOS | 验收与限制 |
|---|---|---|---|---|
| 视频解析与高画质 | 既有引擎与原生适配 | 既有引擎与原生适配 | 原生适配，能力不同 | 画质取决于实际格式、账户权益和网络 |
| 图文原图 / ZIP | 已实现，解析通过，保存待补测 | 原图/ZIP及打包程序实际通过 | 未新增图文引擎 | 图片均重新绑定原作品 ID |
| YouTube Cookie | Keystore，文件导入，解析与下载传入 | DPAPI，文件导入，解析与下载传入 | Keychain + 非持久 WKWebView | iOS 未进行 SDK 构建或设备验收；无真实账号 Cookie 验证 |
| YouTube MP4 / AAC | yt-dlp 与账户会话 | yt-dlp 与账户会话 | 本机播放后取得的完整 MP4/AAC | iOS 有格式与前台限制，不等同完整 yt-dlp 能力 |
| 界面样式 | Android 参考样式 | 保留网页样式，新增独立图文页 | 同步主色、卡片、导航和深浅色 | iOS 真机、大字体、iPad 待验收 |

## 已实现

- Android / Windows 独立图文页：抖音、Pixiv、小红书、X、Pinterest；选图、全选、原始格式下载、顺序 ZIP。复用任务队列，重新定位原作品及所选图片 ID，拒绝推荐内容、HTML 错误页、无效图片和过大资源。ZIP 清单不含签名链接或 Cookie。Android 使用原有 MediaStore / SAF 导出及导出重试。
- 小红书只把平台明确返回的原图资源标为原图；其他资源显示“来源待确认”。没有把处理过的图片宣称为已去水印，不承诺所有资源均无水印。
- X 修复：无宽高但有真实 `tweet_video` 流的 GIF 不再被丢弃；严格识别的无声音动图默认采用仅画面 MP4。连接重置、临时服务器错误及媒体未确认会启用独立公开接口；公开接口不携带账号 Cookie，不合并引用帖或推荐帖媒体。多媒体位置保留原始媒体数组序号。
- Android 增加经过校验的 Requests 纯 Python 依赖，解决 HTTPS 代理因缺少依赖立即失败的问题。当前手机代理实际还返回 HTTP400，经用户明确选择清空应用代理，保留系统 VPN。原生 Android TLS 公开接口与 Python 公开接口形成有上限的备用路径；HLS 枚举设置较小预算，避免长期卡在解析。
- X 解析快照在内存短时保留，下载使用同一媒体信息，不重复解析帖子。账号导入时间、会话版本和网络配置参与隔离；过期后只刷新原媒体。不能定位原媒体时要求重新解析，不能下载别的媒体。
- Windows 备用解析不再修改已缓存 API 选择的同一个 yt-dlp 实例。
- 抖音优先目标页面 SSR，再尝试严格 ID 匹配的 Feed；提取全部可确认画质。识别浏览器验证页，图文不冒充视频。小红书移除全页面任取第一个 masterUrl 的兜底，按目标笔记读取、按实际像素排序。
- YouTube Cookie：Android / Windows 独立导入和更新入口、对应官方导出说明、解析与下载共用平台 Cookie 文件；Android 认证错误直达会话设置。文件存储分别使用 Keystore / DPAPI，临时文件在请求后清理。
- iOS 界面同步 Android 语义配色、首页品牌区、卡片、任务标签、下载配置与胶囊导航；保留 iOS15 最低版本，iOS15 使用配色后的原生底栏。
- iOS YouTube：Netscape 文件导入、本机 Keychain ThisDeviceOnly 存储、非持久 WKWebView 恢复会话；页面实际取得完整 MP4/AAC 地址后，核对原视频 ID、媒体 ID、itag、长度和 MIME。仅在内存保存签名地址；前台下载、仅画面导出、音画合成、M4A、取消和重试，成品核对时长、轨道、尺寸。直播、DRM、分段资源及未取得完整地址的格式返回受限，不伪装完成。

## Windows 实际链接验证

以下为每个原始样例的最新解析结果；“通过”只指元数据解析成功，不能等同于整部视频已下载。X 另以四个样例清空缓存连续三轮，12 次成功；独立公开路径四个样例成功。

| 用例 | 主链接 | 备用链接 |
|---|---|---|
| bilibili_single | 通过 | 通过 |
| bilibili_collection | 通过 | 通过 |
| douyin_video | CHALLENGE_REQUIRED | CHALLENGE_REQUIRED |
| kuaishou_video | EXTRACTOR_CHANGED | EXTRACTOR_CHANGED |
| xhs_video | 通过 | 通过 |
| youtube_video | CHALLENGE_REQUIRED | CHALLENGE_REQUIRED |
| tiktok_video | 通过 | 通过 |
| x_video | 通过 | 通过 |
| x_gif | 通过 | 通过 |
| instagram_video | 通过 | 通过 |
| facebook_video | 通过 | 通过 |
| pinterest_video | 通过 | 通过 |
| heybox_video | BROWSER_ASSIST_REQUIRED | BROWSER_ASSIST_REQUIRED |
| pixiv_ugoira | 通过 | CONTENT_UNAVAILABLE |
| douyin_images | CHALLENGE_REQUIRED | CHALLENGE_REQUIRED |
| pixiv_images | 通过 | 通过 |
| xhs_images | 通过 | 通过 |
| x_images | 通过 | 通过 |
| pinterest_images | 通过 | 通过 |

已实际下载 Pixiv、小红书、X、Pinterest 的原图及 ZIP 共 8 个队列任务。桌面 EXE 和网页 EXE 都实际启动，预览接口 HTTP200，并分别完成一个 Pixiv ZIP 任务。未批量下载整部合集或大型视频。

## Android 真机

设备为 vivo V2352A / Android15 / ARM64。起始安装为 code51；补齐依赖后仍复现代理 HTTP400，用户授权改用系统 VPN。安装的 Python 运行时公开接口四个 X 样例三轮共12次成功。这不等同于最终 APK 界面流程通过；最终 Release 界面新层级验证为 10/12：X 视频/GIF 四条、Pixiv 图文两条、X 图文两条、Pinterest 图片两条成功；小红书图文两条为 AUTH_REQUIRED。JSON 报告包含逐条结果。2026-11-03 用户选择先交付，Android 实际原图/ZIP/GIF 保存留待后补，没有把解析通过当成保存通过。

调试 androidTest 不能直接作为混淆 Release 的验收组件，尝试时出现测试依赖缺类；未把测试框架崩溃当作平台解析失败。改用真实 Release 页面验证。UI 回归工具删除旧层级文件、区分图文和视频完成界面，避免读取失效层级造成假通过；通过分享入口传递完整原链接，避免输入法改写 URL。

## 边界与待验收

- 不能保证 X 任意时段百分之百成功。代理服务、系统 VPN、平台限流、删除或受保护内容仍可能失败；应根据错误码处理，不能无限重试或换成别的作品。
- 本次 YouTube 样例在无有效账号会话时触发人机验证。已验证 Cookie 参数、域过滤、加密和清理，不宣称真实账号验证已通过。
- 抖音返回浏览器验证页；需要用户在平台完成验证并更新会话。快手当前样例结构变化尚未解决。小黑盒动态 SPA 未提供公开媒体时仍需浏览器辅助。Pixiv 一个备用动图资源接口返回404。
- Native OkHttp 平台的 TLS 到代理服务器及带认证 SOCKS 兼容性仍需专项补全；本机使用经授权的系统 VPN 配置，不能把 yt-dlp 的 HTTPS 代理依赖修复等同于所有原生适配器的代理兼容性验收。
- iOS 仅完成22个 Swift 文件语法、工程引用及5个浏览器脚本样例检查。Windows 无 Apple SDK / Xcode / iPhone，因此未编译、签名或运行 iOS；没有 IPA。iOS YouTube 需保持前台，且只支持可确认的完整 MP4 / AAC，不保证所有 YouTube 视频或4K。

## 复现与交付

- `tools/device_regression.py`：匹配 Debug 安装的引擎回归；不能拿它直接测试混淆 Release。
- `tools/device_ui_regression.py`：Release 页面回归。
- `tools/device_python_probe.py`：已安装运行时的无账号网络对照，不等同 UI 验收。
- `tools/windows_regression.py`：Windows 应用解析入口，循环前清缓存。
- `tools/run_android_unit_tests.py`、`pytest windows/tests`、`tools/check_ios_sources.py`：本地验证。
- `tools/package_python_extras.py`：重建校验过的 Android Python 依赖包；修改共享解析器后应重新执行。

APK 与 iOS 源码压缩包在 `release/1.7.0`，Windows 两种版本在 `windows/release`。SHA256 清单另附。未修改用户电脑正在运行的旧版本；临时测试实例使用独立数据目录。
