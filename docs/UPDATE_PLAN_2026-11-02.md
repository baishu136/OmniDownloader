OmniDownloader 完整更新规划与实现设计

制定日期：2026-11-02（Asia/Shanghai）  
审阅基线：`main@5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6`，Android v1.5.10。  
文档性质：待实施设计。本轮仅维护本规划文件，没有修改项目源码、依赖或用户会话，没有运行下载验收。下文新增类名、字段名、接口和版本号均为建议；远端网站接口的可用性以实施时样例验证为准。已补充用户提供的 B站 ENOENT、X 无视频错误截图，以及抖音图文误指向问题，详见第19–22项。

**1. 更新目标。** 将已有平台的可诊断性、媒体定位和下载正确性作为第一批交付，再扩展小黑盒视频及 Pixiv Ugoira。公开内容应尽可能直接解析；需要会话或人工验证时，产品应准确说明原因，并在验证完成后接续原任务。

最终需要解决的用户体验：

- YouTube：能区分“访问验证失败”“只能取得低清格式”“选错格式”“合并失败”；选中高清后不静默保存 360P。
- B站：携带有权限账号的会话解析充电专属视频，区分完整内容与试看；下载成功后的文件导出失败能够单独恢复。
- 抖音：严格匹配作品 ID，图文帖不再落到推荐视频；首批先正确识别图文内容，再提供适用图片导出。
- X：多视频、图文混排、引用推文中选中的媒体与成品一致；网络、认证、限流和无视频错误分别处理。
- 小红书：保留分享链接的访问参数；登录页、验证码页不再当成视频页传递到后续流程。
- 小黑盒：先支持经验证的单个公开视频帖，覆盖 MP4 与常见非 DRM HLS；能力不足时明确标记受限支持。
- Pixiv：支持 Ugoira 原始帧资源保存及 MP4/GIF 导出，保留每帧时长；普通插画、多页漫画、Sketch、FANBOX 分别定义范围。
- Android 与 Windows 使用一致的数据语义、错误码和验收样例，保留各自现有技术栈。

本规划不新增云解析服务、跨端同步或远程控制。用户登录及媒体解析默认在本机完成，不把 Cookie 或带签名链接发送给第三方解析站。

| 能力 | Android 交付目标 | Windows 桌面/网页后台目标 | iOS 处理范围 | 前提及限制 |
| --- | --- | --- | --- | --- |
| YouTube 修复 | 完整修复目标，需 ARM64 真机验收 | 完整修复目标，两种安装形态都验收 | 等价支持另设技术验证，当前不宣称具备同一引擎 | 实际 Python、JS/EJS、FFmpeg 可用；受内容及网络限制 |
| B站充电专属 | 有权限账号的完整流解析、试看检测及成品校验 | 同等行为 | 可移植到现有 B站原生引擎，另测 | 账号拥有目标视频完整观看权限，不承诺所有内容均可下载 |
| 抖音图文纠错 | 严格作品身份；区分图文/视频 | 同等行为 | 先移植识别约定，再验收 | 图片导出作为独立内容类型，不默认合成为视频 |
| X 修复 | yt-dlp 适配及稳定媒体定位 | yt-dlp 适配及每段独立格式选择 | 会话及媒体模型可跟进，通用提取能力单独验证 | 部分内容需有效访问权限 |
| 小红书修复 | 参数、重定向、会话及结构化解析 | 同等行为 | 原生提取器按同一行为约定跟进 | 浏览器可能要求验证 |
| 小黑盒视频 | 专用解析器；必要时内置浏览器捕获 | 专用解析器；Cookie 文件导入 | 分享页可行性验证后移植原生适配器 | 首版限定单个视频帖；网页取流能力需样例确认 |
| Pixiv Ugoira | 原生 HTTP＋ZIP＋FFmpeg | HTTP＋ZIP＋FFmpeg | 可用原生帧编码方案另行验收 | 会话要求随作品变化；动画导出需要编码器 |

**2. 修复依据。** 以下问题已经通过源码确认，和仍需复现的判断分开管理。

| 编号 | 已确认的实现行为 | 风险或影响 | 实施决策 |
| --- | --- | --- | --- |
| B01 | Android 的 `UrlSniffer` 删除小红书全部查询参数 | 丢失 `xsec_token`、来源等访问上下文 | 按平台保留参数，不再统一截断查询串 |
| B02 | 小红书最终可跳到 `/login`，原生请求与 WebView 会话未统一 | 原生解析失败后可能把认证问题变成 Unsupported URL | 保存内容地址；认证/验证作为独立结果 |
| B03 | Android 强制 YouTube `android,ios,mweb` | 覆盖上游默认客户端及后续修复 | 常规路径使用引擎默认客户端 |
| B04 | 管道错误重试使用 `best/bestvideo+bestaudio`，并重建不完整参数 | 可能优先低清合一流，丢失原选择条件 | 重试只调整传输方式，保留同一下载计划 |
| B05 | Android 任务主要保存清晰度文案 | 无法可靠恢复具体格式、帧率、编码 | 增加结构化格式选择 |
| B06 | X 的部分子地址由过滤后序号拼 `/video/N` | 可能与原始媒体位置不一致 | 使用媒体 ID 和原始媒体位置 |
| B07 | Windows 多段任务复用首段格式 ID | 其他分段可能无该格式 | 每个子媒体独立选流，批量只共享质量策略 |
| B08 | Windows yt-dlp 日志器丢弃全部消息，启用 `ignoreerrors=True` | 原始原因难以定位 | 受控日志＋显式异常分类 |
| B09 | 小红书原生解析全局匹配首个 `masterUrl` | 可能误取页面其他资源 | 按笔记 ID 定位媒体结构 |
| B10 | 各阶段的代理、会话和请求头配置分散 | 解析成功但下载失败，或验证后仍不生效 | 集中生成请求上下文，贯穿完整任务 |
| B11 | 抖音目标 ID 未命中时，两端均退回列表第一项 | 推荐内容可能被当成目标图文帖 | 删除错配回退；返回身份不匹配并禁止下载 |
| B12 | Android 抖音网页回退取首个 play_addr/CDN 地址，链接还会把 note 规整成 video | 作品类型及资源归属可能丢失 | 保留内容类型；按 ID 定位对象再取资源 |
| B13 | B站导出先复制到公共路径，失败只改文件名继续复制到同一目录 | 无法修复目标目录不可用；首次异常被覆盖 | 统一存储导出器、保留源文件、分离导出错误 |
| B14 | B站现有原生解析没有专门校验充电专属完整流/试看 | 可能把试看当完整下载，或错误解释无流 | 增加权限与试看判定及明确结果 |

尚未确认：用户实际运行端、已安装包中真实 yt-dlp/Python 版本、X 失败链接、YouTube 示例当前最高可访问画质、小黑盒分享页目前返回的字段。不得把这几项写成已复现缺陷或已通过验收。

**3. 发布拆分。** 建议按下面顺序推进，版本号仅作为规划标识，不代表现有发布承诺。

| 阶段 | 建议版本 | 范围 | 发布门槛 |
| --- | --- | --- | --- |
| M0 | 内部诊断基线 | 样例、版本探测、日志、错误分类、最小模型草案 | 可判断失败所在阶段，无凭据泄漏 |
| M1 | 1.6.0 | 小红书、YouTube、X 修复；B站充电专属及导出修复；抖音图文纠错；必要模型与迁移；会话入口 | 五平台受影响场景回归通过；已有主要平台无阻断回归 |
| M2 | 1.6.1 | 小黑盒单视频帖，MP4/HLS，过期刷新 | 样例验证证明可稳定取得正确流；否则仅内部实验 |
| M3 | 1.7.0 | Pixiv Ugoira：元数据、帧包、MP4/GIF、原始资源 | 非等时帧、总时长、失败恢复、编码能力验收 |
| M4 | 后续 | 更多小黑盒内容类型、Pixiv 图片批量、iOS 对应能力 | 分平台列明实现及测试记录 |

