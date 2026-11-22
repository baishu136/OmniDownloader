# Android 1.7.2 登录后下载报错修复（2026-11-03）

## 结论与原因

用户提供的 Android 日志定位到 Cookie 文件格式错误，而非引擎组件缺失。旧序列化器内部去掉域名前导点后，输出时仍使用 includeSubdomains=TRUE，产生 `youtube.com<TAB>TRUE`。CPython MozillaCookieJar 要求子域标志与域名前导点一致，因此加载时触发断言，yt-dlp 无法进入正常解析流程。相同序列化器用于全部 Android 平台的网页 Cookie 捕获和 Netscape 文件导入，也影响使用临时 Cookie 文件的通用视频引擎及共享图文解析器。

错误分类原先只要文本包含 python 就判为 ENGINE_UNAVAILABLE，堆栈里的 Python 路径因此掩盖了实际原因。旧脱敏规则也没有覆盖 CookieJar 在异常中回显的 Netscape 行。本报告及回归样例未复制用户 Cookie 值。

## 实现

- CookieRow.toNetscapeLine 按 includeSubdomains 输出恰好一个前导点或无前导点；保留 HttpOnly、Secure、path、expiry、name/value。内部域名仍保持无前导点，原生 HTTP 域匹配与 WebView 恢复无需改变。
- SessionRepository.tempCookieFile 每次使用前解析、按平台过滤、去掉过期项并重新序列化，覆盖旧版本已加密保存的错误文本。没有破坏性迁移，不修改会话代数和导入时间，无需仅为该格式问题重新登录或重新导入。
- 拒绝多余字段及行内控制字符；没有有效 Cookie 时明确返回 AUTH_REQUIRED，不悄悄降级为匿名请求。临时文件写入失败会清理并报错，正常任务仍沿用原有 finally 清理机制。
- 新增 COOKIE_INVALID 分类，缩小 ENGINE_UNAVAILABLE 识别范围，避免任意 Python 堆栈被误报为组件缺失。
- Netscape Cookie 行脱敏覆盖真实制表符及 Python repr 的文字 \t 转义。复制错误详情和 SafeLog 均复用该脱敏器。

## 验证结果

- JVM：79 项测试通过。新增/补强各平台旧文件规范化、子域及 host-only 序列化、非法字段、Cookie 错误分类和脱敏回归。
- Android 15 API35 x86_64 模拟器：3 项 CookieSessionEngineTest 通过，使用应用真实 Python/yt-dlp 运行时。
  1. 为 12 个平台创建隔离、加密、合成的旧版会话，实际从仓库导出；CPython MozillaCookieJar、yt-dlp YoutubeDLCookieJar、共享图文解析器 requests 均可读取，验证域/路径属性与跨站隔离，原导入时间和代数保留。yt-dlp 本身会剥除 HttpOnly 注记，标准 CookieJar 的 HttpOnly 标记已单独验证。
  2. 从浏览器 name=value 形式导入合成会话，使用生产参数工厂执行真实 yt-dlp 本地媒体元数据解析及文件下载，成品字节与测试媒体一致。
  3. 已过期会话明确要求重新认证，未生成空会话文件或匿名回退。
- ARM64 Release 构建及 lintVital 通过；APK ZIP 完整性、ABI、版本和签名另行校验。最低 API26 / 目标 API34 保持不变。
- 所有测试会话使用独立首选项和目录，不读取、替换或上传真实账号会话。Windows 与 iOS 本轮没有修改，工作目录中其他已有改动保留。

## 交付和使用

覆盖安装 `release/1.7.2/OmniDownloader-1.7.2-arm64-v8a.apk`，保留应用数据，然后重新解析链接。已有会话会在使用时规范化。若 Cookie 已过期或被平台撤销，仍需重新登录；这与本次文件格式错误不同。

当前没有连接物理手机。本轮没有使用真实账号进行 YouTube/X/国内平台在线登录或下载，模拟器本地链路回归不代表任意平台风控、网络、验证码和内容权限均已通过。

校验值见同目录 SHA256SUMS.txt；结构化结果见 TEST_RESULTS_1.7.2.json。

参考：[CPython 3.12 MozillaCookieJar 实现](https://github.com/python/cpython/blob/3.12/Lib/http/cookiejar.py)；yt-dlp CookieJar 行为核对自项目内置的 yt-dlp 源码。
