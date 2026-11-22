OmniDownloader 专项更新规划：B站充电、抖音图文、X稳定性与4K以上画质

制定日期：2026-11-02（Asia/Shanghai）  
设计基线：`main@5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6`，Android v1.5.10。  
范围：根据上一轮故障分析形成的独立更新方案，新增4K及更高分辨率提取、排序和降级策略。本轮仅新增本文档，未修改应用代码、依赖或账号会话，未运行实际下载验收。原综合规划保留。所有新增组件、接口、字段及版本阶段均为待实施设计。

**1. 交付目标。** 本轮专项更新解决“下载正确作品、取得完整内容、优先真实高分辨率、可靠保存成品”四件事。

| 功能 | 交付行为 | 不足条件下的结果 |
| --- | --- | --- |
| B站充电专属 | 使用有目标内容权限的账号获取完整流，区分试看并验证当前分P时长 | 明确需要会话、无权益、仅试看或权限未确认 |
| Android B站保存失败 | 暂存完成后经统一导出器保存，失败后可只重试导出 | 保留有效成品，提示重新选择目录 |
| 抖音图文纠错 | 严格匹配作品ID与类型，图文不会下载推荐视频 | 无法确认身份时停止；图文显示适用选项 |
| X间歇性无视频 | 同一媒体身份下复用会话、有效取流结果和有界恢复 | 区分暂态未取得媒体、认证、限流和确认不可用 |
| 4K及更高画质 | 枚举真实候选，最高画质模式优先更高源分辨率，支持分离轨合并 | 权限、源格式或封装限制有明确说明，不伪造高清 |

Android与Windows为本专项主实施端。iOS复用协议、选择算法和测试样例，但按已有原生能力另行验收，不将Android/Windows测试结果视为iOS通过。保留Android现有最低系统版本和发布ABI；Windows桌面版及网页后台版均须验证。小黑盒、Pixiv和小红书的完整扩展继续由原综合规划管理，本专项的共用选择器可供它们后续接入。

**2. 源码事实与诊断边界。** 下表将确定存在的行为和截图只能支持的推断分开。

| 编号 | 证据 | 判断 | 优先级 |
| --- | --- | --- | --- |
| D01 | 抖音目标ID未命中，两端取 `aweme_list[0]` | 确定的作品身份缺陷，可能命中推荐内容 | P0 |
| D02 | Android将note规整为video；HTML取全页首个播放地址 | 类型和资源归属可能丢失，图文场景风险明显 | P0 |
| S01 | B站截图备用文件名对应复制失败后的改名重试分支 | 很可能位于最终导出阶段，不能据此确认完整源已有效 | P0 |
| S02 | 导出失败只改名并重用同一父目录，未保留充分异常链 | 无法恢复目录/存储访问故障，缺少仅导出重试 | P0 |
| B01 | B站已有Cookie注入，但没有充电完整内容/试看专门校验 | 有鉴权基础，不等于现版本完整支持所有充电视频 | P1 |
| X01 | 截图为 `No video could be found in this tweet` | 提取阶段未获得可识别媒体，不能归因FFmpeg或保存目录 | P1 |
| X02 | 当前X请求配置缺少贯穿任务的会话，warning被抑制 | 不利于分辨响应不全、认证、限流与回退 | P1 |
| Q01 | Windows将height≥2160统一标签为4K，并以标签去重 | 8K/其他高分辨率可能被合并、覆盖或误标 | P1 |
| Q02 | Android高分辨率文案存在height≥2160即4K的映射 | 真实更高分辨率未获得准确独立展示；文案还参与任务选流 | P1 |
| Q03 | Windows B站目标qn未命中时取第一条视频流 | 结果依赖接口顺序，不能保证最高或最合适画质 | P1 |
| Q04 | B站Android指定高度按绝对差最小选流 | 不是严格上限规则，可能超出用户选择的上限 | P1 |
| Q05 | B站请求常用qn=120，界面/任务主要依赖清晰度字符串 | 不能证明已枚举8K等高阶流；重试可能丢失实际格式 | P1 |

