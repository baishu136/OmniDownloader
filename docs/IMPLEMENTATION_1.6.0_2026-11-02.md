# OmniDownloader 1.6.0 实施与验收记录

日期：2026-11-02。Android versionCode：58；最低 Android 8.0，发行 ABI 仍为 arm64-v8a。Windows：x64，Python 3.12.11 打包运行时；桌面版需要 Microsoft Edge WebView2，托盘网页版使用外部浏览器。

本次在工作目录现有 1.5.16 / code 57 未提交修改的基础上继续实现，保留了原有 Android、Windows 和 iOS 改动。iOS 不是这两份更新规划的实施目标，本次未继续修改或验证 iOS。

对应规划：[完整规划](UPDATE_PLAN_2026-11-02.md)、[B站/抖音/X/高分辨率专项规划](UPDATE_PLAN_SPECIAL_2026-11-02.md)。这是可安装的测试候选版本；平台账户及 Android 真机验收尚未完成，不能把下面的本地测试当成所有平台的成功率承诺。

## 实现结果与能力矩阵

| 功能 | Android | Windows | 权限、依赖及限制 | 验收证据 |
|---|---|---|---|---|
| YouTube 高清及 360P 问题 | 移除强制客户端策略；真实格式排序；精确视频轨与音频合并；更新内置引擎和 EJS | 同样使用上游默认客户端、独立音视频及明确格式选择 | bot challenge 仍需要有效会话/人工验证；平台只返回低清时不能凭空生成高清 | 模拟器实际执行 yt-dlp 2026.10.26、QuickJS；Windows 两种 EXE 实际媒体下载和合并 |
| X 暂态解析失败 | 保留 warning；错误分类；有界重试；已解析的合一流直接消费；过期时按 mediaId 和格式刷新一次 | 10 分钟、会话代次及代理隔离的快照；子媒体用 mediaId 定位；无媒体重试；匿名条件下 syndication；过期源刷新一次 | 不能保证所有时段成功；登录、限流、删除、无媒体未确认分别报告；不会按过滤后的视频序号拼接地址 | ID/错误分类回归、本地传输、成品校验；真实 X 时段对照待验收 |
| 小红书登录包装链接 | 只解外层 redirectPath；保留 xsec_token；按笔记 ID 获取结构化视频流 | 同样处理登录包装、目标 ID、图文和验证状态 | 不支持将图文笔记当视频；失效 token 或验证需要人工处理 | 用户所示 URL 类型的参数保留与跨域拒绝测试 |
| 抖音图文误指其他视频 | 删除 feed 第一项回退；SSR 只匹配目标 ID；图片内容明确拒绝视频下载 | 同样严格匹配 ID，不再取推荐列表首项 | 最低纠错交付为识别图文并停止；本次没有新增“图文合成 MP4” | 错配、图文、推荐项拒绝样例测试 |
| B站充电专属 | 通过 nav 验证账号并加密记录验证状态；使用对应会话请求官方播放源；检查播放错误、试看标记和分P时长；精确 qn/编码；不伪造格式列表 | 同样验证账号、识别充电标识并检查播放返回；原生取得 DASH 后交给统一下载/合并/验证流程 | 仅能保存账户实际获准取得的普通媒体流；充电权限不能由大会员或 Cookie 存在代替；未实现 DRM 解密。部分接口或专属内容可能仍需适配 | 试看分类、分P身份/时长代码校验；有权益/无权益/到期三组真实账户未提供，未验收 |
| 4K/5K/6K/8K 及更高尺寸 | 保留真实格式 ID、宽高、帧率、编码及色彩范围；最高源、严格上限、指定档位、兼容优先、精确格式 | 同样排序；删除“2160以上都叫4K并去重”的逻辑；逐任务选流 | 上限采用可比较的短边尺寸，展示保留完整宽高；默认不静默降级。HDR/SDR 只使用明确标记，未知不猜测；兼容优先是显式选择 | 质量策略测试；Windows 真实 7680×4320 与 3840×2160 文件的下载/导出验证 |
| Android ENOENT / 导出失败 | 私有任务目录先下载、处理、验证，再通过 MediaStore Downloads / SAF / 旧版路径导出；失败保留成品；原 ID 重试 | 私有任务目录、完成记录、原子导出；失败保留成品；原 ID 重试 | SAF 必须保留有效目录授权。Android 8/9 公共路径仍受系统存储权限约束 | 模拟器真实 HTTP 下载→无效 SAF 导出失败→关闭服务器→换目录后仅导出成功；Windows 同类故障恢复测试 |
| 小黑盒 | 分享页公开视频源 MP4/HLS；动态页提供人工浏览器辅助及确认入口 | 分享页视频标签 MP4/HLS；桌面版提供辅助窗口，读取已经播放的视频元素，人工确认后下载 | 受限支持。SPA、blob、加密流及未公开媒体不保证可保存；网页托盘版没有内嵌辅助窗口 | 静态提取样例、真实本地 HLS 播放列表解析/下载；真实平台帖子待验收 |
| Pixiv | Ugoira 元数据、帧 ZIP、逐帧时长；MP4/GIF/原始 ZIP+JSON | 同样支持；VFR、末帧、奇数尺寸补齐；安全 ZIP 检查 | 当前针对 Ugoira，不将插画、多页漫画、FANBOX、Sketch 标为视频支持。透明度和 GIF 时间量化受目标格式限制 | Android/Windows 实际非等时帧转码；80/230/390ms，101×99 补齐到102×100；ZIP 路径及大小限制测试 |
| 会话与日志 | Keystore AES/GCM；分平台导入；唯一临时 Cookie 文件；任务文件加密；日志脱敏；等待登录/验证后原任务重试 | Windows 用户 DPAPI；分域 CookieJar；唯一临时文件；任务文件加密；本机会话管理 API | Netscape 导入保留域/路径/到期信息；WebView 只能提供较弱的 Cookie 信息。会话“已导入”不等于已验证权益 | 域隔离/到期/解析测试、DPAPI 密文存储测试、API 同源保护与状态返回测试 |