M1 只建立被现有故障实际需要的抽象；M2/M3 在其上增加适配器，避免一次性重写 `DownloadEngine`。阶段内采用可独立审阅和回退的提交，不把所有平台改动堆进一个提交。

**4. 统一模型。** 在现有 `Models.kt` 和 Windows `core/models.py` 上增量扩展。核心约定如下。

| 模型 | 建议字段 | 用途及持久化策略 |
| --- | --- | --- |
| `MediaLocator` | `platformId`、`pageUrlRef`、`contentId`、`mediaId`、`originalMediaIndex`、`entryUrlRef?` | 内容页和具体媒体的稳定身份；敏感 URL 单独保护 |
| `QualitySelection` | `mode`、`maxHeight?`、`targetFps?`、`codecPreference`、`containerPreference`、`allowLowerQuality` | 用户意图，独立于 UI 文案 |
| `ResolvedFormat` | `formatId`、`width`、`height`、`fps`、`vcodec`、`acodec`、`container`、`protocol`、`bitrate?`、`size?` | 本次解析实际可用格式 |
| `MediaSource` | `kind`、`videoUrlRef?`、`audioUrlRef?`、`manifestUrlRef?`、`ugoiraResourceRef?`、`expiresAt?` | 区分合一文件、分离轨、HLS、Ugoira |
| `RequestContextRef` | `credentialProfileId?`、`networkProfileId`、`headerProfileId`、`sessionGeneration` | 持久化引用，任务记录不存明文 Cookie |
| `DownloadPlan` | `locator`、`quality`、`selectedVideoFormatId?`、`selectedAudioFormatId?`、`exportType`、`contextRef` | 可执行且可重建的下载计划 |
| `OutputMediaInfo` | `width`、`height`、`durationMs`、`videoCodec?`、`audioCodec?`、`container` | 完成前验证以及结果展示 |
| `PlatformCapabilities` | `contentTypes`、`exportTypes`、`authenticationModes`、`qualityModes`、`supportLevel` | 控制平台入口和适用导出选项 |

`QualitySelection.mode` 建议取 `BEST_AVAILABLE`、`MAX_HEIGHT`、`EXACT_FORMAT`。精确格式失效时返回“格式已变化”，或仅按预先保存的回退意图重新选择；不得把旧 format ID 应用到另一视频。

分辨率列表应将显示与选择分离：可以将同高度格式收进一个清晰度选项，但内部保留 FPS、编码、音轨及格式 ID；不再用第一个同高度格式代表全部编码。仅有标题或封面、没有可执行媒体源时，不能伪造 1080P/720P 可下载选项。

设计示意，实施时适配现有序列化方式：

```kotlin
data class QualitySelection(
    val mode: QualityMode,
    val maxHeight: Int? = null,
    val targetFps: Int? = null,
    val codecPreference: CodecPreference = CodecPreference.AUTO,
    val containerPreference: String = "mp4",
    val allowLowerQuality: Boolean = false
)

sealed interface ResolveResult {
    data class Ready(val items: List<ResolvedMedia>) : ResolveResult
    data class InteractionRequired(val challenge: ChallengeRef) : ResolveResult
    data class Failed(val error: MediaError) : ResolveResult
}
```

没有必要把 Kotlin 与 Python 强行共享成同一个运行时；共享 JSON Schema、错误码、fixture 和预期结果即可。

**5. 统一执行流程。** 沿用现有下载服务和队列，逐步把分散的策略集中到少数职责明确的组件。

```text
输入/分享文本
  → LinkNormalizer：解析 URL、平台识别、保留内容身份
  → SessionRepository＋RequestContextFactory：选择会话、代理、请求头
  → 平台适配器：解析元数据和媒体列表
  → FormatSelector：将用户意图解析为真实格式
  → DownloadPlan：保存稳定身份和可恢复选择
  → 下载器：HTTP / yt-dlp / HLS / Ugoira 帧资源
  → 后处理：合并、转封装、提取音频或动画编码
  → MediaVerifier：验证成品
  → 发布至用户目标目录，进入完成态
```

入口保留现有返回类型的兼容包装，避免一次改动所有 UI；内部使用结构化结果。旧的 `fetchVideoMetadata()`、`download()` 可以先委托新组件。

每次任务使用独立暂存目录，例如 `staging/{taskId}/{attemptId}/`。所有输出通过明确文件路径或 yt-dlp 后处理回调获取，不再用“目录中最新文件”猜测成品。验证通过前保持暂存态；最终落盘防止同名覆盖。取消后停止该任务及其子进程，迟到回调不能把已取消任务改成完成。

**6. 链接、会话和网络实现。** 这是三项修复与两个新平台共同依赖的部分。

链接处理具体规则：

- 平台识别改用解析后的 `host` 和精确子域规则，不在完整字符串中执行 `contains("x.com")` 等判断。
- 保留 `originalInput`、内容页地址、最近响应地址三种语义；只有内容页地址参与稳定身份计算。
- 短链跳转设置总预算和最多 5 跳；用相对 URL 正确解析 `Location`。HEAD 不可用时执行有响应大小限制的 GET。
- 小红书先保留完整查询参数。对 `/login?redirectPath=...` 只按外层参数规则解包一层，再解析内部 URL；不循环 URL-decode，不破坏 token 中的 `%3D`、`+` 等编码。
- 从登录页或验证码页恢复内容地址后，先进入验证状态，不能无限重试相同请求。
- 小黑盒保留 `link_id` 及实际证明需要的参数；Pixiv 提取作品 ID 时保留原输入以便诊断。
- 所有 URL、Cookie、代理凭据写日志前经过统一脱敏。例外信息和 yt-dlp 输出也要经过同一过滤器。

会话管理具体实现：

1. 为 YouTube、X、小红书、小黑盒、Pixiv 建立独立凭据档案，支持导入、替换、检查最近使用结果、删除。显示“已导入/已验证/需重新验证”，不能只凭存在 Cookie 文件显示“登录有效”。
2. 第一版可靠基线为 Netscape Cookie 文件导入：校验格式、站点范围、过期时间和文件大小；不输出值。Android 从系统文件选择器复制至应用私有区域，不能假设内容 URI 就是文件路径。
3. Android 使用 Keystore 管理的密钥加密凭据；Windows 使用用户级 DPAPI 等系统保护。运行 yt-dlp 时才生成权限受限的临时 Cookie 文件，任务退出即删除；启动时清理上次异常退出留下的过期临时文件。
4. `RequestContextFactory` 将同一凭据档案分别适配为原生 HTTP CookieJar 和 yt-dlp Cookie 文件。HTTP 按 Cookie 的域、路径、Secure、有效期发送，禁止把登录域 Cookie 无条件发往视频 CDN。
5. Android 内置浏览器用于小红书、小黑盒等人工验证。`CookieManager.getCookie()` 只有 Cookie 请求头，不包含完整 domain/path/expiry 元数据，不能将其宣传为无损导出。浏览器桥接按已验证的站点和请求地址提供会话快照；需要完整 Cookie jar 的场景仍保留文件导入方式。
6. 不假设 YouTube 允许嵌入式 WebView 登录。YouTube 首版优先浏览器正常登录后导入；Windows 读取系统浏览器 Cookie 属于可选入口，失败后提供文件导入，不通过改系统安全设置解决。
7. Cookie 切换或删除增加 `sessionGeneration`，失效相关解析缓存。后台任务使用明确的会话快照，不能在一次下载中途混入新账户。
8. 全链路共用 `networkProfileId`：短链解析、页面请求、媒体请求、yt-dlp、必要的 Provider 请求遵循一致网络配置。验证浏览器的实际出口需要说明及检查，不能假设 WebView 自动继承自定义 OkHttp 代理。

