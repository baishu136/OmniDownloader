# OmniDownloader 1.7.6 更新与验收（2026-11-04）

本次交付 Android 与 Windows，Android versionCode 为 65。保留原有 Cookie 禁用开关、加密会话及旧版安装产物；未修改 iOS，未公开发布，也未提交或回滚工作区已有改动。

## 结果与边界

| 平台 / 能力 | 本次实现 | 实际验证与当前限制 |
| --- | --- | --- |
| 抖音视频 | 移除会返回推荐作品的旧 Feed 备用请求；读取移动分享 SSR、RENDER_DATA 与目标专用详情；枚举不同码率、AVC/HEVC 与备用地址，按实际尺寸排序 | 新报视频的匿名页面只提供作品 ID，详情接口未提供目标媒体，返回可操作的 `BROWSER_ASSIST_REQUIRED`。保留严格 ID 校验；未宣称该视频匿名自动解析或高清下载已通过 |
| 抖音图文 | Android / Windows 增加目标图文浏览器辅助提取；读取已渲染状态和平台正常请求响应；再次校验作品 ID，只保留该作品图片 | Android 真 WebView 的首方响应观察、目标/推荐分离与状态导出测试通过；真实账号登录、验证码及该新分享帖的网页提取尚未验收。不会自动完成验证或取得账号本无权访问的内容 |
| 快手视频 | 识别 `chenzhongtech.com/fw/photo/…` 分享落地域名并映射回同一作品；统一完整桌面 UA；解析目标 Apollo 实体，阻止落入不支持快手的通用解析器 | 新报作品 `3xqjmdgqyr7u7xc` 在 Android、Windows 源码及 Windows 独立包实际下载成功，720×1280、约 29.5 秒、含音轨 |
| 快手图文 | 新增 INIT_STATE 图集解析；校验 SSR 缓存作品 ID，存在时再校验 shareObjectId；读取 atlas 图片尺寸、CDN 和顺序 | 两个分享帖分别解析 10 / 6 张。主帖单图及 10 图 ZIP 在 Android 保存成功；Windows 源码 ZIP 保存成功 |
| 小红书视频 | 保留完整转码清晰度/编码/备用地址；新增 `originVideoKey` 原始源探测；通过有界 HTTP Range 读取 MP4 moov/tkhd 实际尺寸，再参与清晰度排序 | 备用样例原始源实际保存 **3840×2160、132.245 秒、含音轨，791,858,434 字节**；普通 720p 通道也通过。并非每篇笔记都有可访问原始源或 4K |
| Instagram | 解析后下载复用元数据快照；网络断连只重试一次；明确受众限制归类为 ACCESS_DENIED | 新报 `DceA6G7ywLu` 在 Android、Windows 均返回平台受众限制，未宣称该内容下载成功；需要在对应账号网页中确认访问权限 |
| Pinterest | 专用 Pin 解析保留身份和原生视频分组；HLS 请求失败不丢弃独立 MP4；CDN/清单连接失败归为 NETWORK_ERROR | 新报 Pin 在 Android 实际保存成功；Windows 源码按最高可用清单源保存 720×1280、8.57 秒、含音轨。此前也观察到短链/CDN TLS 断连，不能保证网络始终稳定 |
| Pixiv Ugoira | 保留帧 ZIP、MP4/GIF 通道；网络失败有界重试；404 提示区分作品删除、匿名限制或账号权限，明确检查 Cookie 是否启用 | 主样例 Android / Windows 实际 MP4 保存成功；后续 Android 复查又遇到 NETWORK_ERROR，表明传输仍会受网络影响。备用作品返回 404，未认定为必然已删除，也未伪造下载成功 |
| 小黑盒 | 修正 NO_MEDIA 包裹浏览器辅助提示的错误分类，直接返回 BROWSER_ASSIST_REQUIRED 并显示已有操作入口；Windows 辅助窗口增加作品身份检查 | 两个分享页均仅返回 SPA 外壳，没有静态公开视频源。仍需内置浏览器打开并播放目标视频；真实网页播放捕获尚未验收，未宣称后台自动解析全面支持 |
| X 短循环视频 | MediaMetadataRetriever 失败后尝试 MediaExtractor，再使用随包 FFmpeg 读取真实媒体信息；正确处理无音轨、亚秒视频，避免误把日志中的 `[0x1]` 当尺寸 | 新报作品实际保存 **650×430、0.55 秒、无音轨、48,563 字节**。Android 与 Windows 均通过；成品校验仍会拒绝无法识别、尺寸不足或缺少明确预期音轨的文件 |
| B站动态图文 | 支持 t.bilibili.com 动态、opus 图文、短链；兼容动态 draw/opus 和 opus 专栏正文；只提取正文图片，不使用头像、视频封面或转发原文代替；尊重付费/权限模块 | 使用公开 GIF 图文验证，4 张 GIF 的单图及 ZIP 在 Android 保存成功；Windows 源码和独立包的 4-GIF ZIP 均通过，保留 GIF 原始字节 |
| 软件内图片查看 | Android 解析页缩略图、已完成图片/封面/GIF 任务打开内置查看器，支持缩放、逐张浏览及结果页保存单图；Windows 增加模态图片查看器、滚轮缩放、方向键切换及任务图片入口 | Android Compose 图片切换/关闭测试通过，使用已有 GIF/WebP 解码器。Windows 页面脚本和打包资源验证通过，但本次隐藏运行没有可供 UI 自动化选择的窗口，未完成桌面可视界面验收 |

## 关键实现

### 目标内容、会话与媒体快照