X“换时段自行恢复”与会话、限流、出口或平台响应变化相符，目前没有成功/失败成对日志，不能确定唯一原因，也没有证据证明存在安全漏洞。B站ENOENT也不等同于充电权限不足。正式缺陷记录必须保留这些边界。

**3. 共用数据契约。** 在现有模型中增量增加结构化字段，不再由UI文案决定媒体与质量。

| 模型 | 必要字段 | 约束 |
| --- | --- | --- |
| `MediaIdentity` | platform、contentId、mediaId、partId/cid、sourceContentId | 每次取流、刷新和下载均核对，引用媒体保留真实来源 |
| `ContentKind` | VIDEO、IMAGE_POST、ANIMATED_PHOTO、UNKNOWN | 内容类型先确认，再提供下载选项 |
| `AccessInfo` | FULL、PREVIEW、DENIED、UNKNOWN；判定证据；expectedDuration | Cookie存在不能直接推出FULL |
| `FormatCandidate` | formatId、qualityId、原始宽高、显示宽高、rotation、fps、vcodec、acodec、HDR、bitDepth、protocol、bitrate、urlRef、expiresAt | 质量ID与格式ID分开；敏感地址受保护保存 |
| `QualityIntent` | mode、targetTier、maxLongEdge、maxShortEdge、exactWidth/Height、fpsPolicy、HDRPolicy、codecPolicy、containerPolicy、allowResolutionDowngrade | 最高、上限、指定档位、精确格式分别解释 |
| `ResolvedMediaSnapshot` | identity、全部候选、所选视频/音频、sessionGeneration、networkProfileId、解析时间 | 复用已成功解析的同一媒体，过期才刷新 |
| `ExportTarget` | MEDIASTORE_DOWNLOADS、SAF_TREE、LEGACY_FILE、WINDOWS_DIRECTORY | URI与路径独立建模，不互相猜测转换 |
| `OutputInfo` | 实际宽高、FPS、时长、音轨、编码、容器、最终URI/路径 | 校验通过和导出成功才进入完成态 |

格式去重键至少保留媒体ID、格式ID、尺寸、帧率、编码、动态范围、音轨组合及协议。可以把同一格式的不同CDN地址折叠为备用源，不能把不同分辨率/编码折叠为一个“4K”对象。比特率和尺寸未知时保留null，不能当作0码率劣质流。

**4. B站充电专属实现。** 只处理账号已经拥有的目标内容访问权限，完整流取得失败时准确分类。

1. 验证配置会话当前对应的账号，记录账号标识和最近验证状态；不在日志或普通任务记录中保存Cookie值。
2. 确定目标BV、所选分P的cid、完整内容时长及充电专属标识。分P校验使用分P时长，不能拿整部视频总时长比较。
3. 同一会话读取播放信息，结合网页播放数据与当前有效接口。评估使用上游已采用的WBI播放接口及签名适配，实际参数以锁定版本和样例确认。
4. 解析试看信号、有效格式及返回时长，生成FULL/PREVIEW/DENIED/UNKNOWN。上游已有 `is_upower_exclusive` 和“试看”描述检测；本项目将它们结构化，避免只发隐藏warning。[上游实现](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/bilibili.py)
5. PREVIEW默认不创建标为完整视频的任务；UNKNOWN不推断为FULL。时长比对是补充证据，不能作为唯一权益判断。
6. 完整流进入共用高分辨率选择器，下载所选音视频并合并。应有音轨但音频获取失败时不得转为静音成功；确实无音轨的内容单独标注。
7. 媒体校验通过后调用统一StorageExporter。权限过期、无完整流或未实现的保护形式均明确报告，不承诺任意充电内容都可下载。

新增错误：`AUTH_REQUIRED`、`PREVIEW_ONLY`、`ACCESS_DENIED`、`ACCESS_UNCONFIRMED`。用户可重新验证会话；不执行购买或改变充电档位等付费操作。

**5. B站ENOENT及Android存储导出实现。** 截图中的 `bili_BV…_时间戳.mp4` 对应原始标题复制失败后的备用文件名。改名没有改变目标目录，无法解决父目录不可用等问题。补充记录两次异常、源文件状态和目标类型，避免首次错误被覆盖。

