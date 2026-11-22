# OmniDownloader 1.7.5 修复及会话 Cookie 开关（2026-11-03）

## 已完成

- Android 移除 Instagram 固定 iPhone UA，让 yt-dlp 管理平台请求身份。同一主样例现已解析、下载、验证音轨并导出；备用解析也通过。
- Android Facebook 下载复用现有 MetadataSnapshots 和 --load-info-json，解析后选择的格式不再因立即再次提取导致 ID 改变而失效。原先失败的主样例完整下载并保存通过。
- Android / Windows 快手从目标 VisionVideoDetailPhoto:<ID> 的 Apollo videoResource、manifest 或 photoUrl 中提取真实流，覆盖主、备用 CDN。只接受目标 ID，不全页搜第一个视频。未返回内容页时明确提示浏览器辅助，不再当作作品没有视频。Windows 也修复了备用 CDN 路径相同而格式 ID 重复的问题。
- Windows B站支持 ugc_season.sections.episodes 展开和已有分 P 展开。每集保存自己的 BV/CID、时长及封面，下载时重新核验该集。此次在线两个合集展开 159 / 165 集，并抽一个子集完成保存。

## Cookie 开关

Android：设置 → Accounts/账户 → 平台会话 → 选择平台 → “禁用 Cookie”。Windows：设置 → 平台会话 → 选择平台 → “禁用所选平台 Cookie”。

开关按平台独立保存，默认允许使用已保存 Cookie。禁用后：

1. 新解析和新下载不使用该平台已保存的账号 Cookie，凭据密文保留；重新导入或网页登录保存不会自动开启，用户需明确重新启用。
2. yt-dlp 不生成/加载该平台的临时 Cookie 文件，原生 HTTP 不读取已保存头，WebView Cookie 恢复跳过。旧直链请求头及浏览器 Cookie 回退也遵守开关。
3. B站原有独立 Cookie/SESSDATA 设置不能绕过禁用状态；不会继续使用旧的静态账号 Cookie。
4. 解析缓存使用包括 Cookie 策略修订号在内的键。禁用后再开启也不会复用早先登录态下的旧快照。Windows 临时预览缓存随开关变化清理。
5. 已有浏览器登录状态不会被清除；专用网页登录仍可管理凭据。需要网页登录态的第一方浏览器辅助在禁用时会要求明确开启后继续。

该开关影响后续解析与新任务，不是中断正在发送的请求或清空浏览器存储；平台匿名访客/反爬 Cookie 也不等于已保存账号会话。

## 验证

| 项目 | 结果 |
| --- | --- |
| Android JVM | 91 项通过 |
| Python / Windows | 40 项通过 |
| 模拟器 Cookie / 引擎测试 | 4 项通过，含 12 平台加密合成会话禁用、导入保持禁用、重新启用及请求代数失效 |
| Android 全量解析 | 39 条中 33 条返回媒体；海外使用临时宿主机系统代理通道，国内直连 |
| Android 修复针对性回归 | 11 条全部解析成功；B站、快手、X、Instagram、Facebook、小红书图文共 6 组实际保存通过 |
| Android 开关界面 | 实际点击禁用，状态和开关联动通过；再开启恢复，截图/层级用于检查排版 |
| Windows 解析 | 两个 B站合集、两条快手、两条 Instagram、两条 Facebook 均通过 |
| Windows 实际保存 | 快手及 B站合集子集成品通过尺寸、时长、音轨校验 |
| Windows 打包程序 | 独立 EXE 实际启动；打包后的开关 API、导入仍禁用、重新开启、跨站拒绝及快手任务队列下载通过 |
| 包与启动入口 | Android ARM64 Release、Windows Desktop/Web ZIP 完整性通过；本地 LLMPET 启动入口更新成功 |

Android 针对性成品包括：快手 960×720 / 33.139 秒 / 有音轨；Instagram 720×1280 / 15.372 秒 / 有音轨；Facebook 主样例 360×640 / 27.214 秒 / 有音轨。Windows 打包程序快手成品 5,513,556 字节、960×720、33.02 秒、有音轨。

全量解析仍受限的样例：YouTube 备用需要人机验证；抖音两条图文需要浏览器验证；小黑盒两条分享页没有公开流，仍需手动播放辅助；Pixiv 备用 Ugoira 匿名请求 HTTP404。YouTube 主样例本轮可返回格式，但不能据此声明风控问题已普遍消失。没有自动处理 CAPTCHA 或绕过账号权益。

当前未连接真机，未用真实账号验收充电视频、已登录会话或付费内容。本轮视频保存选择低/中等实际格式；没有声称验证全部 4K/8K 和所有编码。Windows Web 模式完成构建，未单独启动托盘程序；iOS 本轮未改动。

Windows 独立版的可视窗口重启被自动审批拒绝，工具仅返回 blocked by policy；因此可视界面复验没有计为通过。使用原已启动的隔离后台完成接口和实际下载验收后，测试进程已安全停止。

## 交付

- Android：release/1.7.5/OmniDownloader-1.7.5-arm64-v8a.apk，可覆盖安装，签名与上一版相同；最低 API26、目标 API34 不变。
- Windows 桌面：windows/release/OmniDownloader_Desktop-1.7.5.zip。
- Windows Web/托盘：windows/release/OmniDownloader_Web-1.7.5.zip。
- 校验值：docs/SHA256SUMS-1.7.5.txt。
- 逐条结果：docs/TEST_RESULTS_1.7.5.json。

未提交 Git、未公开发布。保留工作目录中其他既有修改，没有覆盖 iOS 修改或删除旧版发布目录。