## 关键代码入口

- Android：`engine/QualitySelector.kt`、`YtDlpOptionsFactory.kt`、`BundledEngineInstaller.kt`、`DownloadEngine.kt`、`StorageExporter.kt`、`MediaVerifier.kt`、`MediaErrors.kt`、`SafeLog.kt`、`NetworkProxy.kt`。
- 平台适配：Android `BilibiliDirectExtractor.kt`、`DomesticDirectExtractor.kt`、`HeyboxExtractor.kt`、`PixivExtractor.kt`、`UgoiraProcessor.kt`；Windows `engine/platforms.py`、`bilibili_extractor.py`、`ytdlp_engine.py`、`quality.py`、`browser_assist.py`。
- 会话：Android `data/repository/SessionRepository.kt`，Windows `core/session_store.py`。登录/验证失败的任务显示 `WAITING_FOR_AUTH`，导入有效会话后点击重试，保留原任务及质量意图。
- 恢复：Android `TaskRepository.kt`、`MainViewModel.retryTask()`；Windows `core/task_manager.py`、`POST /api/tasks/{id}/retry`。导出重试优先使用任务目录中的已验证文件，不重新访问平台。
- 两端 UI 已接入质量策略、会话、诊断和重试。Android 完成记录支持 content URI 的打开、分享及删除。

## 本地验证结果

1. Android 66 项 JVM 测试通过。当前 Windows 中文工作路径下 Gradle Test Worker 的参数文件存在编码问题，因此增加 `tools/run_android_unit_tests.py`：仍使用 Gradle 编译并取得真实运行类路径，随后通过 Unicode 命令行运行 JUnit。不是忽略失败测试。
2. Android API 35 x86_64 模拟器新增 3 项 instrumentation 测试通过：引擎/QuickJS 实际运行与 MediaStore 字节校验；Ugoira 实际编码与时长/尺寸校验；真实下载后故障导出及离线重试导出。
3. Windows 22 项 pytest 测试通过，包括真实 HTTP 传输、纯视频去声轨、原 ID 导出恢复、HLS 清单展开、实际 4K/8K 选择、Ugoira 转码、会话密文、URL/ID/错误/API 检查。
4. Android Release APK 构建通过。沿用项目既有的 release 签名配置；这是本地测试候选包，没有上传商店或 GitHub Release。
5. Windows 桌面独立版和托盘网页版已分别从生成的 EXE 启动。最终回归使用受控的本地 HTTP 媒体服务及显式测试代理，排除系统自动代理对本地请求的影响；两种 EXE 各实际执行音画、纯视频、纯音频三类下载，共 6 个任务完成，并独立读取成品检查宽高及音轨。
6. Windows 实际窗口已被系统识别，WebView 页面可读取；当前计算机截图捕获返回 timeout，因此未把截图/布局视觉验收标为通过。未完成多显示器、DPI 和所有 Android 机型的布局验收。

测试不使用用户 Cookie、不进行充电购买、不修改远端账户内容。测试媒体为本地生成的纯色画面/正弦音及帧资源。

## 规划项的交付边界

