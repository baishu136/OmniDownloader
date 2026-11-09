# OmniDownloader Windows 1.7.20

Windows 10/11 x64 客户端，提供桌面 EXE 和本机 Web EXE。两种形态共用下载核心和用户数据。

## 使用

完整解压 `OmniDownloader_Desktop-1.7.20.zip` 或 `OmniDownloader_Web-1.7.20.zip`，在解压目录双击同名 EXE；请保留 `_internal` 目录。运行不需要安装 Python。桌面版需要 Edge WebView2；抖音自动网页补全需要本机 Microsoft Edge。

Web 版默认仅监听 http://127.0.0.1:58000，启动后常驻托盘。托盘可打开网页或退出后台服务。用户数据在 `%LOCALAPPDATA%/OmniDownloader`，已下载成品保存在设置所选目录。自启动为可选项。

## 界面和功能

- 首页、图文、任务、设置四页；单行输入、平台标签、剪贴板和解析/下载进度。
- 七类设置和子页：外观与语言、下载与存储、网络代理、中转站、账号、下载引擎、关于；账号说明为“Cookie 与登录管理”。
- 音画合流、纯画面、MP3/M4A/FLAC/OPUS、GIF、封面、合集与 X 多视频。
- 图文原图选择、预览、ZIP；Pixiv Ugoira 原始帧、MP4/GIF。
- 会话按平台导入/清除/禁用，使用 Windows 用户 DPAPI 保存。接口不返回 Cookie 值。媒体历史也保存在 DPAPI 加密文件中。
- 最高规格按实际可访问的源排序，提供尺寸、帧率、HDR、编码与严格画质策略。下载后核对成品；导出失败保留已验证的成品，重试无需重新下载。
- 抖音使用原生读取优先，必要时以独立临时 Edge 配置补全同一作品的可用源。遇到登录或验证码时保留已有可用源并说明限制；不会自动处理验证码。
- 页面样式、图标、脚本均随包提供，启动 UI 不依赖样式 CDN。网页服务仍需要网络访问媒体平台。

## 限制

最高源画质不等于上传原片。平台登录、会员/充电权益、内容状态、地区和 DRM 限制不会被绕过。部分动态页面（如小黑盒）需要桌面辅助浏览器播放并确认目标媒体；纯 Web 版没有桌面 WebView 的交互捕获能力。组件随应用发布更新，不在设置中承诺独立二进制热更新。

## 开发与打包

Python 3.12，依赖固定在 `requirements-lock.txt`。开发服务器为 `python -m uvicorn omni_server:app --app-dir windows --host 127.0.0.1 --port 58000`。

```powershell
python -m pytest windows/tests -q
python windows/tools/prepare_web_assets.py
python windows/build_release.py --mode all
```

样式资产构建需要 Node/npm；最终 EXE 不依赖这些开发工具。`runtime-lock.json` 固定 Deno 2.9.8 和 FFmpeg 7.1 的官方归档及二进制 SHA-256，构建前校验并在缺失时恢复。不同版本不会被静默混入成品。新输出在 `dist/1.7.20/`，不会停止或覆盖旧服务；发布 ZIP 在 `release/`。

`OMNI_STATE_DIR` 可为测试指定独立数据目录，`OMNI_PORT` 可指定本机测试端口。测试请使用独立状态，避免改动真实任务。

## 本机升级和恢复

本次 58000 服务已更新，原安装目录和用户状态备份在仓库 `.local/backups/` 及 `windows/dist/OmniDownloader_Web.backup-1719-*`。旧版安装目录内的历史已迁移至用户数据目录。配置、Cookie、历史与下载目录不随重新打包覆盖。

恢复旧程序时先退出托盘服务，保留当前用户数据；将原安装目录恢复到原路径，再启动原 EXE。若需要回退数据，应同时使用对应的用户数据备份，避免旧版本误读新格式。备份可能含登录状态，请只保存在本机。
