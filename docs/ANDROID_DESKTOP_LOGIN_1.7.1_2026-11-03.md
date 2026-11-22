# Android 1.7.1 桌面登录修复（2026-11-03）

## 行为

设置 → 账户与会话 → 选择小红书 / 抖音 / 快手 → 网页登录，默认请求桌面网站。支持切换手机版、重新加载登录入口、双指缩放，以及由用户点击打开的登录子窗口。顶部显示当前网站域名。

完成网页登录后，点击“我已登录，保存会话”。这三个平台不会再因为检测到匿名 / 旧 Cookie 自动关闭登录页。保存状态仍是“已导入会话”，不是已验证账号权益；解析时若认证失败会要求更新会话。

## 改动

1. 去除旧的 Android/Mobile 固定 UA，按设备实际 WebView 的 Chromium 版本生成桌面 UA。
2. 新增 AndroidX WebKit 1.11.0；在 WebView 支持时同时设置 UA Client Hints 的 mobile=false、Windows 平台及桌面架构，去掉 Android WebView 品牌项，避免 UA 与 Client Hints 矛盾。
3. 在文档开始阶段为顶层页面设置 1280 像素桌面视口，并处理网站重新写入 viewport 的情况，避免只改 UA 后仍按手机宽度显示。允许双指缩放；切换回手机版会恢复原始 UA 和 Client Hints 并重新加载。
4. 登录控制器支持用户主动打开的弹窗，保留 window.opener、相同浏览器身份与共享 Cookie；支持返回、关闭子窗口及完整释放。未忽略 TLS 证书错误，登录页不开放文件访问或混合活动内容。
5. Cookie 恢复等待全部异步 setCookie 回调完成后再加载网页；保存时 flush。未删除用户已有会话。
6. 平台辅助浏览器、B站旧登录入口、中转浏览器的第一方平台链接复用桌面身份策略；小红书分享短链和 B站短链也纳入。普通第三方中转站保留系统默认模式，避免影响既有解析脚本。
7. 小红书登录入口使用 /explore，快手使用 /new-reco，抖音使用网站主页。YouTube 保留系统默认浏览器身份，不尝试以桌面标识改变 Google 对嵌入式登录的限制；原有 cookies.txt 导入入口保留。

## 验证

- 74 项 JVM 测试通过，含桌面 UA 的真实内核版本、域名边界、短链、匿名 Cookie 不自动关闭、精确 Cookie 名和原生应用跳转策略。
- Android 15 模拟器，WebView 124.0.6367.219：5 项 WebView / 中转回归测试通过。真实本地 HTTP 请求验证 UA 和 Client Hints；真实 JavaScript 验证 1280px 宽度和手机版恢复；真实触摸打开子窗口并验证 window.opener 和窗口关闭；Cookie 回调顺序验证通过。
- ARM64 Release 构建、ZIP 完整性与 ABI 校验通过。最低 Android API 26、目标 API34 没有改变。
- 当前未连接物理手机，未使用真实账号完成扫码、短信或验证码验证。上述测试不等同于所有平台、任意时段均能登录；平台风控、网络问题及旧版 WebView 仍可能影响实际登录。旧引擎不支持 Client Hints API 时，使用 UA 和视口兜底。
- Windows 与 iOS 本轮没有修改。已有未提交的工作目录改动保留，未提交 Git 或公开发布。

## 交付

`release/1.7.1/OmniDownloader-1.7.1-arm64-v8a.apk`，可覆盖安装以保留数据。校验值见同目录 `SHA256SUMS.txt`。

实现入口：`BrowserIdentity.kt`、`WebViewDesktopMode.kt`、`LoginBrowserController.kt`、`PlatformLoginPolicy.kt`、`BrowserCookieRestorer.kt`。

参考：Android WebSettings.setUserAgentString、AndroidX WebSettingsCompat.setUserAgentMetadata / UserAgentMetadata、WebViewCompat.addDocumentStartJavaScript 官方文档。
