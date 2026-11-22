> 后续实施记录：请以 [1.6.0 实施与验收记录](IMPLEMENTATION_1.6.0_2026-11-02.md) 为最新状态。以下保留 1.5.16 阶段的历史记录，其中未验收项目不应解释为平台成功保证。

# M0/M1/M2/M3 实施记录（Android 1.5.16）

实施日期：2026-11-02  
对应规划：`OmniDownloader更新规划-2026-11-02.md`  
基线：`main@5ff8ff4`（Android 1.5.10 / versionCode 51）  
本次结果版本：Android 1.5.16 / versionCode 57

## 已完成

### M0 诊断基础

- 新增 `engine/MediaErrors.kt`：稳定错误码（`AUTH_REQUIRED`、`CHALLENGE_REQUIRED`、`RATE_LIMITED`、`QUALITY_UNAVAILABLE`、`NETWORK_ERROR`、`CONTENT_UNAVAILABLE` 等），错误文本与 HTTP 状态统一归类。
- 新增 `engine/LogSanitizer.kt`：去除 Cookie/Authorization、签名查询参数、URL userinfo；错误详情和日志共用。
- `DownloadEngine.formatDetailedError()` 改为输出 `[CODE] 用户提示 (脱敏详情)`。

### M1 链接与会话边界

- 新增 `engine/LinkNormalizer.kt`：
  - 平台识别基于解析后的 host，修复 `contains("x.com")` 误命中 `netflix.com` 一类域名；
  - 按平台保留访问参数：小红书全部保留、抖音 `modal_id/mix_id`、B站 `p/sid/season_id`、YouTube `v/list/index`；
  - 小红书 `/login?redirectPath=...` 只解一层，不破坏 token 中的 `%3D`/`+`；
  - 短链最多 5 跳，逐跳识别登录、验证码、404/410，并返回明确状态。
- `UrlSniffer` 委托 LinkNormalizer；新增 `resolveContent()`；跳转使用禁止自动跟随的 OkHttp 客户端，避免把登录页当成内容页。
- 小红书原生解析：按 `note.noteDetailMap[noteId].note` 读取视频流，区分 h264/h265/av1；登录页、验证码页、图文笔记分别返回明确错误；仅结构化解析失败时才使用保守正则回退。

### M1 引擎选项与下载计划

- 新增 `engine/YtDlpOptionsFactory.kt`：解析、下载、重试共用一套参数生成逻辑。
- 移除 YouTube 强制 `android,ios,mweb` 客户端，恢复上游默认提取策略。
- `--no-playlist` 仅在单视频 URL 上启用；合集/播放列表 URL 允许展开 entries。
- “安全通道”重试不再改成 `best/bestvideo+bestaudio`，保留原选流、站点请求头、代理和导出类型，只调整输出目录与重试次数。
- 解析结果按 `format_id` 保留真实格式，不再用第一个同高度格式代表全部编码；记录 vcodec/acodec/fps。

### M1 媒体定位与结果模型

- `MediaSelection`：删除按过滤后序号拼 `/video/N` 的逻辑；只使用平台明确返回的子页面地址或该 entry 自己的直链。
- `data/model/Models.kt`：
  - 新增 `QualityMode`、`QualitySelection`、`qualitySelectionFromResolution()`；
  - `FormatOption` 新增 vcodec/acodec/isSynthetic；
  - `DownloadTask` 新增 schemaVersion、质量意图、精确格式 ID、muxed 标记、errorCode。
- `TaskRepository` 读取旧任务时迁移到 schemaVersion 2（旧 `selectedResolution` → 质量意图），不重新下载、不修改已完成记录。
- `MainViewModel` 创建任务时写入质量意图与精确格式；合集中的每个分集独立校验格式是否存在。
- 新增 `engine/MediaVerifier.kt`，在 `DownloadService` 标记完成前用 `MediaMetadataRetriever` 校验真实高度；明显低于所选档位时返回 `QUALITY_UNAVAILABLE`，不再显示普通高清完成。

### M1 会话与凭据（第二批）

- 新增 `data/repository/SessionRepository.kt`：
  - Cookie 文本使用 Android Keystore 管理的 AES/GCM 加密后存入 SharedPreferences，不落明文；
  - 导入时校验 Netscape 格式、1 MiB 上限、平台域范围与过期时间，只保留目标平台 Cookie；
  - UI 仅暴露 `MISSING / IMPORTED / NEEDS_AUTH`、导入时间与 Cookie 条数；
  - 为 yt-dlp 生成应用私有缓存目录下的临时 Netscape 文件，任务结束即删除，启动时清理残留；
  - 会话代次在导入/清除时递增。