| 规划 | 当前状态 |
|---|---|
| U01–U05 / T01–T06 | 诊断、身份与质量模型、会话、存储、迁移和内置运行时已有实现；具体设备/站点仍按下述待验收项处理 |
| U06 / T18 | B站账号核验、授权取流、试看及分P校验已有实现；完整账户权益矩阵未完成真实验收，不能声称全部充电内容均可下载 |
| U07–U13 / T07–T11、T19–T21 | 真实候选、策略、快照、错误恢复、UI、成品校验及仅导出重试已有实现，并通过相应本地测试 |
| U14 | 数据模型升级、保留旧意图、敏感数据加密和备份已有实现；生产历史数据规模的回退演练尚未完成 |
| T13–T14（M2） | 小黑盒首版适配和受限浏览器辅助已有实现；缺少真实帖子成功/失败样本，仍标受限支持 |
| T15–T16（M3） | Ugoira 两端实现及本地实际编码通过；真实平台登录、长帧包、透明 GIF 等仍待平台验收 |
| U15 / T12、T22 | Windows 当前实际环境和 Android 模拟器通过上述检查；Android 真机、干净 Windows 和真实账户测试未完成 |
| U16 | 已生成源码、锁定依赖、候选安装包、能力矩阵和校验值；不宣称已完成公开发布或生产回退演练 |

## 构建与复测

Android 使用 JDK 17、现有 Android SDK 和 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleRelease
python tools\run_android_unit_tests.py
.\gradlew.bat -PomniTestAbi=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.omni.downloader.engine.MediaPipelineTest com.omni.downloader.test/androidx.test.runner.AndroidJUnitRunner
```

默认发行包只含 arm64-v8a；`omniTestAbi=x86_64` 只用于模拟器，不改变最低系统要求。

Windows 使用 Python 3.12；`requirements.txt` 锁定直接依赖，`requirements-lock.txt` 记录本次完整环境。`build_release.py` 写入版本子目录，不停止或覆盖正在运行的旧版程序：

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r windows\requirements-lock.txt
.venv\Scripts\python.exe -m pytest windows\tests -q
.venv\Scripts\python.exe windows\build_release.py --mode all
```

`windows/tools/prepare_runtime.py` 从固定的官方 Deno 发行地址下载，校验固定 SHA-256 后提取；FFmpeg 由锁定的 imageio-ffmpeg 提供。已有运行时可离线复用。PyInstaller 收集 EJS 脚本、yt-dlp 模块及包版本元数据，避免开发环境可用、发行包缺脚本的问题。

Android 的官方 yt-dlp zipapp 及 SHA-256 已放入 `app/src/main/assets/engine/`。首次初始化原子替换较旧引擎，不覆盖更新的用户引擎；zipapp 自带 EJS。当前 wrapper 的 Python 3.12、QuickJS 及 FFmpeg 原生库保持原 ABI 供应方式。

## 依赖来源及剩余验收

- yt-dlp 2026.10.26：[官方发行](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26)，zipapp SHA-256：`e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3`。
- yt-dlp-ejs 0.8.0：[官方 EJS 仓库](https://github.com/yt-dlp/ejs)；Windows 使用 Deno 2.9.8：[官方发行](https://github.com/denoland/deno/releases/tag/v2.9.8)。运行时要求参考 [yt-dlp EJS 说明](https://github.com/yt-dlp/yt-dlp/wiki/EJS)。
- Windows 安装形态必须保留 EXE 旁的 `_internal` 目录，不能只复制 EXE。运行数据位于 `%LOCALAPPDATA%\OmniDownloader`，`OMNI_STATE_DIR` 仅作为隔离测试/显式部署覆盖项。
- 更新前保留原 APK/便携包与原数据备份；新任务含 schema 3 和加密记录，旧客户端不保证可读取。回退程序时应配合相应版本数据备份，不能直接把新加密任务文件交给旧版解析。
- 仍需用户设备与对应权益账户验收 B站充电视频、小红书验证接续、X 不同时段及多媒体/引用推文、YouTube 反机器人及真实高阶清晰度。小黑盒动态内容和 Pixiv 长帧包也需要真实平台样例。这些条件尚未具备，故本次不声称“所有规划已通过生产验收”。

账户核验补充：两端使用官方 nav 响应的 code / isLogin / mid 验证当前账号，加密记录验证时间，并展示账号名称；`is_upower_exclusive` 优先读取视频字段，其次读取 rights。FULL 需要可解释的流、账户/内容类型及完整时长证据，Cookie存在或时长匹配本身不产生FULL。真实接口未给出必要证据时保留UNKNOWN。播放适配保留 playurl 主通道；仅在协议/接口不匹配（-400/-404）时尝试一次 WBI 签名通道，保留相同 BV、cid、qn、Cookie 和代理。认证、试看、验证及限流错误不通过换接口绕过。WBI 参数与签名使用锁定 yt-dlp 的独立测试向量验证；真实充电账户的服务端兼容性仍待验收，不宣称已经覆盖全部专属内容。

Windows升级操作：若旧版托盘服务仍在运行，请先退出旧版再启动新版本，以免默认58000端口继续对应旧服务。本次构建保留了旧版进程和文件，没有替用户强制结束正在使用的旧服务。