`DownloadTask` 中原有 `directMediaHeaders` 保留普通请求头；Cookie、Authorization 和带敏感参数的地址迁移至受保护资源存储，通过引用读取。

Windows 本地 API 只返回凭据档案状态，GET 设置接口不回传 Cookie。导入/删除接口使用当前安装形态的会话或本机访问约束；局域网网页后台不能新增匿名可调用的凭据管理入口。服务器端使用参数数组调用进程，不将前端传入的格式或 URL 拼接为 shell 命令。

**7. YouTube 的具体修复。** 先恢复上游合理默认，再处理日志证明存在的附加要求。

步骤 Y1：建立引擎版本清单。运行实际发布包中的 Python、yt-dlp、JS runtime、FFmpeg/ffprobe 版本命令；记录 EJS 可用版本和路径。Android v0.18.1 包装库与实际解压后的 Python/yt-dlp 分开检查，更新引擎后重新探测。

截至本次核查，yt-dlp 最新稳定版为 `2026.10.26`，要求 Python ≥3.11。Windows 项目使用 Python 包 API，应将 Python、yt-dlp、EJS 和打包依赖锁为一组已验证版本；在 PyInstaller 成品中验证资源实际被收集。Android 必须先确认内置 Python 是否兼容，再决定升级包装库或重建其运行时。不能通过只替换脚本完成未经验证的升级。[上游发布说明](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26)