- `windows/engine/media_resolver.py` 作为 Android 随包 Python 与 Windows 的共享图文/专用视频解析模块；`tools/package_python_extras.py` 同步依赖归档和浏览器响应观察脚本。无需新增加密签名服务或第三方解析站。
- Android `DownloadEngine` 将单作品抖音、Pinterest 交给专用链路；Instagram/Pinterest 下载也复用解析快照，减少二次解析造成的格式变化。
- 新增图片短期私有快照，按作品、平台、会话代次和代理配置绑定，10 分钟有效；浏览器提取出的图片可直接下载，避免再次访问匿名分享页后丢失目标。更换会话、切换 Cookie 开关或代理配置后，不复用旧快照。快照和签名 URL 不进入导出 ZIP 的 manifest。
- Android 图文辅助浏览器沿用桌面身份、登录弹窗处理和 Cookie 恢复屏障；Cookie 禁用时需要用户明确开启后才能进入。正常网页响应观察只关注目标平台的作品接口，不读取请求 Cookie、令牌或认证头；返回的图集再次进行目标 ID 校验。
- Windows 辅助入口也检查当前页面与目标作品身份；抖音图文可在辅助窗口加载目标、完成验证后回到图文页点击提取。Web/托盘版没有独立桌面辅助窗口时会明确提示。

### 原始画质与验证

- 小红书原始源只使用目标笔记提供的 originVideoKey，限制安全路径；探测每个 HTTP Range 最多 1 MiB，优先读取头部，必要时读取尾部。不通过修改任意视频 URL 的清晰度参数假造高清源。
- MP4 尺寸解析要求完整、边界合法的 moov/trak/tkhd；无法取得可信尺寸的原始源不冒充高分辨率进入默认排序。
- 抖音保留所有真实码率和备用播放地址，取消“拿第一个 play_addr 即完成”的单源行为；账号或页面没有返回高清流时，不放大低清文件或伪造 1080p/4K。
- X 成品校验备用探测使用本地已下载文件，20 秒上限，不重新请求远端视频；真实样例系统读取器返回空，FFmpeg 能正确探测 650×430 / 550ms / 无音轨。

## 验证记录

- **96 项 Android JVM 测试通过**，包括新增快手分享域名、原始源安全路径、MP4 Range 尺寸解析、亚秒视频和错误归类回归。
- **54 项 Windows/Python 测试通过**，包括目标图集/动态正文匹配、GIF 保留、Pinterest HLS/MP4 独立性、Instagram 断连重试与访问限制不重试、静音视频实际传输、会话策略等。
- **4 项 Android 设备组件测试通过**：真实 X 文件备用探测；WebView 状态提取；文档开始脚本观察首方响应并拒绝推荐作品；图片查看器切换/关闭。
- Android 测试环境为自建 API 35 x86_64 模拟器。国内直连，海外测试通过开发机现有 HTTP 代理桥接；未修改用户应用持久代理。发布 APK 为 arm64-v8a，**本次没有连接到实体手机**。
- Android 实际保存覆盖快手视频/图文、小红书普通视频和 4K 原始视频、Pinterest、Pixiv 动图主样例、X 短循环、B站 GIF 图文；所有验收输出经过媒体尺寸/时长/音轨或图片解码/ZIP 数量校验，测试输出随后清理。
- 小红书 4K 原始文件约 755 MiB。第一次 210 秒、第二次 420 秒的测试上限分别中断传输；第三次复用该测试任务的已下载部分，最终续传、校验、系统下载目录导出全部成功。前两次记录保留，不把中断记录当成功，也不据此承诺一次请求的下载耗时。
- Windows 源码实际下载 6 组：快手视频、快手 10 图 ZIP、B站 4-GIF ZIP、Pinterest、Pixiv 动图主样例、X 短循环。最终桌面独立 EXE 已实际启动，并通过其 API 完成快手视频及 B站 4-GIF ZIP 下载；Web/托盘版本仅构建与归档校验，未单独启动。
- Android 发布构建含 R8/资源压缩及 lintVital 检查；Windows 两种版本打包成功；APK/ZIP 归档、共享脚本内容、arm64 ABI、APK 签名及 SHA-256 均验证。Windows JavaScript 通过 Node 语法检查。

机器可读结果见 `TEST_RESULTS_1.7.6.json`，原始私有诊断保留于 `.local/1.7.6/`。用户本次链接保存在忽略提交的 `testdata/reported-2026-11-04.private.json`，原有平台主/备用链接继续保留。未导出、打印或替换真实账号 Cookie。

## 安装与待验收

- Android：`release/1.7.6/OmniDownloader-1.7.6-arm64-v8a.apk`。包名与本地既有签名保持一致，可覆盖本地旧版，保留应用数据；未升级最低 Android 版本。
- Windows：`windows/release/OmniDownloader_Desktop-1.7.6.zip`；另提供 `OmniDownloader_Web-1.7.6.zip`。
- 校验：`docs/SHA256SUMS-1.7.6.txt`。
- 仍待真实账号/真机验证：抖音新视频及图文登录后的实际提取、Instagram 受众权限、Pixiv 备用作品权限、小黑盒网页播放捕获，以及 Android 真机和 Windows 可视界面的图片操作。缺少平台授权、验证或网络可达性时仍会明确失败，不会用其他作品替代。

## 参考

原始源处理对照 yt-dlp 随包小红书 extractor，并查阅上游 [Instagram 实现](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/instagram.py)、[Pinterest 实现](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/pinterest.py)、[Android MediaExtractor 文档](https://developer.android.com/reference/android/media/MediaExtractor) 和 [pywebview API](https://pywebview.flowrl.com/api/)。本次没有盲目升级解析器依赖。
