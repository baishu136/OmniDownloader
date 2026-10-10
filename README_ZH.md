<div align="center">

# OmniDownloader (全能音视频下载器)

<p>
  <a href="README.md">English</a> •
  <a href="README_ZH.md">简体中文</a> •
  <a href="README_ZH_TW.md">繁體中文</a> •
  <a href="README_JA.md">日本語</a>
</p>

[![Release](https://img.shields.io/github/v/release/baishu136/OmniDownloader?color=blue&label=Release)](https://github.com/baishu136/OmniDownloader/releases)
[![Android](https://img.shields.io/badge/Android-v1.4.8-green.svg)](https://developer.android.com)
[![iOS](https://img.shields.io/badge/iOS-SwiftUI%20Native-orange.svg)](OmniDownloader-iOS)
[![Windows](https://img.shields.io/badge/Windows-Desktop%20%26%20Web-blue.svg)](windows)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)

**OmniDownloader** 是一套现代化全平台多媒体音视频下载矩阵（涵盖 **Android 原生、Apple iOS 原生、Windows 桌面独立版、Web 网页纯后台服务版**）。支持 **哔哩哔哩 (4K/1080P60/DASH分离流/合集聚合)、抖音、快手、小红书、TikTok、YouTube、X (Twitter)** 等各大主流平台的原画无水印直连极速提取。

</div>

---

## 🌟 全平台产品形态一览

| 平台形态 | 适用设备 | 核心技术栈 | 获取与安装方式 |
| :--- | :--- | :--- | :--- |
| **🤖 Android 原生版** | Android 手机 / 平板 / 车机 / 电视 (Android 8.0+) | Kotlin + Jetpack Compose + FFmpegKit + Aria2c | [下载最新 APK (v1.4.8)](OmniDownloader_LATEST.apk) 或 Releases 页面 |
| **🍎 iOS 原生版** | iPhone / iPad (iOS 15.0+) | Swift 5.9 + SwiftUI + AVFoundation 原生硬件混流 + Photos 相簿 | 源码工程在 [`OmniDownloader-iOS`](OmniDownloader-iOS)，支持 GitHub Actions 免费打包 IPA 并通过 TrollStore 巨魔或自签侧载 |
| **💻 Windows 桌面版** | Windows 10 / 11 (64位) | Python 3 + FastAPI + Edge WebView2 原生独立窗口 + 内置 FFmpeg | 下载 [`windows/release/OmniDownloader_桌面独立版.zip`](windows/release/)，免环境解压即用，附带多国语言说明 |
| **🌐 Web 网页纯后台版** | Windows 电脑，支持局域网内任意手机/平板浏览器访问 | 轻量托盘守护进程 + 本地 Web 服务 + 内置 FFmpeg | 下载 [`windows/release/OmniDownloader_网页纯后台版.zip`](windows/release/)，静默常驻托盘，支持浏览器原生接管下载 |

---

## 🚀 核心特性

### 1. 🌐 主流音视频平台原生全支持
- **哔哩哔哩 (Bilibili)**：
  - 支持单视频、多 P 分集与番剧/电视剧 UGC 合集批量识别；
  - 原生 DASH 双流解耦与自适应流优选算法（优先 4K/1080P60 极清画质与兼容性最佳的 AVC 编码）；
  - 安全截取持久化 `SESSDATA` 凭证，轻松解锁未登录游客 480P 限制。
- **国内短视频去水印直连**：
  - **抖音 (Douyin)**：支持分享口令与短链解析，自动重定向嗅探原画无水印直链；
  - **快手 (Kuaishou)**：短视频直链免水印嗅探；
  - **小红书 (Xiaohongshu)**：图文笔记及超清视频极速抓取。
- **国际主流媒体全方位覆盖**：
  - **YouTube**：支持 4K/2K/1080P 超高清流与 Shorts 短视频；
  - **TikTok**：免登录绕过 WAF，提取 1080P 原生无水印直链；
  - **X (Twitter) / Instagram / Facebook / Pinterest**：完整通用媒体解析引擎。

### 2. 🎬 丰富的导出与转码模式
- **音画合流 (MP4)**：自由挑选目标清晰度（4K、2K、1080P、720P），毫秒级无损合并音视频轨道。
- **仅纯视频画面**：自动剥离音轨，生成无声纯净视频，专为视频混剪与鬼畜二创设计。
- **提取高保真音频**：提取原轨并支持转码为 **MP3 (最高 320kbps)**、**M4A**、**FLAC 无损**、**OPUS**。
- **动态图 (GIF)**：基于双通道调色板优化算法，生成高清晰度免播放器预览动图。
- **保存高清封面**：一键保存视频原始超清封面图并同步至系统相册。

### 3. 📦 智能合集聚合与分集抽屉设计
- **下载页聚合卡片**：下载多视频或合集时，不逐一堆叠各个视频，而是显示**加权整体总进度条 + 当前子集进度 + 平滑展开/收起分集抽屉**，页面清爽直观。
- **吸底操作栏 (Sticky Footer)**：格式与合集下载弹窗采用规范的“可滚动主体 + 吸底常驻操作栏”，无论分集列表多长，操作按钮始终常驻底部。

### 4. 🌍 全链路多国语言与国际化
- 网页版、桌面客户端及压缩包内均内置 **简体中文、繁體中文、English、日本語** 4 种语言的完整使用指南；
- 随包附带排版美观的离线 HTML 手册与便携文本说明。

---

## 📲 快速下载安装

### Android 安装包
- 📦 **[`OmniDownloader_LATEST.apk`](OmniDownloader_LATEST.apk)**（或最新版 [`OmniDownloader-v1.4.8-debug.apk`](OmniDownloader-v1.4.8-debug.apk)）：直接安装至安卓手机。
- 📦 **[`OmniDownloader_Source_LATEST.zip`](OmniDownloader_Source_LATEST.zip)**：完整工程源码归档。

### iOS 原生工程与打包
- 源码工程位于 [`OmniDownloader-iOS/`](OmniDownloader-iOS/)；
- 配套自动化构建：推送到 GitHub 后可在 Actions 免费自动编译打包生成 `OmniDownloader.ipa`；
- 支持 **TrollStore（巨魔商店 - 永久保活）**、**AltStore / Sideloadly** 免费自签安装。详情参见 [iOS 侧载安装指南](OmniDownloader-iOS/README_IOS.md)。

### Windows 桌面独立版 & 网页纯后台版
- 💻 **桌面独立版**：下载解压 [`windows/release/OmniDownloader_桌面独立版.zip`](windows/release/)，双击 `OmniDownloader_Desktop.exe` 直接运行；
- 🌐 **网页纯后台版**：下载解压 [`windows/release/OmniDownloader_网页纯后台版.zip`](windows/release/)，双击 `OmniDownloader_Web.exe` 启动后台服务并在浏览器秒开使用。

---

## 📝 最近更新日志 (v1.4.8)

- **[NEW] 🐱 全面集成“嗅探猫”(Cat Catch) 专业多媒体嗅探体系**：
  - **深度网络监听注入**：内置浏览器全面注入 Fetch Hook、XHR Hook 与 HTMLMediaElement 监听钩子，自动捕获页面异步调用的所有视频、音频、分段 M3U8 流与直链 CDN；
  - **多媒体流资源管理抽屉**：彻底告别单一覆盖问题，顶部导航栏与底部常驻“🐱 嗅探猫”资源徽章与胶囊，轻点即可展开全功能资源管理器面板；
  - **专业嗅探工具箱**：支持【🔍 深度搜索】（主动扫描 DOM 隐藏节点、内嵌脚本变量与直链正则表达式）、【📋 复制全部直链】、【🗑️ 清空列表】、各流彩标清晰分类（MP4/M3U8/音频/WebM/FLV）以及单项一键入队下载与复制；
  - **Android 端与 iOS 原生端全面对齐**：双端排查浏览器均已同步上线嗅探猫完整体系。
- **[NEW] 备用中转解析站体验与架构重构**：
  - 添加入口规范迁移至【设置】中心集中管理，主页未添加中转站时完全移除空卡片占位；
  - 添加中转站时【网址输入框】置顶，支持 700ms 防抖自动异步拉取网页 `<title>` 并智能回填网站名称；
  - 恢复旧版“就地静默解析直接下载”主通道，同时保留“窗口排查（浏览器）”作为处理人机验证的逃生通道；
  - 针对 `greenvideo.cc` 等 SPA 架构站点实现 URL 参数原生注入与 Pinia 状态树自动化触发。
- **[SEC & FIX] 网络嗅探与下载引擎深度防御**：
  - **彻底修复 HTTP 405 (Method Not Allowed) 报错**：严格排除内部 API 接口路由，严禁向仅支持 POST 的接口发 GET 下载请求；
  - **彻底修复 MT 管理器等播放器报 source error 无法播放问题**：增加二进制 Magic Bytes 文件头核验，杜绝将 HTML/JSON 保存为 MP4；支持 HLS/M3U8 自动检测并调度 FFmpeg 高速无损封装为 MP4；支持 JWT Base64 直链深度解包。
- **[PERF] 列表渲染与滑动 120fps/60fps 满帧性能治理**：
  - `DownloadTask` 全面重构为完全不可变 Stable 数据类，达成 100% 重组跳过（Recomposition Skip）；
  - 任务下载进度高频（250ms）刷新独立抽离为微组件隔离，杜绝整屏卡片连带重绘；
  - 主页重构为扁平化原生 `LazyColumn`，中转卡片单项测量由 15ms 极致精简至 0.2ms；移除非标准负坐标伪保活，完美契合系统 LTPO 动态高刷新率调度。
- **[ALIGN] Windows 端与 iOS 端全面对齐**：
  - **Windows 客户端**：后端新增 `/api/relay-sites/fetch-title` 异步流式标题探测路由；前端设置页集中管理中转站与防抖自动抓取标题；同步引入 JWT 直链解密与防 405 拦截；修复网页端按钮点击无响应缺陷。
  - **iOS 原生客户端**：`CookieStore` 支持中转站持久化，`UrlSniffer` 扩展异步流式标题提取与媒体格式判定，设置页与主页对齐中转站管理与原生嗅探弹窗。

---

## 📝 历史更新日志 (v1.4.7)

- **[NEW] 全新发布 Apple iOS 原生版**：SwiftUI + 纯原生 AVFoundation 硬件加速音视频混流，自动存入系统照片相簿，支持后台持久下载与 Actions 免费云端打包。
- **[NEW] 网页版与桌面 EXE 版多国语言指南**：界面新增多语言使用说明模态框，打包压缩包内集成中/繁/英/日 4 国语言离线使用手册。
- **[OPTIMIZE] 合集下载页面架构重构**：多视频下载时合并展示加权总进度条与子进度，支持折叠展开分集抽屉。
- **[OPTIMIZE] B站清晰度优选算法**：精选候选流优先匹配 4K/1080P60 及高兼容 AVC 编码，支持平滑降级。
- **[FIX] 修复合集下载弹窗在部分场景下按钮缺失问题**：重构为“可滚动主体 + 吸底常驻操作栏 (Sticky Footer)”规范。
- **[FIX] 修复下载页在多次翻页后动画卡顿与渲染开销问题**。

---

## 📄 授权与免责声明
本项目仅供编程学习与个人个人影音离线归档使用，解析下载音视频之著作权归各平台及原作者所有。遵循 MIT 开源许可协议。