步骤 Y2：解析、下载和重试统一经过 `YtDlpOptionsFactory`，常规路径移除强制 `android,ios,mweb`。上游已于 2026-10-10 合并默认 `tv_samsung` 客户端修复，其中包括年龄限制视频仅返回 360P 的问题；直接固定另一套客户端可能绕开该修复。不要再把 `tv_samsung` 长期硬编码成项目唯一客户端。[上游修复](https://github.com/yt-dlp/yt-dlp/pull/17805)

步骤 Y3：确认 JS runtime 和匹配的 EJS 均可用。Android 优先验证包装库已有 QuickJS；Windows 打包一个经过验收的 Deno 运行时，并使 Python API 能找到匹配的 EJS。构建阶段收集所需资源，运行时首次解析不能隐式依赖用户机器上恰好有 Node/Deno。具体 CLI/Python 参数按锁定版本文档与实际 API 验证，不将 CLI 参数名机械转换为 Python 字典键。[EJS 指南](https://github.com/yt-dlp/yt-dlp/wiki/EJS)

步骤 Y4：先匿名解析公开视频。遇到明确的需要登录/反机器人验证信息，结合当前网络和会话状态提示下一步；已有用户主动选择的凭据档案时，将同一 Cookie 上下文用于格式探测和下载。不要自动轮换多个账户重试。

步骤 Y5：引入明确的格式策略，避免静默低清：

| 用户选择 | 执行规则 |
| --- | --- |
| 最高可用画质 | 使用当前完整格式列表选择最佳可播放组合；展示实际选择高度 |
| 不超过 1080P | 在上限内选择最佳组合；缺失目标档位时按已设置的降级策略处理 |
| 具体编码/帧率 | 保存精确格式及对应音轨；过期重新解析后按相同意图匹配 |
| 精确格式 | 当前媒体仍有该格式才执行；不可用时明确提示 |
| 音视频合一导出 | 高清分离轨分别下载再合并，不能默认强制使用低清单文件 |

选择表达式示意：常规最佳可用可使用上游的 `bv*+ba/b` 语义；高度上限可在真实候选列表中先筛选，再生成明确的视频/音频选择。若使用表达式 `bv*[height<=1080]+ba/b[height<=1080]`，执行前仍需检查候选及目标高度，不在末尾追加无上限 `/best` 掩盖选择失败。

步骤 Y6：重构现有“安全通道”。管道错误时，保留 locator、Cookie、代理、导出类型、质量意图，只调整分片并发、暂存或传输实现。需要时将同一视频轨与音轨分两次下载，再显式 FFmpeg 合并。不能改回 `best/bestvideo+bestaudio` 或忽略所选导出类型。

步骤 Y7：如果日志明确提示缺少 PO Token，再启用 Provider 适配器。先在 Windows 验证本机 Provider，纳入依赖、健康检查、超时和打包设计；Android 的本机生成能力单独验证，未验证前将其显示为不可用能力，不默认连接外部服务。缓存必须遵循 Provider 返回的绑定与有效期，不能长期复用一个手抄 token。Provider 不是“反机器人验证必定成功”的保证。[PO Token 指南](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide)

步骤 Y8：下载完成后读取真实宽高、音轨和时长。选择 1080P 而成品 360P 时，根据允许降级设置返回明确的降级结果或失败，不显示普通高清完成。优先原编码合并；只有用户选择兼容编码导出且编码器可用时才转码，不能把 MP4 容器选择等同于 H.264 转码。

**8. X 的具体修复。** 将现有 yt-dlp 保留为主解析器，修复应用自己的媒体定位和错误处理。

步骤 X1：先建立公开单视频、GIF、多视频、图文混排、引用视频、受限内容和失效推文的 fixture 与实测样例。所有定位以 `tweetId + mediaId` 为主键，原始媒体位置作为辅助信息。

步骤 X2：替换 `MediaSelection.childUrl()` 中按解析后列表下标拼 `/video/N` 的逻辑。优先使用上游明确返回的子页面地址；缺失时保留父推文 URL 和 entry 的媒体 ID，在下一次解析得到的完整结果中按 ID 选择。只有已经证明保存的是原始媒体位置时才能生成 `/video/N`。

这是必要的：上游 `/video/N` 分支按原始媒体列表定位，可能包含图片；解析后的视频列表序号不一定相同。引用推文还应保留真实来源内容 ID，不能把引用媒体强行映射为父推文第 N 个视频。[上游索引逻辑](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py#L1348)

步骤 X3：为 yt-dlp 适配层提供“按媒体 ID 解析并下载一个 entry”的能力。Windows 在同一个 `YoutubeDL` 流程中选定 entry 后调用该版本支持的单条处理入口。Android CLI 封装优先使用经过当前格式/版本测试的 ID 过滤或播放列表选择；如果 CLI 无法稳定重选对应 entry，则改用该 entry 的 formats 和请求上下文生成直链/HLS 下载计划。不能以不可靠的序号替代媒体身份。

步骤 X4：多视频每段独立解析格式。Windows 前端批量请求只发送统一质量意图；服务端为各段生成不同格式 ID，删除所有分段复用首段 format ID 的路径。

步骤 X5：固定 iPhone UA 改为默认提取器策略；确有证据需要站点请求头时才在适配器中添加。补充 X Cookie 档案，与下载阶段共用。受限或无访问权限内容返回对应状态，不把所有失败都建议“换代理”。

步骤 X6：默认使用上游 GraphQL 路径及内建回退。匿名公开内容遇到适合的失败，可执行一次受控 syndication 回退；需要明确它可能少媒体或元数据。上游已有部分 429 回退，而且登录时 API 选择可能被忽略，必须避免重复回退和把“切换参数”当成有效动作。失败后保留最有意义的根因。[上游提取实现](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py#L1182)

步骤 X7：直接媒体 URL 遇到过期，重新解析相同 media ID 并续接；资源不再存在时返回 `CONTENT_UNAVAILABLE`。GIF 对应无音轨 MP4 等内容时按实际元数据展示，不强制追加音轨。

**9. 小红书的具体修复。** 使用站点感知的链接恢复与浏览器会话，之后再改进原生提取器。

步骤 R1：用用户本次错误构造脱敏 fixture。外层 URL 为 `/login`，`redirectPath` 包含 `/discovery/item/{id}` 及 token。测试中使用占位 token，不提交用户原 token。

步骤 R2：`LinkNormalizer` 先识别登录包装地址，解出合法内容地址，并保留原查询串；短链每一跳检查登录、验证码、404 等终点。建议内部提供：

```text
normalize(input) → originalUrl + platform + contentUrl + contentId
resolveRedirects(contentUrl, context)
  → ContentPage | LoginRequired | ChallengeRequired | Unavailable
```

步骤 R3：已有有效会话时请求内容页；无会话且跳登录时进入 `WAITING_FOR_AUTH`。用户在内置浏览器完成登录或验证码后，刷新对应会话版本，从原内容页继续。最多自动恢复一次，重复挑战交还用户，不循环弹窗。

步骤 R4：页面解析按笔记 ID 读取 `note.noteDetailMap[id].note`，确认内容类型，再读取 `video.media.stream` 下的实际候选。H.264、HEVC、不同分辨率分开保留；原始视频 key 仅作为已验证的回退，并检查响应是否为可播放媒体。不要全局匹配页面第一个 `masterUrl`，也不要仅通过文件后缀判断成功。

步骤 R5：原生解析失败但确定仍是内容页时，才允许用完全相同的 URL 和会话交给 yt-dlp。认证失败、验证码、内容不可用不进入盲目通用回退。

步骤 R6：主地址和备用流都保留完整签名参数；按媒体类型验证响应。返回 HTML/JSON 时根据最终地址及响应结构分类，禁止保存为 `.mp4`。

步骤 R7：删除解析/下载链路中重复的无 Cookie 页面探测。Android 的原生 HTTP 客户端、Windows requests Session 和内置浏览器分别适配统一会话档案；代理贯穿短链和内容请求。

**10. 小黑盒的具体实现。** 采用“先验证分享页、再实现专用提取器”的路径，不能预先承诺未验证的私有 API 字段。

第一步是一个有退出条件的技术验证任务：

- 收集至少 8 个不同作者的可访问视频帖，另加图文帖、失效帖和需验证帖。记录 URL 类型、内容 ID、是否需要会话、实际播放器请求、清晰度和时效。
- 验证 `xiaoheihe.cn/a/{id}`、带 `link_id` 的分享地址和实际存在的短链。若需要支持 `heybox://` 深链，在文本输入层单独识别并转换为验证过的 HTTPS 地址，不能依赖当前只提取 HTTP 的正则。
- 检查 HTML 中 `<video>`、`<source>`、结构化页面状态和浏览器的实际播放器请求，确认取流依据。所有字段路径写入 fixture；文档不预设一定存在 `video_url` 或固定公开接口。
- 验证 MP4、HLS、所需 Referer/UA、是否带签名有效期，以及是否存在多清晰度。
- 如果分享页在正常会话中仍不提供视频资源，则停止承诺自动分享解析，转为明确的浏览器辅助模式；不能通过要求 Root/宿主 Hook 充当普通下载能力。

验证通过后新增 `HeyboxExtractor` / `heybox_extractor.py`：

```text
resolve(link, context):
    识别并保存 link_id / 内容 ID
    请求已验证的分享页
    若进入登录/验证：返回 InteractionRequired
    读取该帖对应的媒体状态，枚举候选源
    为每个候选标注 MP4/HLS、清晰度、必要请求上下文、时效
    返回 ResolvedMedia，不直接在解析器中创建下载线程
```

接入现有下载器：MP4 使用受控 HTTP 下载，HLS 使用现有 yt-dlp/FFmpeg 能力；遇到 DRM 或未实现的保护方式明确返回不支持。普通非 DRM HLS 的协议处理由成熟下载器完成，不自行重新实现完整分片栈。鉴权信息按域发送；失效源只允许刷新一次并核对相同内容/媒体 ID。

浏览器辅助模式复用项目已有有界媒体捕获机制，仅捕获当前内容页相关、可验证的媒体请求。请求头和会话一起构建媒体计划，不只复制一个可能过期的 URL。Windows 网页纯后台首版以文件导入或专用解析为主；它不具备原生内置浏览器时，明确显示能力差异。

**11. Pixiv Ugoira 的具体实现。** Pixiv 动图由图片帧和帧时长组成；首版以单作品动图为范围，不将图片、漫画、Sketch、FANBOX 混入同一提取器承诺。[Pixiv 官方说明](https://www.pixiv.help/hc/en-us/articles/235584628-What-are-Ugoira)

建议新增 `PixivExtractor`、`UgoiraProcessor` 以及两端对应实现。

步骤 P1：识别 `/artworks/{id}`、`/en/artworks/{id}` 和需要支持的旧式作品链接。请求作品详情，判断类型。遇到非 Ugoira 返回明确的内容类型及支持范围，而不是当作“没有视频”反复回退。

步骤 P2：使用同一会话读取网页端作品信息及 `/ajax/illust/{id}/ugoira_meta`。解析结果中的帧包地址、帧名和 delay，以当前响应为准。参考 gallery-dl 的提取结构，但实现适合本项目范围的适配器；不得仅通过改 URL 中尺寸字符串宣称取得原始资源。[参考提取实现](https://github.com/mikf/gallery-dl/blob/master/gallery_dl/extractor/pixiv.py#L183)

内部资源结构建议：

```json
{
  "artworkId": "example-id",
  "kind": "UGOIRA",
  "archiveResourceRef": "protected-resource-reference",
  "frames": [
    {"file": "000000.jpg", "delayMs": 80},
    {"file": "000001.jpg", "delayMs": 120}
  ],
  "totalDurationMs": 200,
  "sourceQuality": "as_returned_by_platform"
}
```

步骤 P3：下载帧包到任务暂存区，校验 ZIP、帧名和解压后大小。拒绝路径穿越、绝对路径和资源异常膨胀；只按元数据顺序读取需要的帧，不依赖字典序或 ZIP 条目顺序。文件缺失、重复及不合理 delay 要有明确错误处理。原始资源导出可打包为 ZIP＋JSON，保留平台返回的帧序与时序。

步骤 P4：生成 FFmpeg concat 输入，每帧的 `duration` 来自 `delayMs / 1000`，时间基精度按毫秒需求验证。concat 示例：

```text
ffconcat version 1.0
file '000000.jpg'
duration 0.080
file '000001.jpg'
duration 0.120
file '000001.jpg'
```

末帧重复用于让 concat 识别末帧时长；编码还需限定目标总时长并实测输出，不能以此额外延长一帧。变帧率与像素格式参数按所打包 FFmpeg 版本确定，不把所有动画固定成 30 FPS。导出 MP4 时优先 H.264 和 `yuv420p`，奇数宽高通过补边保留完整画面；只有已验证编码器存在才显示此导出选项。

步骤 P5：GIF 使用调色板生成及应用流程，说明 GIF 的时间精度和颜色限制。透明源选择 MP4 时提示背景处理；GIF/原始资源保留适用透明度。精确原始时序以帧资源及 JSON 为基准，导出结果按各容器能力设定验收误差。

步骤 P6：UI 为 Ugoira 提供“MP4 / GIF / 原始帧资源”；不展示没有意义的音频提取。展示原始尺寸、帧数、时长、导出状态及预计资源消耗。转码任务串行执行或使用独立限流，避免同时大量编码耗尽手机存储和内存。

步骤 P7：进程中断后保留可验证的已下载帧包，重新校验后继续处理；已经写入目标目录的完整文件不覆盖。失败的编码文件不能进入完成态，清理仅限该任务的暂存目录。

**12. 错误、重试和任务状态。** 将诊断信息直接映射为用户能执行的下一步。

| 错误码 | 判断依据示例 | 用户提示/动作 | 自动重试规则 |
| --- | --- | --- | --- |
| `AUTH_REQUIRED` | 登录页、上游明确要求认证 | 导入会话或进入适用登录入口 | 原会话不重试，更新会话后恢复一次 |
| `CHALLENGE_REQUIRED` | 明确验证码/机器人验证响应 | 完成验证并检查访问网络 | 不连续请求撞验证 |
| `RATE_LIMITED` | 429、上游明确限流 | 等待后重试 | 优先遵守 Retry-After；长等待交回队列 |
| `NETWORK_ERROR` | 超时、连接重置、DNS 失败 | 检查网络及代理 | 全任务最多 2 次应用层重试，指数退避＋抖动 |
| `SOURCE_EXPIRED` | 签名/有效期及重新解析结果证明失效 | 自动刷新媒体源 | 同媒体 ID 刷新一次；403 不直接等同过期 |
| `QUALITY_UNAVAILABLE` | 已取得格式列表但不满足选择 | 展示实际可选格式 | 不静默降画质 |
| `NO_MEDIA` | 可访问内容确实不是支持的媒体 | 提示内容类型 | 不重试 |
| `MEDIA_ID_MISMATCH` | 返回对象 ID 与请求作品/媒体 ID 不一致 | 提示未取得目标内容 | 不得下载返回的其他作品 |
| `PREVIEW_ONLY` | 充电专属内容实际只返回试看 | 提示当前仅取得试看，检查目标权益 | 不静默保存为完整视频 |
| `ACCESS_UNCONFIRMED` | 会话存在但目标完整观看权限无法确认 | 提示重新验证或继续诊断 | 不据此宣称拥有完整权限 |
| `MEDIA_UNCONFIRMED` | X 返回为空、结构不完整或状态不明 | 提示本次未取到媒体，支持受控重试/浏览器验证 | 不缓存为永久无视频 |
| `EXPORT_FAILED` | 媒体已就绪但目标文件/URI 无法写入 | 重新选择目录并重试导出 | 保留有效暂存文件，不重下视频 |
| `CONTENT_UNAVAILABLE` | 删除、不可见、地域或访问权限限制 | 展示具体可确认原因 | 不盲目重试 |
| `EXTRACTOR_CHANGED` | 页面可访问但既有结构失配 | 导出脱敏诊断 | 可一次已验证替代提取路径 |
| `ENGINE_UNAVAILABLE` | Python/JS/EJS/FFmpeg 不兼容或缺失 | 显示组件及修复入口 | 不重试同一损坏运行时 |
| `POSTPROCESS_FAILED` | 下载完成但合并/编码失败 | 保留有效源文件并允许再处理 | 仅重试后处理 |

初始建议预算：页面/解析阶段总预算 90 秒；网络操作单次超时按阶段设置；大文件下载不套用 90 秒总限制。应用层与 yt-dlp 内部重试共用预算，避免外层 3 次乘内层 10 次形成请求放大。用户取消立即终止重试与等待。

任务状态在已有枚举上补充 `RESOLVING`、`WAITING_FOR_AUTH`、`WAITING_FOR_RETRY`、`VERIFYING`；下载、处理、完成、失败、取消语义延续。最终完成只由成品验证及落盘成功触发。后处理失败保留源文件路径引用，允许只重跑后处理。

日志最少记录：taskId、attemptId、平台、提取器/引擎版本、阶段、HTTP 状态、错误码、候选格式摘要、选中格式、成品摘要及耗时。禁止记录 Cookie、Authorization、代理密码、完整签名地址、用户 token。诊断日志设置容量及期限上限，用户导出前可预览脱敏内容。

**13. 代码落点。** 以下仓库相对路径用于后续实施定位；标注“新增”的文件尚不存在，不是本轮修改记录。

| 端 | 现有文件/建议新增文件 | 具体改动 |
| --- | --- | --- |
| Android | `engine/UrlSniffer.kt` | host 精确识别、参数保留、内容身份恢复；保持旧入口兼容 |
| Android | 新增 `engine/LinkNormalizer.kt` | 纯 URL 规则与可测试结果；重定向 I/O 通过接口注入 |
| Android | `engine/DownloadEngine.kt` | 统一构造解析/下载请求，移除低清重试，连接下载计划及验证 |
| Android | 新增 `engine/YtDlpOptionsFactory.kt` | 集中 Cookie、代理、格式和诊断参数，返回可脱敏参数摘要 |
| Android | `engine/MediaSelection.kt` | 从序号猜测改为媒体身份与经过验证的原始位置 |
| Android | `engine/DomesticDirectExtractor.kt` | 小红书结构化取流、认证分类、请求上下文注入 |
| Android | `engine/DomesticMediaSource.kt` | 逐步迁移为按 source kind 分发，移除将平台名单当媒体模型的限制 |
| Android | `data/model/Models.kt`、`data/repository/TaskRepository.kt` | 新字段、schemaVersion、旧任务迁移、暂存和结果引用 |
| Android | 新增 `data/repository/SessionRepository.kt` | 会话加密存储、导入/删除、临时 jar、会话代次 |
| Android | `data/repository/SettingsRepository.kt`、设置页面 | 会话状态、网络档案、引擎状态，不向 UI 回传凭据值 |
| Android | `ui/viewmodel/MainViewModel.kt`、`ui/components/FormatSelectorSheet.kt` | 保存结构化选流；批量质量意图；Ugoira 选项 |
| Android | `service/DownloadService.kt` | 可恢复等待、取消传播、后处理重试、状态不倒退 |
| Android | 新增 `engine/HeyboxExtractor.kt`、`engine/PixivExtractor.kt` | 新平台元数据与媒体源解析 |
| Android | `engine/FFmpegExecutor.kt`、新增 `engine/UgoiraProcessor.kt`、`engine/MediaVerifier.kt` | 动画编码、成品验证及任务级暂存；验证 ffprobe 可用性 |
| Windows | `engine/url_sniffer.py` | 对应链接规则及认证重定向 |
| Windows | `engine/ytdlp_engine.py` | 受控日志、显式错误、统一选项、entry 定位与输出路径 |
| Windows | 新增 `engine/request_context.py`、`core/session_store.py` | 会话/网络上下文、系统保护、临时 jar |
| Windows | `engine/domestic_extractor.py` | 同会话原生请求；小红书结构化提取 |
| Windows | `core/models.py`、`core/task_manager.py`、`core/config.py` | 模型与迁移、每段独立格式、凭据引用、可恢复队列 |
| Windows | `omni_server.py`、`web/index.html` | 结构化质量/会话 API、错误动作、逐媒体选择、格式显示 |
| Windows | 新增 `engine/heybox_extractor.py`、`engine/pixiv_extractor.py`、`engine/ugoira_processor.py`、`engine/media_verifier.py` | 对应新增平台与校验 |
| Windows | `engine/ffmpeg_helper.py`、`requirements.txt`、构建脚本 | ffprobe/编码探测、锁定依赖、EJS/runtime 资源打包 |
| iOS | `Engines/UrlSniffer.swift`、`Engines/DomesticExtractor.swift`、`Services/CookieStore.swift`、模型和任务服务 | 按里程碑移植链接/会话/结果约定；新平台另测 |

Android 文件位于 `app/src/main/java/com/omni/downloader/`；Windows 文件位于 `windows/`；iOS 文件位于 `OmniDownloader-iOS/OmniDownloader/`。

Windows API 建议保留旧字段兼容窗口，新增 `schemaVersion`、`locator`、`qualitySelection`、`credentialProfileId`。`selectedResolution` 作为旧客户端兼容字段，不再作为新逻辑的唯一选流依据。服务端验证前端提交的媒体 ID 与当前解析结果对应，不能让前端任意指定本地路径或 yt-dlp 选项。

**14. 数据迁移及旧任务兼容。** 新字段落地必须同时实现读取、迁移和回退说明。

- 任务和配置分别增加 schemaVersion，读取旧格式后迁移到新的内存模型；采用临时文件＋原子替换保存，保留可恢复的旧版本快照。
- 从旧 `selectedResolution` 推导质量意图：`1080p` 等转换为高度上限，“最佳/自动”转换为最高可用；无法识别时显示需重新选择，不能猜成格式 ID。
- 旧 X 多视频任务若没有可靠 media ID，重新解析后需要明确重选，避免错误下载另一段。已有完成文件保留，不因迁移触发重新下载。
- 旧 `directMediaUrl` 转换为带时效的媒体资源引用，下载前校验；旧 Cookie/Authorization 请求头迁入凭据存储，成功写入受保护存储后再去除旧值。
- 新版首次读取旧队列时，不默认执行未确认的过期任务；原始待下载意图和已完成记录保留。
- 二进制回退使用旧数据快照或支持新 schema 的兼容版本，不直接把新数据交给不识别它的旧程序覆盖保存。
- 依赖更新保持已验证运行时的可回退副本，校验来源及完整性；不在任意任务执行中途替换运行时。

**15. 验证计划。** 测试目标是发现实际行为回归，不写仅复制实现的断言。网络样例和离线 fixture 分开，Cookie 等凭据只在本地或受控测试环境使用。

| 测试组 | 必须覆盖 | 通过条件 |
| --- | --- | --- |
| URL | 小红书登录包装、多层百分号编码、含 `+` token、短链相对跳转、伪造域名、Pixiv 语言路径、小黑盒 link_id | 内容 ID 正确、关键参数不变、跳转有界、无跨站凭据发送 |
| YouTube | 常规高清、Shorts、分离轨、仅低清、需要会话、机器人验证、失效格式、管道错误 | 正确区分失败；不静默降级；音视频及导出类型正确 |
| X | 单视频、多视频、GIF、图片＋视频、引用视频、原始 /video/N、受限/删除、429 | 每次下载均对应所选 media ID；错误动作准确 |
| 小红书 | 完整分享、短链、登录、验证码、无媒体、结构变化、不同编码 | 原 token 保留；验证后接续；不下载页面其他视频 |
| 小黑盒 | 不同作者/帖子地址、MP4、HLS、签名过期、无视频、需验证 | 选定内容正确；刷新不串媒体；受限能力明确 |
| Pixiv | 等时/不等时帧、单帧、奇数尺寸、透明帧、缺帧、损坏 ZIP、异常路径、会话失效 | 帧序正确，动画总时长在约定容差内，无不受控解压 |
| 队列 | 取消、网络断开、后台恢复、进程终止、两任务同时后处理 | 状态不倒退；暂存互不影响；能从有效阶段恢复 |
| 成品 | HTML 假媒体、缺音轨、损坏容器、合并失败、错误分辨率 | 验证失败不报完成；有效源可保留用于重处理 |
| 迁移 | 旧设置、旧待下载任务、旧 X 任务、旧直链、回退 | 不丢记录；身份不明确任务不盲下；快照可恢复 |
| 凭据 | 导入损坏文件、过期、删除、切换、异常退出、诊断导出 | 无明文泄漏；临时文件可回收；缓存随会话失效 |
| 原有功能 | B站单视频/分P、抖音、快手、TikTok、封面/音频/GIF | 共用模型变动没有造成关键功能阻断 |
| B站充电 | 同账号有/无权益、到期、错误账号、试看、分P、源流有效但导出失败 | 完整/试看结果准确，导出失败可恢复且不重新下载 |
| 抖音图文 | 未命中 ID 的推荐列表、图文对象带推荐视频、SSR 中目标后置、旧缓存、动态照片 | 不下载其他作品；正确识别类型；误命中缓存清除 |
| X 间歇失败 | 同帖成功/失败响应、200空数据、429回退、引用媒体、会话变更 | 不把未确认当无视频；重试有界；直接下载仍保持媒体身份 |
| Android 导出 | 公共目录不存在、不可写、同名普通文件、SAF权限撤销、磁盘满、并发导出、进程中断 | 错误阶段准确；源文件保留；完成记录指向可读取URI/路径 |

Ugoira MP4 时长初始验收容差建议为 `max(20ms, 总时长的0.5%)`，须根据实际编码器和时间基验证；GIF 根据格式时间粒度单独设定容差，不宣称无损时序。高清成品通过 ffprobe 或等价媒体探测确认；若 Android 包内没有 ffprobe，评估同源打包或使用能提供所需信息的 Android 媒体 API，未具备验证能力时不能伪报通过。

Android 运行现有 JVM 测试、受影响的仪器测试及 release-smoke，并补充媒体选择、迁移、错误分类及动画样例测试。至少在声明支持的 Android 8.0 ARM64 设备与较新 Android ARM64 真机验证关键流程；模拟器用于辅助，不代替真机媒体执行。保持现有最低系统版本和发布 ABI，运行时升级若有冲突须单独评估。

Windows 运行核心解析/队列/迁移测试，并在 Windows 10、Windows 11 的干净用户环境验收桌面独立版和网页纯后台版。测试机器不能依赖开发目录里的 Python、Deno、FFmpeg 或隐式环境变量。打包后重新执行版本探测和下载样例，而非只检查构建退出码。

网络验收使用测试时已确认可访问、具有目标格式的样例；将网站当日不可用、会话缺失与应用缺陷分别报告。没有用户环境和有效样例时，只报告离线验证及未验证项，不给出“所有平台成功率100%”之类结论。

**16. 实施任务及依赖。** 每项都有具体产物和完成标准，可直接作为后续开发清单。

| ID | 实施任务 | 前置 | 交付及完成标准 | 所属阶段 |
| --- | --- | --- | --- | --- |
| T01 | 记录真实引擎/运行时与当前样例 | 无 | Android/Windows 成品探测记录，故障阶段可重现或明确缺口 | M0 |
| T02 | 脱敏日志、错误码、预算 | T01 | 失败保留根因，凭据泄漏测试通过 | M0 |
| T03 | 平台 URL 规则及登录重定向恢复 | T02 | 小红书参数/登录 fixture 通过 | M1 |
| T04 | 会话档案、导入、系统保护、请求上下文 | T02 | 两端原生/yt-dlp 同会话；清理和删除通过 | M1 |
| T05 | 新质量/媒体模型及旧任务迁移 | T02 | 精确格式和质量意图可恢复；旧数据测试通过 | M1 |
| T06 | 锁定兼容运行时与 EJS/JS 打包 | T01 | 两端成品能执行匹配挑战组件；可回退 | M1 |
| T07 | YouTube 默认客户端、选流及重试 | T04-T06 | 高清、仅低清及反机器人分别得到正确结果 | M1 |
| T08 | 小红书精确取流与人工验证接续 | T03-T05 | 同笔记/会话恢复，假媒体被拒绝 | M1 |
| T09 | X 媒体 ID 定位、每段独立格式及回退 | T04-T06 | 混排与引用样例不串媒体；错误分类正确 | M1 |
| T10 | 成品验证、任务级暂存及后处理恢复 | T05 | 错误宽高/缺轨/失败合并不报完成 | M1 |
| T11 | 设置与下载 UI/API 联调 | T03-T10 | 用户可完成导入、验证、重试和画质选择 | M1 |
| T12 | 回归与第一批发布候选 | T07-T11 | Android/Windows 安装产物实测记录齐全 | M1 |
| T13 | 小黑盒分享页技术验证 | T03-T04 | 取流字段、请求条件及稳定性有样例证据 | M2 |
| T14 | 小黑盒适配器与 MP4/HLS 接入 | T10、T13 | 单个公开视频帖端到端通过 | M2 |
| T15 | Pixiv 元数据、帧包及会话解析 | T04-T05 | 正确识别 Ugoira，资源与时序对应 | M3 |
| T16 | 帧序列后处理和导出 UI | T10、T15 | 非等时帧、末帧、尺寸和透明度验收 | M3 |
| T17 | 全量发布候选、迁移/回退演练及说明 | 对应阶段任务 | 能力矩阵、产物清单、测试记录及已知限制一致 | 每阶段 |
| T18 | B站账号身份、目标权益、试看及完整流校验 | T04-T06 | 有权限完整下载；无权限不误报；不额外付费操作 | M1 |
| T19 | Android StorageExporter 与导出恢复 | T05、T10 | ENOENT/权限/空间等可诊断；只重试导出 | M1 |
| T20 | 抖音严格 ID/类型解析与错误缓存失效 | T03、T05 | 删除首项回退；不串作品；图文正确展示 | M1 |
| T21 | X 解析快照、受控替代路径、单次解析后直接下载 | T04、T09-T10 | 暂态无媒体可恢复；有效媒体不因重复解析丢失 | M1 |
| T22 | 新增场景的两端回归与 Android 导出实测 | T18-T21 | 截图对应错误分类和恢复流程有实测记录 | M1 |

PO Token Android 本机能力和 iOS YouTube/X 等价支持作为独立验证任务；只有依赖、系统约束和样例已证明可执行时才进入发布承诺。不能以“后续接入一个服务”作为已完成实现。

**17. 工作量与风险。** 以下是工程估算，不是完成日期承诺；以一名熟悉项目的开发者、具备真机和可访问样例为假设。

| 工作包 | 初步估算 |
| --- | --- |
| M0 诊断、样例与版本清单 | 1–2 人日 |
| M1 两端模型/会话/迁移、五平台受影响场景及导出修复 | 12–21 人日 |
| M1 打包、真机及干净 Windows 回归 | 4–6 人日 |
| M2 小黑盒验证、实现及回归 | 3–6 人日，取决于分享页可访问性 |
| M3 Pixiv Ugoira 两端实现及回归 | 5–8 人日 |
| 合计 | 25–43 人日，另预留约20%平台变化和依赖适配余量；已计入新增B站、抖音及导出需求 |

主要不确定性是 Android Python/JS 运行时兼容、小黑盒分享页访问能力、平台实时风控和 Ugoira 编码器能力。任何一项验证失败，应缩小对应平台的支持声明或调整该项技术路线，不阻止已通过验收的其他修复独立发布。

**18. 交付标准。** 每个正式发布候选应包含源码变更、精确依赖清单、数据迁移及回退说明、安装产物校验值、平台能力矩阵、测试样例类型及结果、未验证项和已知限制。用户可从设置看到实际引擎和会话状态，从错误页执行适用的下一步。

本规划的优先顺序是：先阻止下载错误作品并修复最终导出，再统一链接、会话和选流，完成 YouTube/X/小红书及B站充电场景回归，随后验证并增加小黑盒，最后实现 Pixiv 帧动画流程。新平台的“支持”以真实内容与成品验证为依据。

**19. B站充电专属支持及截图中的 ENOENT。** 两者分属权限取流与存储导出，不作为同一个错误处理。

截图一显示公共目录 `Download/OmniDownloader/` 下的 `bili_BV…_时间戳.mp4` 在打开时返回 `ENOENT (No such file or directory)`。在审阅基线中，B站模块第一次按标题复制文件失败后，会改用完全对应的 `bili_{bvid}_{timestamp}.mp4` 名称，再向同一公共目录复制。由此可以较强推断程序已走到备用导出分支，但截图没有完整异常堆栈，不能断言源文件已完整有效，也不能把底层原因唯一归结为未创建文件夹。

已确认的健壮性问题：目录创建结果未检查；文件名回退没有改变目录/存储访问方式；首次异常被后续异常掩盖；失败缺少“重新导出已有成品”入口。需要记录源文件存在性/大小/可读性、目标父级状态、系统版本、存储目标类型和两次异常链，才能区分目标目录缺失、路径中某一项是普通文件、存储访问限制或其他情况。

存储修复具体设计：新增 `StorageExporter`，将下载与合并产物先写入应用私有任务目录并验证。Android 10+ 默认下载目录使用 `MediaStore.Downloads` 的 `DISPLAY_NAME`、`MIME_TYPE`、`RELATIVE_PATH`，写入期间 `IS_PENDING=1`，验证写入成功后设为0；返回并持久化 `content://` URI。自选目录使用 SAF 授权树与持久化访问权限，不能把 URI 转换为猜测的绝对文件路径。Android 8/9 使用适用的旧式外部存储权限和文件接口，并检查 mkdirs/文件写入结果。[Android 官方存储说明](https://developer.android.com/training/data-storage/shared/media)

导出异常返回 `EXPORT_FAILED`，保留已验证源文件及其校验信息，清理本次创建的残缺目标；允许重新选目录或只重试导出。播放、分享和删除逻辑适配 URI 与旧路径两种记录。不以请求所有文件访问权限作为默认修复方案，也不在导出失败时重新请求平台视频。

B站充电专属建议增加 `BiliAccessInfo`：`exclusiveType`、`accountId`、`accessState(FULL/PREVIEW/DENIED/UNKNOWN)`、`expectedDurationMs`、`returnedDurationMs`、`evidence`。具体页面/响应字段通过当前样例确认，不能凭 Cookie 存在就设置 FULL。

实现步骤：

1. 用配置会话验证账号身份；提供当前账号标识及最近验证结果，防止登录错账号。完整Cookie优先保留其有效字段，不武断要求所有只读请求必须具有某三个固定字段。
2. 取得目标 BV、所选分P的 cid、充电专属标识与完整内容时长；权益以该账号对这条内容的实际访问结果为准。
3. 带相同会话请求完整播放信息，评估迁移至当前上游使用的 WBI 签名播放接口和必要参数。复用经过验证的签名实现或 yt-dlp 适配，不预设旧 playurl 参数永远足够。
4. 结合响应中的试看提示、实际支持字段、返回流时长及原内容时长判定。上游已对 `is_upower_exclusive` 和“试看”格式描述做处理，且已登录请求会移除 try_look；本项目应将这些信号结构化，不能只发一个用户看不到的 warning。[上游实现](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/bilibili.py)
5. 只取得试看时返回 PREVIEW_ONLY，默认不创建“完整视频”下载任务；如果将来提供试看保存，应清楚标为试看。未知状态保留 UNKNOWN，不能只因短视频时长相同就推断有权益。
6. 按实际可用格式下载音视频并合并，校验当前分P时长和适用音轨；确实没有音轨的内容单独标注，不能把预期有音频但获取失败的任务默认为成功。
7. 导出经 StorageExporter 完成后进入完成态。有权限但接口限制、需要未实现保护方式或无法取得完整流时，明确报告限制，不承诺登录即100%可下载。

**20. 抖音图文误指向的根因与修复。** 这是源码能确认的媒体身份缺陷，不应仅解释成平台随机返回。

Android `requestDouyinFeedApi()` 与 Windows `_extract_douyin()` 都会在 aweme_id 没有命中目标时选 `aweme_list[0]`；返回第一项可能是推荐作品。Android 随后把该流缓存到请求页面和请求 ID 对应地址，导致错误匹配还可能持续影响后续任务。Android 的 URL 清洗还会把 `/note/{id}` 改为 `/video/{id}`；HTML 回退对整个页面抓首个 play_addr 或 CDN URL，没有证明它属于目标作品。图文没有普通视频流时，这些回退尤其容易命中推荐视频。

必须修改的决策：

```text
requestedId = 从受支持链接结构提取目标ID
candidate = 返回对象中 aweme_id 严格等于 requestedId 的对象
candidate 不存在 → MEDIA_ID_MISMATCH / 当前源未返回目标，禁止取首项
candidate 存在 → 读取其真实内容类型和资源
video → 仅下载该对象的可验证视频流
image_post → 仅展示该对象的图片/动图资源；视频导出不适用
其他/未知 → 明确受限类型，禁止全页抓任意视频兜底
```

链接保留 note/video 类型线索，页面实际对象作为最终内容类型依据；统一用 `contentId` 表示身份，不用“所有输入都是 videoId”的假设。SSR/路由状态先结构化解析，再定位目标对象，不能从整段页面正则匹配第一个播放地址。识别出图文后不再落到 yt-dlp 通用视频回退；只有仍在解析同一作品且验证其身份的替代提取器才可继续。

缓存键增加平台、contentId、mediaId、会话代次；值保存已验证的源对象ID和类型。版本升级后失效旧抖音直链缓存；对于已持久化、没有身份依据的旧直链任务，要求重新解析，不能继续下载可能串帖的地址。

首批修复的最低交付是“正确识别图文并阻止下载其他作品”；可随同提供图片原资源/ZIP保存。图片背景音乐可作为单独资源，但不得伪装成图片视频轨。将图文合成为MP4属于新的导出功能，默认不纳入纠错任务，若后续实现要明确时长和音乐规则。

**21. X 的间歇性无视频与更稳的方式。** 截图二的错误为 `ERROR: [twitter] 2103710160502104271: No video could be found in this tweet`，阶段是提取器没有得到可识别的媒体条目，尚未进入MP4/HLS下载，因而不能由此认定下载线程、FFmpeg或公共目录有问题。之前发现的 `/video/N` 推算风险需要修复，但不能直接当作这条父推文“无视频”错误的已证实原因。

“同一视频不做修改，换时段恢复”与会话/出口/限流或站点返回变化相符，不足以证明纯随机或软件数据竞争。上游说明过某些受限推文会返回含义不明确的 TweetTombstone，可能和删除等状态混淆；同样的无视频文案也可由不完整的公开嵌入响应触发，不能直接判定推文真的没视频。[上游说明](https://github.com/yt-dlp/yt-dlp/issues/14742)

本项目的确定缺口是：主要单一路径提取、缺少贯穿任务的X会话、抑制warning导致回退原因不可见，以及未充分区分“确定无视频”和“这次未取得媒体”。上游会对部分429转到syndication；该接口明确可能返回不全元数据/媒体，因此“被限流→回退响应缺媒体→报无视频”是需要通过日志检验的一条因果链，当前截图不能证明实际走过该链。

更稳的目标架构：

```text
提取层：同一会话/网络 → 当前 yt-dlp → 一次有条件的替代路径
                                      → 必要时用户当前内容页的浏览器验证/取流
          ↓ 校验 tweetId、来源推文ID、mediaId，生成媒体源快照
传输层：有效合一MP4 → 原生HTTP下载
        HLS/分离轨 → 成熟分片下载器及必要合并
          ↓ 校验成品 → StorageExporter
源过期：只重解同一mediaId一次；不得切换另一视频
```

具体实施：

- 匿名和登录模式是明确的会话档案；auth_token/ct0等随同一合法会话导入并按域适配，不拼凑不同来源Cookie。只展示有效性状态，不暴露值。
- 在成功与失败时记录相同的引擎版本、API路线、同一出口配置标识、会话代次、HTTP状态、顶层响应类型、媒体计数及回退原因。不要把“HTTP200”直接视为有效媒体响应。
- 明确403/429按其语义处理；没有明确永久不可用证据的“无媒体”先返回 MEDIA_UNCONFIRMED。网络错误最多两次有退避重试；这类未确认响应最多一次条件重试/替代路线，共享总预算，不叠加无界重试。
- 上游原生GraphQL仍为主路径；仅对公开内容验证syndication作为补充。记录是否真正切换，避免重复执行上游已有回退；登录状态对API参数的处理按锁定版本确认。不固定把syndication当作更稳定的唯一接口。
- 对解析成功的当前条目，保留所选格式URL及其必要headers/会话和时效，下载时优先消费这个结果，减少“预览解析成功→排队下载时再次完整提取失败”的窗口。URL过期才按同一媒体身份刷新。批量子任务各自保存媒体身份。
- 直接MP4下载减少重复GraphQL请求与不必要合并，改进的是传输链路；如果尚未取到流地址，单纯更换HTTP下载器不会修复截图中的错误。
- 浏览器辅助只针对用户当前打开且能观看的目标推文。优先读取响应中对应tweet/media的数据，再关联播放器网络请求，不能抓页面任意第一个video或推荐流；blob/MSE地址本身不是可下载的完整源，需要实际manifest或媒体请求。
- 同一账号/网络浏览器也无法播放时，停止盲目重试并说明状态；不默认采用第三方镜像站，避免引入额外不稳定源与会话外传。
- 错误页按实际原因展示“重新验证”“稍后重试”“在浏览器查看”或“设置网络”；本截图中的统一代理提示不能作为已诊断结论。

结论是“外部状态可能是触发因素，当前流程缺少足够健壮的恢复和判断”，并非已证明存在安全漏洞，也不能保证某种技术路线在所有时段成功。稳定性用同一组目标在成功/失败时段的对照实测判断，报告首次成功、有限恢复成功、真正不可用及未确认的数量，不虚构成功率。

**22. 本次补充的优先顺序和验收。** 首先修复抖音身份错配，防止成功下载错误作品；B站存储导出修复同样优先于新平台扩展。随后加入B站充电权限/试看判定和X分阶段恢复；这些任务已列入M1。

新增验收：抖音返回列表只有推荐内容时必须拒绝；目标图文对象后面存在视频推荐时只识别目标；错误旧缓存不再命中。B站同一链接分别用有权益、无权益和过期会话验证，确认完整/试看/拒绝分类；文件已就绪但公共目录失败时，换目录后仅导出并保留相同源校验值。X使用同一推文在不同时间的成功/失败日志做对照，确认是否涉及429回退、会话变化或响应类型变化；无实际视频流时不能用“下载重试通过”伪装解析修复。

后续实机复现最有价值的材料是：完整成功/失败两份脱敏日志、真实App及引擎版本、Android版本/目标目录类型、抖音原分享链接。用户无需发送Cookie或账号密码。本轮没有访问用户登录态，也没有对截图中的付费内容执行下载；不能宣称上述修复已经完成。

参考资料：

- [项目审阅基线](https://github.com/baishu136/OmniDownloader/tree/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6)
- [Android 下载引擎](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/app/src/main/java/com/omni/downloader/engine/DownloadEngine.kt)
- [Android 链接处理](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/app/src/main/java/com/omni/downloader/engine/UrlSniffer.kt)
- [Android 子媒体选择](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/app/src/main/java/com/omni/downloader/engine/MediaSelection.kt)
- [Android 小红书原生解析所在模块](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/app/src/main/java/com/omni/downloader/engine/DomesticDirectExtractor.kt)
- [Windows yt-dlp 引擎](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/windows/engine/ytdlp_engine.py)
- [Windows 前端](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/windows/web/index.html)
- [yt-dlp Cookie 导入说明](https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp)
- [YouTube 官方已知问题清单](https://github.com/yt-dlp/yt-dlp/issues/3766)
- [小黑盒播放地址捕获参考；不等同分享页解析器](https://github.com/Mrmiaomrzh/BetterHeybox/blob/main/app/src/main/java/com/better/heybox/hooks/VideoDownloadHook.java)
