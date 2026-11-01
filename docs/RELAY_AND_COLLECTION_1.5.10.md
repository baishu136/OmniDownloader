# 1.5.10 合集、中转与下载页修正

本次针对 Android 下载器。Windows 网页、桌面程序及 iOS 现有改动保留；项目最低系统、目标 SDK 和正式包 ARM64 支持范围不变。

- 合集保持列表顺序，默认按分享链接和解析后的真实视频身份选中原视频。Bilibili 保留分P参数并使用对应 CID 获取格式；YouTube 保留原有 watch/shorts/短链接身份；X 保留独立视频序号。通用条目优先使用真实网页地址，不再为任意网站拼造 `/video/N`。合集未包含当前视频时保留当前元数据，不静默跳到首集。
- 嗅探有实际作用，保留其媒体请求、响应、播放器与下载链接捕获能力，统一接入静默解析和窗口排查。移除网页窗口的“嗅探猫”入口、资源面板、深度扫描和浮动资源条。手动浏览器仍有返回、填入链接、刷新和外部浏览器入口。
- 自动填入与提交每个文档只执行一次；支持真实输入事件、表单和站点按钮。保留 GreenVideo 的现有 Pinia 提取与转码结果适配，轮询有次数和时间上限。Fetch/XHR 观察响应，不将 POST 解析 API 改发 GET；JSON 读取限制 2 MiB，递归深度与条目数有上限。MIME 类型补充无扩展名的视频、音频和播放清单识别。
- 捕获前要求已提交解析或发生明确下载动作。URL 需为合法 HTTP(S) 媒体证据，排除安装包、网页和图片，CDN 按实际主机及子域匹配。多来源回调通过单次完成门控，UI 回调回到主线程；取消、关闭和换站后迟到回调不再入队，弹出窗口随会话清理。
- 下载页顶部使用单个持续存在的选中背景，位置随最新选择平滑变化；移除多块选中背景交叉淡入淡出。标签增加 Tab 选中语义，下载数量变化不重建指示器。
- 设置 → 关于增加 `https://github.com/baishu136/OmniDownloader` 可点击入口，地址依据本地 Git origin。没有浏览器时复制地址。

全部功能、测试代码和本说明收尾后再统一执行 JVM、真实 Android WebView/Compose 检查和构建。结果另存本次 scratch 验收目录；未执行的检查不写成通过。用户提供的参考站为 greenvideo.cc，反馈现有小范围测试没有捕获失败，不能将本次增强描述为已复现该站故障。

局限：未知站点、登录/验证码、blob/DRM 媒体和所有动态网页结构均不能保证通用支持。当前直链下载管线支持 HLS，尚不支持 DASH MPD，因此捕获层明确排除 MPD，避免误当视频文件入队。保留窗口排查；不增加验证码绕过、账户获取或系统权限。合成 WebView 测试不等于各第三方站点的实时下载验收，没有原合集链接时须明确该项限制。

实现依据：[WebView JavaScript 接口线程说明](https://developer.android.com/reference/android/webkit/WebView#addJavascriptInterface(java.lang.Object,%20java.lang.String))、[WebViewClient 资源请求观察](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest))；用户参考站：[GreenVideo](https://greenvideo.cc/)。