- 新增 `engine/RequestContext.kt`：把同一平台会话分别适配为原生 Cookie 请求头与 yt-dlp 临时 cookie 文件。
- yt-dlp 解析/下载/重试统一通过 `--cookies` 使用会话；小红书原生页面请求与直链下载使用同一 Cookie 请求头。
- 设置「账号」页新增“平台会话”卡片：平台选择、导入 Cookies 文件、清除、状态显示；认证失败自动标记为“需重新验证”。
- 引擎子页新增“组件版本”：读取实际安装包内的 yt-dlp 与包装库版本。

### M3 Pixiv Ugoira（第三批）

- 新增 `engine/PixivExtractor.kt`：
  - 识别 `/artworks/{id}`，读取 `ajax/illust/{id}` 与 `ugoira_meta`；
  - 仅支持 `illustType == 2` 的 Ugoira；插画、漫画、Sketch、FANBOX 返回明确的不支持结果；
  - 导出选项：MP4（H.264，保留每帧时长）、GIF（调色板）、原始帧资源（ZIP + frames.json）；
  - 使用 Pixiv 会话档案的 Cookie，请求带 Referer/UA。
- 新增 `engine/UgoiraProcessor.kt`：
  - 按元数据帧序而不是 ZIP 条目顺序解压；
  - 拒绝绝对路径、盘符、`..` 穿越、超限条目与超大帧包；
  - FFmpeg concat 清单使用每帧 `delayMs/1000`，末帧重复以保留末帧时长；
  - MP4 优先 libx264，缺失时降级 mpeg4；GIF 优先调色板，失败时降级直接编码；
  - 导出完成前扫描媒体库，失败清理任务暂存目录。
- FormatSelectorSheet 在 Pixiv 下隐藏无意义的音频提取选项。
- 抖音/快手合集链接不再由原生单视频解析器静默处理，改为交给可展开列表的通用引擎，避免“合集只下载原视频”。

### M2 小黑盒（受限支持，第四批）

- 新增 `engine/HeyboxExtractor.kt`：
  - 把 `api.xiaoheihe.cn/.../web/share?...link_id=...` 规范为 `https://www.xiaoheihe.cn/app/bbs/link/{id}`；
  - 抓取分享页，从 `<video>`、`<source>`、`og:video` 与转义 JSON 中提取 MP4/HLS 地址；
  - 有 MP4/HLS 时返回可选格式，下载复用现有 HTTP/HLS 管线并携带 UA/Referer/Cookie；
  - 没有公开媒体源时返回 `NO_MEDIA` 并说明“受限支持”，不再把图文或空页面当视频。
- 样例验证（2026-11-02）：
  - `api.xiaoheihe.cn/v3/bbs/app/api/web/share?...link_id=6d637277cc84` 当前 302 到 `/app/bbs/link/6d637277cc84`，返回的是 SPA 外壳（约 3.9 KB），Googlebot UA 也无静态正文，页面内没有 MP4/HLS 地址；
  - 因此该样例目前会得到明确的“受限支持”结果；未凭空编造私有 API 字段。
- 小红书样例补充：
  - `xhslink.cn` 已加入短链与平台识别；
  - 分享短链会跳转到 `/login?redirectPath=...`，现已保留 `xsec_token` 并从登录包装恢复内容地址；
  - 图文笔记在取得内容页后按“非视频”返回 `NO_MEDIA`，不会进入视频下载流程（图片批量下载不属于当前规划）。

### 内置浏览器登录接取（第五批）

- 新增 `ui/components/PlatformLoginSheet.kt`：为 YouTube、X、小红书、小黑盒、Pixiv 提供应用内 WebView 登录页，登录完成后从 CookieManager 读取 Cookie 请求头。
- `SessionRepository.importCookieHeader()` 支持导入 WebView 得到的 `name=value; ...` 头，按平台主域生成会话项。
- 设置「账号」页平台会话卡片新增“登录获取会话”按钮。
- 已知限制：WebView 只能提供请求头，无法还原完整 domain/path/expiry；该限制已在 UI 与文档中说明。

#### 关于 Chrome 已登录态

尝试从桌面 Chrome 的 Cookies SQLite 直接读取小黑盒会话时，Chrome 运行期间独占锁定 Cookie 数据库（WinError 32），无法在不关闭 Chrome 的情况下复制；因此改用应用内 WebView 登录作为可交付路径。若需要复用 Chrome 会话，请导出 Netscape Cookies 文件后在设置中导入。