新增 `StorageExporter.export(source, target)`：

```text
应用私有目录 staging/{taskId}/{attemptId}
  → 下载/合并完成
  → MediaVerifier确认成品
  → StorageExporter创建受控目标
  → 写入并关闭输出流
  → 验证目标可读及大小/必要媒体信息
  → 发布成品并持久化最终引用
```

- Android10+默认目录：使用MediaStore.Downloads，设置DISPLAY_NAME、MIME_TYPE及RELATIVE_PATH；写入期间IS_PENDING=1，成功后设为0。任务保存返回的content URI。
- 自选目录：通过SAF获取并保持授权，使用ContentResolver/文档接口写入，禁止从content URI猜测绝对路径。
- Android8/9：使用适用旧权限及文件接口，确认父目录确为目录、创建成功和目标可写。
- Windows：使用任务临时文件及最终原子落盘策略，处理同名冲突、磁盘空间和文件占用。
- 导出失败返回EXPORT_FAILED，保留已校验成品；用户换目录后仅重试导出。只删除当前尝试创建的残缺目标，不删除其他任务或已有成品。
- 任务播放/分享/删除入口支持URI与旧路径。进程恢复后识别未完成导出，不能因为已有暂存文件就标完成。

默认路径修复不要求用户授予所有文件访问权限。Android10+自身创建的下载文件可通过系统媒体存储处理。[Android官方说明](https://developer.android.com/training/data-storage/shared/media)

**6. 抖音图文身份修复。** 删除“未找到目标就选第一项”和全页面任意视频兜底。

```text
从支持的链接结构提取requestedId，保留note/video类型线索
→ 结构化解析返回对象
→ 只接受aweme_id严格匹配requestedId的目标
→ 识别目标ContentKind
   VIDEO：枚举该目标的视频/音轨
   IMAGE_POST：枚举该目标的图片与适用资源
   ANIMATED_PHOTO：按实际资源类型展示
   UNKNOWN：明确不支持或需要进一步解析
```

实现要求：

- `UrlSniffer`停止将所有note改为video；身份统一用contentId，内容类型由匹配对象最终确认。
- Feed返回推荐列表但未命中目标时返回MEDIA_ID_MISMATCH，禁止把推荐内容绑定到原ID。
- SSR/路由状态按目标ID定位对象，再取视频或图片；不用全局第一段play_addr或首个CDN URL。
- 已确认是图文后，不再落到通用视频提取器搜其他视频。替代路径必须继续验证同一作品身份。
- 失效旧版抖音媒体缓存；缺少身份依据的持久化直链重新解析。缓存保存已验证的来源ID和类型。
- 首批交付必须阻止串帖并正确展示图文；图片原资源/ZIP可作为独立导出项。背景音乐不是图片的视频轨，图文转MP4作为后续独立功能评估。

**7. X稳定性实现。** 将“本次未取得媒体”与“内容确定无视频”分开，减少重复提取窗口。

截图中的No video错误是提取器未找到可识别条目。上游说明过含义不明确的受限响应，同一句信息不能唯一确定无视频原因；成功与失败日志须对照同一推文、账号、网络和引擎版本。[上游记录](https://github.com/yt-dlp/yt-dlp/issues/14742)

主流程：

```text
当前会话与网络
  → yt-dlp解析目标推文
  → 核对tweetId/sourceTweetId/mediaId
  → 保存ResolvedMediaSnapshot
  → 选择同一媒体的真实格式
  → 合一MP4直接HTTP下载 / HLS或分离轨使用成熟下载器
  → 校验及导出
源过期 → 同一mediaId刷新一次 → 不得切换其他视频
```

具体措施：

1. 会话档案贯穿解析和下载，按域使用同一会话的Cookie；会话更新增加代次并失效旧缓存。
2. 取消固定iPhone UA覆盖，优先使用当前提取器行为；必要请求头以实际响应为依据。
3. 保留脱敏warning与API路径，记录HTTP状态、响应顶层类型、媒体数及回退原因。HTTP200不等于返回有效视频。
4. 明确无访问权限/删除时直接分类；响应空白、结构不完整且无永久不可用证据时返回MEDIA_UNCONFIRMED，不负缓存为永久无视频。
5. 网络最多两次应用层退避重试；未确认媒体最多一次条件重试或已验证替代路线。与引擎内部重试共用预算，429遵循Retry-After。
6. GraphQL继续作为上游主路径；公开内容可验证syndication补充，但其媒体可能不全，上游也已处理部分429回退。记录实际路线，避免重复回退和将其设为唯一接口。[上游提取器](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py)
7. 解析成功的格式和请求上下文直接用于下载，不立即再次解析整条推文。取流尚未成功时，更换HTTP下载器不会消除截图错误。
8. 用户在浏览器能正常观看时，可使用当前目标页辅助取流；必须将响应与tweet/media身份关联，不取推荐流或页面第一个video。blob/MSE地址不直接当成完整文件URL。
9. 多视频保留mediaId和原始位置，删除过滤后序号拼/video/N的猜测；每段独立选流，批量共享质量意图而非首段格式ID。
10. 错误页显示适用的验证、重试、浏览器检查或网络操作，不把所有失败统一解释为需要代理。

稳定性由真实样例验证，分别统计首次成功、有限恢复成功、确认不可用及未确认，不预设100%成功率。

**8. 4K以上优化分为格式发现与格式选择。** 先确保拿到真实高阶流，再排序；给低清视频修改标签或指定一个不存在的格式ID都不能获得高清。

格式发现要求：

- 元数据保留所有实际格式，不以4K为最高档位，不把高阶条目折叠到4K标签。
- 4K、5K、6K、8K及更高使用真实宽高与平台档位显示；未识别尺寸以精确像素标注，不强行套入4K。
- 16:9常见样例中，3840×2160与7680×4320分别独立显示；竖屏2160×3840保留实际方向；4096宽电影画幅使用实际尺寸及可信平台档位，不只按高度猜标签。
- 图片分辨率、封面尺寸、页面CSS尺寸、storyboard与视频帧尺寸分开，不能把高分辨率封面当视频候选。
- 当前账号未获高清、平台未返回、JS/令牌失败、格式被过滤、输出不兼容分别记录。平台仅返回1080P时不能显示4K可下载。
- B站不把固定qn=120及fourk=1视为已覆盖全部8K能力；结合support_formats、accept_quality与实际DASH对象枚举可访问档位。必要时只对响应宣告的更高档位做有界补查，不枚举未知qn碰运气。qualityId、width/height、fps、HDR分别建模，不能用“qn越大必定分辨率越高”的通用规则。
- YouTube保持上游默认客户端与匹配的JS/EJS能力，恢复丢失格式后再选流。Cookie、PO Token等仅在实际要求下接入，同一会话用于格式探测和媒体传输。发布成品中的运行时必须验证可用。[EJS说明](https://github.com/yt-dlp/yt-dlp/wiki/EJS)
- X、抖音仅在真实响应提供高分辨率时使用该候选；不宣称它们普遍提供4K/8K。

**9. 明确质量模式。** 默认新任务使用“最高源画质”，用户指定其他模式后按其意图执行。

| 模式 | 语义 | 示例 |
| --- | --- | --- |
| BEST_SOURCE | 当前账号可取得的最高源分辨率，无人为4K上限 | 同画幅8K与4K均有效时选8K |
| MAX_RESOLUTION | 宽高/方向归一化后的严格上限 | 上限4K时不自动下载8K |
| TARGET_TIER | 用户明确指定档位/尺寸 | 选4K只考虑满足4K定义的候选，低档需允许降级 |
| EXACT_FORMAT | 具体媒体的具体格式/轨道 | 格式失效需重新验证，不应用到其他视频 |
| COMPATIBLE | 用户明确以指定设备/编码要求筛选，再选最高分辨率 | H.264硬要求下可能低于源最高，必须展示结果 |

`allowResolutionDowngrade`默认false，作用于指定档位和精确格式；最高可用模式本来就在完整候选中择优，不把源本身最高只有1080P称为“从4K降级”。最高模式得到不完整列表时显示“当前已取得最高”，不能假装已证明源绝无更高档位。

新任务自动选最高；已有任务保留原质量意图，升级后不能把用户原选720P自动改成8K。已有只有“最佳/自动”文案的任务迁移为BEST_SOURCE，其他文案一次性转换为结构化选择。

**10. 选择器的排序算法。** 默认优先真实空间分辨率；帧率、HDR、编码、协议与码率不能在无用户设置时把8K/4K压到1080P。

处理顺序：

1. 硬过滤：媒体身份一致、正确内容类型、可访问完整内容、协议可处理、非未支持保护、满足用户硬约束。
2. 原始版本与衍生版本分组：若平台明确标注AI放大/插帧，默认优先原始版本组，用户可选择增强版本。无明确证据时标记来源未知，不能靠码率或尺寸猜造假，也不能承诺等同相机原始画质。
3. 处理方向与画幅：依据rotation等可信信息计算显示宽高；优先同一源画幅，裁切、VR、不同投影分组展示，不能只按长边把细长裁切版误判为最高完整画面。
4. 在同一内容、原始版本组和等价画幅内，先比较像素面积，再比较短边，之后比较同尺寸平台可信质量档位、帧率、用户HDR偏好、提取器质量信息和传输偏好。
5. 码率只在同尺寸、帧率、编码及动态范围等可比条件下作辅助排序，不能用1080P高码率压过4K，也不能以H.264文件更大就认定优于同尺寸AV1。
6. 没有可解释的编码质量排序时使用提取器可信排序或稳定确定性次序；不宣称AV1、HEVC、VP9、AVC之间存在不依内容和参数的绝对画质顺序。

同为正常16:9、非增强、无硬限制的示例优先级：

```text
8K 7680×4320 > 6K 5760×3240 > 5K 5120×2880
> 4K 3840×2160 > 2560×1440 > 1920×1080
同为4K：可信同档位条件下60fps可优先30fps
4K30与1080P60：默认最高分辨率模式选4K30
用户显式要求至少60fps：先筛选满足帧率者，再按分辨率排序
```

伪代码：

```text
select(snapshot, intent):
    candidates = validateIdentityAccessAndSupportedSource(snapshot)
    candidates = applyHardQualityConstraints(candidates, intent)
    if empty: return QUALITY_UNAVAILABLE

    group = chooseSourceVariantAndGeometryGroup(candidates, intent)
    ranked = rankWithinEquivalentGeometry(group, intent)
    video = ranked.first()
    audio = selectAudioForSameMediaAndRequestedLanguage(snapshot, video, intent)
    plan = validateOutputContainer(video, audio, intent)
    return plan.withSelectionReasonAndFallbacks()
```

MAX_RESOLUTION必须硬过滤后排序，不能使用绝对高度差最小。yt-dlp的排序偏好如res:2160不等价严格上限；其res按最小维度定义，而单纯height过滤对竖屏语义不同，须让UI和适配器保持同一约定。[格式排序说明](https://github.com/yt-dlp/yt-dlp#sorting-formats)

**11. 编码、封装、HDR及降级规则。** 将“保存最高源”与“本机播放兼容”分开。

- 默认不以本机是否支持硬解码为理由删除4K/8K候选；显示兼容提示。用户选择COMPATIBLE时才按设备/编码要求筛选。
- 默认保留所选源编码，优先无损合并/转封装；不因偏好H.264而自动选择低清，也不自动耗时转码。
- 容器AUTO在可封装前提下选择MP4或其他受支持容器。用户锁定MP4且当前组合无法可靠封装时，提示更换容器、显式转码或同分辨率替代；不能直接降至1080P。
- HDR/Dolby Vision与SDR单独展示。按用户或可信源版本策略选择，不因HDR标签跳过更高分辨率，也不把“HDR”一概当作更高空间清晰度。
- 音视频合一格式与分离轨都参与相同视频质量比较；4K视频轨＋音轨可合并时，不优先1080P合一文件。无音轨内容无需伪造音频，应有音轨却未获取时明确报错。
- 批量任务共享质量意图，每个媒体独立选格式；不能共享另一个媒体的formatId。

失败恢复顺序：同一格式备用CDN → 刷新相同媒体/格式 → 同分辨率且满足要求的替代编码/协议 → 用户允许时降低分辨率。没有允许降级就返回QUALITY_UNAVAILABLE或传输错误，保留原因和可选项。

yt-dlp适配可用 `bv*+ba/b` 作为常规最佳组合基础，但专项中的质量规则优先通过完整格式列表选出明确ID实现。原始高分辨率模式可验证一次性 `-S res,fps` 等排序设置；必须检查提取器优先字段及锁定版本行为，必要时使用自定义选择器。不得无条件启用format-sort-force，避免覆盖具有语义的提取器优先级。不要在重试中切换为 `best/bestvideo+bestaudio`。[格式选择说明](https://github.com/yt-dlp/yt-dlp#format-selection)

**12. UI与结果验证。** 用户能在下载前后看清实际得到的媒体。

格式项建议展示“8K · 7680×4320 · 60fps · AV1 · HDR · 需合并”，未知信息如实省略。普通清晰度列表可折叠同尺寸变体，但其内部对象全部保留；显示尺寸相同而编码/FPS不同的候选时提供展开选择。

任务保存 `requestedQuality`、`selectedQuality`、`actualOutput` 和 `selectionReason`。详情页可说明“最高源模式选择8K”“4K上限排除8K”“当前权限仅返回1080P”“因用户允许改用4K”。不使用“最佳编码轨”等无法证明的固定文案。

成品校验读取真实显示宽高、rotation、帧率、音轨和时长：

- 对比所选源及质量模式，而非解析UI文案；4K目标得到1080P不能普通完成。
- 精确模式以所选格式尺寸为基准，电影画幅不强制凑成3840×2160；不自动放大源补齐4K标签。
- 时长按完整内容/分P及声明的裁剪范围比较，允许容器时间基带来的合理小偏差；阈值由样例验证记录。
- 若只有元数据成功、没有可验证的视频流，不生成4K/8K占位按钮。
- 默认不对每个候选发探测请求，避免为追求高分辨率放大平台请求量；只验证选定源和受控回退。

**13. 实施文件落点。** 下列为后续修改清单，不是本轮源码变更。Android路径前缀为 `app/src/main/java/com/omni/downloader/`，Windows路径前缀为 `windows/`。

| 文件或组件 | 具体实现 |
| --- | --- |
| Android `engine/UrlSniffer.kt` | 保留抖音note类型；按host识别；内容身份与跳转分离 |
| 两端DomesticDirectExtractor | 删除首项回退；按目标ID解析；内容类型分支；旧缓存失效 |
| 两端BilibiliDirectExtractor | 充电/试看判定；高阶格式发现；取消第一条/绝对差选流；接入统一导出 |
| 新增两端 `QualitySelector` / `quality_selector.py` | 硬约束、画幅分组、完整候选排序、同档回退及选择原因 |
| 两端FormatOption/VideoMetadata/DownloadTask模型 | 宽高/编码/HDR等完整字段，质量意图和格式ID分离，snapshot/URI支持 |
| Android `engine/DownloadEngine.kt`、Windows `engine/ytdlp_engine.py` | 完整列表保留，明确格式选择，移除低清优先重试，复用有效快照 |
| 新增 `MediaSnapshotStore` / `media_snapshot_store.py` | 同媒体取流快照、时效、会话代次，敏感URL保护 |
| 新增Android `StorageExporter.kt`、Windows对应输出模块 | MediaStore/SAF/文件输出及仅导出恢复 |
| MediaVerifier及FFmpeg封装 | 校验真实宽高/音轨/时长，无损合并及明确转码选项 |
| SessionRepository/RequestContextFactory | 原生/yt-dlp统一会话及网络；临时凭据文件及脱敏 |
| AndroidMainViewModel/FormatSelectorSheet、Windowsweb/index.html及omni_server.py | 新质量模式、4K以上准确展示、错误动作、每段独立格式 |
| TaskRepository、队列及配置模块 | schema升级、旧文案迁移、错误缓存失效、导出状态恢复 |
| 构建/依赖与测试 | 锁定可运行的Python/yt-dlp/JS/EJS/FFmpeg组合，成品包验证 |

旧任务已有明确质量选择时不改变其意图；无可靠ID的旧抖音/X直链重新解析；已完成文件不重下载。已存文件路径继续可读，新导出优先保存URI。迁移原子写入并保留快照，回退程序须能识别新schema或恢复旧快照。

**14. 分阶段任务。** 下列16项可直接转成实施任务，先交付正确性，再验证高画质和稳定性。

| ID | 任务 | 前置 | 完成标准 |
| --- | --- | --- | --- |
| U01 | 成功/失败样例、引擎清单、脱敏日志 | 无 | 能区分解析、下载、合并、导出四阶段 |
| U02 | 媒体身份/类型及结构化质量模型 | U01 | 任务不依赖展示文案；候选不因4K标签合并 |
| U03 | 抖音严格ID与类型修复 | U02 | 推荐列表与图文SSR样例不串作品 |
| U04 | 会话与请求上下文统一 | U01-U02 | 相同会话贯穿解析、下载、刷新 |
| U05 | AndroidStorageExporter及URI迁移 | U02 | ENOENT等导出故障可单独恢复 |
| U06 | B站充电完整/试看判定 | U02、U04 | 三种权限会话及分P结果正确 |
| U07 | 两端高阶格式发现与去重修复 | U02、U04 | 4K/8K等实际候选完整独立保留 |
| U08 | 共用质量选择算法与模式 | U07 | 最高/上限/指定档位/兼容模式符合规则 |
| U09 | X取流快照与稳定媒体定位 | U02、U04 | 成功解析后直接消费正确媒体快照 |
| U10 | X有界恢复及诊断UI | U01、U09 | 未确认无媒体不永久判无视频；路线可解释 |
| U11 | 同分辨率优先恢复及媒体合并 | U08-U09 | 网络失败不静默切到低清单流 |
| U12 | 高分辨率UI与批量任务 | U08、U11 | 8K不会显示为4K；分段独立选流 |
| U13 | 成品校验与仅导出重试 | U05、U11 | 请求、选择、成品三者可核对 |
| U14 | 旧数据迁移与恢复测试 | U02-U13 | 历史任务意图保留；错误直链失效 |
| U15 | Android真机、Windows干净环境验收 | U03-U14 | 各发行形态媒体执行及4K以上成品记录 |
| U16 | 发布候选与回退演练 | U15 | 源码、依赖、产物、能力矩阵及验证记录一致 |

建议候选阶段A：U01-U06，先阻止串帖并修复导出和充电判定。阶段B：U07-U13，增加高阶发现、排序和X恢复。阶段C：U14-U16完成迁移、成品包及发布验收。版本号在实际开发基线确认后分配，不覆盖原规划的既有版本承诺。

工程初估：基础模型/会话/诊断2–4人日，抖音与B站权限/存储4–7人日，高分辨率与X链路5–9人日，跨端实机/打包/迁移回归3–5人日，共14–25人日，另预留约20%平台变化余量。它与原综合规划有重叠，不可直接相加；样例、设备或运行时适配缺口会影响估算。

**15. 验收矩阵。** 离线fixture负责可复现的选择行为，网络和真机验收负责平台及成品真实性。

| 编号 | 样例/条件 | 预期结果 |
| --- | --- | --- |
| V01 | 抖音目标不在返回列表，第一项是其他视频 | 拒绝错配，不缓存或下载其他作品 |
| V02 | 目标是图文，页面有推荐视频 | 只识别目标图文，视频按钮不适用 |
| V03 | 旧抖音任务含未验证直链 | 重新解析，旧错配不继续生效 |
| V04 | B站同链接分别使用有权益、无权益、到期会话 | FULL/PREVIEW/DENIED/UNKNOWN有证据，不误报完整 |
| V05 | B站多P，只有某分P下载 | 按该cid、实际分P时长验证 |
| V06 | 下载合并有效，公共目录不可写/不存在或SAF授权失效 | EXPORT_FAILED保留成品，换目录只导出 |
| V07 | X同推文成功与失败时段日志 | 可比较会话/网络/版本/响应类型和回退路径 |
| V08 | X200响应缺媒体，或429后syndication缺媒体 | 不直接永久判无视频；恢复次数受控 |
| V09 | X多视频/引用推文、过期源 | 保持mediaId，不用过滤后序号替代 |
| V10 | 同一16:9内容有8K、4K、1080P | BEST_SOURCE选8K，不被4K标签去重覆盖 |
| V11 | 仅4K的HEVC/AV1/VP9与1080P AVC | 默认保留4K，不因AVC偏好降分辨率 |
| V12 | 4K30与1080P60 | 默认最高选4K30；明确至少60fps时另按约束选择 |
| V13 | 上限4K且只有8K和1080P | 严格排除8K；选定4K档位而未允许降级则报告不可用 |
| V14 | 竖屏2160×3840、电影画幅4096×1716 | 尺寸和方向正确，档位依据可信元数据，不按height误标 |
| V15 | 返回条目顺序随机打乱 | 选择结果稳定，不取第一项碰运气 |
| V16 | 多编码同尺寸、码率未知或不同编码码率倒置 | 不错误去重；未知不当零；不跨编码仅按码率判质量 |
| V17 | 4K视频轨＋音轨，对比1080P合一流 | 有合并能力时选择4K并取得适用音轨 |
| V18 | 8K选择成功，下载中断或备用CDN切换 | 保持质量意图，不静默落到360P/1080P |
| V19 | HDR/SDR/DV，MP4/AUTO容器，设备不支持硬解码 | 按显式策略处理；提示兼容性，不伪装降低画质 |
| V20 | 明确原版4K与AI增强8K；来源未知的8K | 原版/增强分组规则生效；未知不凭空认定AI或原生 |
| V21 | 界面选8K，实际输出被故障替换为4K | 校验失败或明确用户允许的降级，不普通完成 |
| V22 | 两任务并发、取消、进程终止、导出重试 | 暂存隔离，终态不倒退，不误取其他任务成品 |

高分辨率网络样例需先用同一有效会话确认确有目标格式；没有8K样例时可完成排序fixture，但不得宣称8K端到端通过。Android至少覆盖现有最低版本ARM64和较新系统真机，Windows覆盖干净环境的桌面独立版与网页后台版；全部使用最终打包依赖，不借用开发机隐式组件。

**16. 交付与限制。** 实施完成后提交源码变更、模型/schema迁移、精确依赖版本、安装产物及校验值、分平台能力矩阵、测试记录和回退说明。设置页可查看真实引擎状态；任务详情可核对媒体身份、选择理由和实际输出。

本文已完成的是规划及源码层面设计。尚未获取用户环境的成对X日志、抖音原链接及有权限的充电样例，未验证4K/8K网络下载、Android存储导出或iOS媒体执行；这些内容保留为实施验收条件。不能把平台不提供的格式、未获权限的高清或仅有标题标签写成已支持。

源码与参考依据：

- [项目基线](https://github.com/baishu136/OmniDownloader/tree/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6)
- [Android抖音目标匹配与首项回退](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/app/src/main/java/com/omni/downloader/engine/DomesticDirectExtractor.kt#L140)
- [AndroidB站导出](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/app/src/main/java/com/omni/downloader/engine/BilibiliDirectExtractor.kt#L542)
- [AndroidB站选流](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/app/src/main/java/com/omni/downloader/engine/BilibiliDirectExtractor.kt#L845)
- [Windows高分辨率标签与去重](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/windows/engine/ytdlp_engine.py#L174)
- [WindowsB站目标未命中时选首流](https://github.com/baishu136/OmniDownloader/blob/5ff8ff48b1fa10b9fe9f9b7b70a072ccf4c72ae6/windows/engine/bilibili_extractor.py#L274)
