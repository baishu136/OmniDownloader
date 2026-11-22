# Windows 1.7.60：X 公开接口间歇性 TLS 连接中断

报告链接：`https://x.com/lacroix0704/status/2124444023373074488`。错误发生于 X 官方公开媒体接口 `cdn.syndication.twimg.com/tweet-result` 的 HTTPS 连接，表现为 `SSLEOFError / UNEXPECTED_EOF_WHILE_READING`。

## 调查

本机应用没有显式配置代理，系统 HTTP/HTTPS 代理指向本机 7890 端口。初次并行探测中，直连、系统代理、显式 HTTP 代理及 SOCKS5 代理均返回 HTTP 200 和相同帖子 ID。随后单独请求实际复现了 SSL EOF，证实连接故障具有间歇性；不能仅凭这个异常判断 Cookie 失效，也不能认定某个代理节点始终正常。

成功响应的目标帖子包含 **5 项 photo 媒体，没有视频**。统一解析应将视频解析的无视频结果交给图文解析，而不是将帖子报告成视频下载成功。

## 修改

`windows/engine/media_resolver.py` 中，公开接口原先只在收到空 JSON、缺失帖子 ID 时进入第二次请求。SSL EOF 会直接抛出，从而跳过重试。

现在同一官方接口最多请求两次。首个瞬时 SSL EOF、连接重置或超时等待 400ms 后重试一次；持续失败返回结构化 `NETWORK_ERROR` 和安全提示，不输出原始异常 URL 或参数。

证书验证失败、401、403、404、429 均不会被这项重试绕过；没有关闭 TLS 校验，没有使用第三方镜像，没有给公开接口附带账号 Cookie。原有帖子 ID 校验继续生效。

重试无法修复持续断网或持续异常的代理节点；这类情况下仍需检查到该域名的网络连接。1.7.60 继续保留 1.7.59 的登录会话修复。

## 验证

- 新增回归测试在修复前得到 2 项失败：SSL EOF 不会重试，以及连续失败不能生成预期安全错误。
- 修复后完整后端测试：119 项通过，5 条既有 Pydantic 弃用警告。记录：`.local/x-tls-full-tests.log`。
- 原始分享链接经过统一解析，返回 `kind=images`、目标帖子 ID 和 5 项图片资源，实测约 4.88 秒。记录：`.local/x-tls-live-result.json`。
- 再次通过统一解析和既有下载引擎下载该帖 5 张原图，生成 701,777 字节 ZIP；归档 CRC 和全部图片解码校验通过。记录：`.local/x-tls-download-result.json`。这是实际网络下载结果，不是模拟返回。

Web 和桌面的实际便携 EXE 均已在隔离数据目录启动，并通过 HTTP 调用同一分享链接，返回目标帖子 ID、`kind=images` 和 5 张图片。记录分别为 `.local/x-1760-web-exe.json`、`.local/x-1760-desktop-exe.json`。桌面解析检查完成后，测试脚本因 WebView2 占用缓存文件而在临时目录清理阶段退出非零；后续关闭测试子进程和清理该目录的命令被自动审批拒绝，未给出具体理由，因此保留了该测试缓存，没有将清理报告为通过。

Windows Web、桌面和 WinUI 产物位于 `windows/dist/1.7.60/`，发行 ZIP 位于 `windows/release/`。Android、iOS 安装产物未在本次重新构建。

## 本机运行状态

旧服务没有被本次操作停止，原安装目录也没有被替换。随后检测到旧服务已经退出、58000 端口空闲，直接从独立的 `windows/dist/1.7.60/OmniDownloader_Web/` 启动了新版。已核实端口 58000 返回 1.7.60，并再次通过实际接口解析本帖得到 5 张图片。运行记录为 `.local/windows-x-1760-active.json`、`.local/x-1760-active-result.json`。

当前网页刷新后即可使用新版。旧目录和旧启动快捷方式没有被改写，后续启动请使用 1.7.60 目录中的程序。其他机器升级时应先正常退出旧版托盘，再完整解压并启动新包。