#### 关于“新的小黑盒视频链接”

本轮收到的消息中，链接位置实际是项目仓库 `https://github.com/baishu136/OmniDownloader`，不是小黑盒视频分享链接。因此未对新视频样例做网络验收；小黑盒解析器仍按受限支持工作，需要真实分享链接继续验证。

### 小黑盒浏览器辅助捕获（第六批）

- 新样例 `link_id=b95171fffbb9`：分享 API 仍 302 到 SPA 外壳；页面 HTML 不含公开 MP4/HLS；直接调用已知 API 路径返回 404 或 `请求失败了`，说明网页端请求带签名/会话，无法在后台静态解析。
- 新增 `ui/components/MediaCaptureDialog.kt`：在应用内 WebView 打开分享页，共享 CookieManager 会话，拦截实际播放的 MP4/HLS 请求；捕获后直接进入现有直链/HLS 下载管线。
- 首页解析失败且错误包含 `BROWSER_ASSIST_REQUIRED` 时，错误弹窗提供“用内置浏览器捕获”；页面中点击播放即可自动入队。
- 使用流程：
  1. 设置 → 账号 → 小黑盒 → “登录获取会话”（应用内 WebView 登录，或导入 Netscape Cookies）；
  2. 首页粘贴小黑盒分享链接并解析；
  3. 若提示受限，点击“用内置浏览器捕获”，在页面中播放视频。
- Chrome 已登录态仍无法直接复用：Chrome 运行期间独占锁定 Cookie 数据库（WinError 32）。替代方案是应用内 WebView 登录或导出 Netscape 文件。
- 未在真机上验证 WebView 捕获；该能力依赖页面实际发起可识别的媒体请求。

## 测试

- JVM 单元测试 56 项全部通过，其中新增：
  - `LinkNormalizerTest` 9 项（host 识别、小红书参数/登录解包/token 不解码、抖音合集参数、YouTube list、B站 p、短链、旧清晰度迁移）；
  - `MediaErrorsTest` 4 项（错误分类、HTTP 状态优先、错误码与脱敏）；
  - `NetscapeCookiesTest` 5 项（域过滤、过期过滤、HttpOnly、序列化往返）；
  - `UgoiraProcessorTest` 4 项（总时长、concat 末帧重复、ZIP 路径安全、空帧）；
  - `HeyboxExtractorTest` 3 项（MP4/HLS 提取、去重、空页面）；
  - `CookieHeaderParserTest` 2 项（浏览器 Cookie 头解析、空头处理）；
  - `LinkNormalizerTest` 新增小黑盒分享链接与 `xhslink.cn` 用例。
- `:app:assembleDebug`、`:app:assembleRelease`（R8 + 资源裁剪）通过。
- 说明：本仓库路径含中文，Windows 下 Gradle 测试 worker 的 argfile 编码会导致 `ClassNotFoundException`；测试在 ASCII 路径副本中执行，源码与依赖相同。

## 未实施（后续阶段）

- 内置浏览器登录/验证接续（`WAITING_FOR_AUTH` 完整流程）；当前只返回明确状态并标记需重新验证。
- YouTube/X/小红书之外平台的会话验证结果回写（当前仅认证失败时标记 NEEDS_AUTH）。
- T01/T06 引擎与运行时探测：Python、yt-dlp、EJS、FFmpeg/ffprobe 版本清单；PO Token / Provider。
- 抖音/快手合集：已避免原生单视频解析器误吞合集链接，但尚未实现专门的 mix/collection 适配器；实际展开依赖 yt-dlp 能力。
- 小红书登录后 `WAITING_FOR_AUTH` 状态接续与内置浏览器桥接。
- M2 小黑盒、M3 Pixiv Ugoira。
- Windows / iOS 对应改动。

## 已知限制

- 未做真机或真实网络样例验收；本记录不代表 YouTube/X/小红书端到端已通过。
- 会话导入未在真实 Cookies 文件上验收；加密存储依赖设备 Keystore，异常时导入会失败并提示。
- 迁移为内存内转换，尚未保存旧数据快照。
- 音频轨道缺失未强制判失败，仅校验视频高度。
- Pixiv 已用 `artworks/151591764` 验证 illust/ugoira_meta 字段（193 帧、1920×1080 帧包）；该样例 `urls` 为 null，因此封面可能为空。
- 小黑盒样例未取得公开视频源；Pixiv Ugoira MP4/GIF 的实际编码结果依赖打包 FFmpeg 的编码器与时间基。
- 合集/播放列表展开仍受平台提取器能力限制。
