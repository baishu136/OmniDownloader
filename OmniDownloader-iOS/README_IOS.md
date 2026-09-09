# OmniDownloader - Apple iOS 原生版

**OmniDownloader iOS** 是专为 iPhone 与 iPad 设计的全能音视频极速下载器。采用纯正的 **Swift 5.9 + SwiftUI** 声明式现代架构与 **iOS 原生硬件加速 AVFoundation 媒体引擎**，完美还原 Android 端广受好评的全平台下载与合集聚合能力。

---

## 🌟 核心特性

1. **哔哩哔哩（B站）全画质极速直连**：
   - 官方原生 DASH 双流解耦，支持 **4K (2160p)**、**1080P60 高帧率**、**1080P 高清**以及无损音频原轨提取；
   - 智能视频流挑选引擎：按“高度降序 -> 清晰度qn降序 -> AVC高兼容编码优先 -> 码率降序”精选候选流，支持无缝平滑降级；
   - 内置安全 WebKit 官方登录入口，无感截取持久化 SESSDATA 凭证，一键免费解锁游客 480P 限制。

2. **分P视频与 UGC 合集聚合下载**：
   - 自动识别多视频、分P教学视频与 UGC 电视剧/合集；
   - 首页下载弹窗提供“下载单集 / 批量下载合集”双模式与全选/反选分集抽屉；
   - 弹窗严格遵循“可滚动主体 + 吸底常驻操作栏 (Sticky Footer)”设计规范；
   - **下载管理页合集卡片聚合**：显示加权整体进度条、当前正在下载的子分集进度，并支持点击平滑展开/收起分集抽屉明细。

3. **国内热门短视频无水印原画直连**：
   - 原生支持 **抖音**、**快手**、**小红书** 短链与长链嗅探；
   - 自动解析 302 重定向并提取官方原生 CDN 无水印最高画质直链与封面。

4. **原生硬件加速 DASH 合并（无需庞大 FFmpeg）**：
   - 创新性采用 iOS 底层 `AVMutableComposition` + `AVAssetExportSession(presetName: Passthrough)`；
   - 纯硬件加速，毫秒级无损合并视频轨道与音频轨道，且将 App 安装包体积控制在几兆之内。

5. **无缝存入系统《照片》相册**：
   - 深度集成 `Photos.framework` (`PHPhotoLibrary`)；
   - 合并完成后自动在 iPhone 相册中创建“OmniDownloader”专属相簿并将音视频存入其中，方便原生播放与分享。

6. **原生后台下载持久化**：
   - 基于 `URLSessionConfiguration.background` 与 iOS `nsurlsessiond` 系统级守护进程；
   - 退到后台、锁屏状态下下载依然稳定进行，不中断、不被系统杀进程。

---

## 📁 项目工程架构

```
OmniDownloader-iOS/
├── OmniDownloader.xcodeproj/       # Xcode 标准工程配置
│   └── project.pbxproj
├── .github/workflows/              # GitHub Actions 自动化 CI 编译流水线
│   └── ios-build.yml               # 自动打包未签名 OmniDownloader.ipa
├── README_IOS.md                   # 详细使用与侧载说明文档
└── OmniDownloader/                 # 核心 Swift 原生代码
    ├── App/
    │   ├── OmniDownloaderApp.swift # SwiftUI App 应用程序入口
    │   └── Info.plist              # 相册权限、后台模式与网络策略声明
    ├── Models/
    │   ├── Models.swift            # 核心数据模型 (DownloadTask, VideoMetadata 等)
    │   └── AppStrings.swift        # 全套简体中文国际化常量表
    ├── Engines/
    │   ├── UrlSniffer.swift        # 链接正则嗅探、重定向解析与平台识别
    │   ├── BilibiliExtractor.swift # B站全画质 DASH、分P/合集与流挑选算法
    │   └── DomesticExtractor.swift # 抖音/快手/小红书去水印提取
    ├── Media/
    │   └── AVMuxer.swift           # AVFoundation 硬件加速音视频无损混流
    ├── Services/
    │   ├── CookieStore.swift       # SESSDATA 凭证与偏好配置持久化
    │   ├── MediaSaver.swift        # 系统相册专属相簿创建与文件安全入库
    │   └── DownloadManager.swift   # 后台 URLSession 下载任务并发调度管理
    └── Views/
        ├── MainTabView.swift       # 根 Tab 栏容器
        ├── Home/
        │   ├── HomeView.swift      # 首页 (链接输入、剪贴板嗅探、平台导航)
        │   └── FormatSelectorSheet.swift # 格式与合集下载弹窗 (Sticky Footer)
        ├── Tasks/
        │   ├── TasksView.swift     # 任务管理页 (进行中/已完成，翻页缓冲)
        │   ├── TaskCard.swift      # 单任务下载卡片
        │   └── CollectionTaskCard.swift  # 合集聚合卡片 (总进度+子进度+抽屉)
        └── Settings/
            ├── SettingsView.swift  # 设置页 (账号状态、相册开关、清理缓存)
            └── BilibiliLoginSheet.swift  # WebKit 官方安全登录拦截弹窗
```

---

## 🚀 编译与运行方式

### 方案 A：在 Mac 上使用 Xcode 编译运行（针对有 Mac 设备的开发者）
1. 双击打开 `OmniDownloader.xcodeproj`；
2. 在 Xcode 顶部选择您的 iPhone 真机或 iOS 模拟器；
3. 在 `Signing & Capabilities` 中配置您的个人 Apple 开发者账号或个人免费 Apple ID；
4. 点击 **Run (⌘ + R)** 即可直接将应用安装到手机上运行。

---

### 方案 B：在 Windows 环境通过 GitHub Actions 云端打包（免 Mac 设备）
若您当前在 Windows 开发机上，无需安装 macOS 虚拟机：
1. 将当前工程推送到您的个人 GitHub 仓库；
2. 进入仓库页面的 **Actions** 标签；
3. 选择左侧的 **“构建 iOS OmniDownloader.ipa”** 工作流；
4. 点击右侧的 **“Run workflow”** 手动触发编译；
5. 构建完成后（约 2~3 分钟），在页面下方 **Artifacts** 区域即可一键下载最新编译好的 **`OmniDownloader.ipa`** 安装包！

---

## 📲 iPhone / iPad 侧载安装方法

下载生成的 `OmniDownloader.ipa` 之后，可通过以下任意一种常见方式安装至 iOS 设备：

### 1. TrollStore（巨魔商店 - 推荐，永久保活）
- **适用机型**：支持 iOS 14.0 ~ 17.0（部分版本）的巨魔设备；
- **安装步骤**：将 `.ipa` 发送至微信/QQ/隔空投送并在手机上打开，选择“使用 TrollStore 打开”，点击 Install 即可，**永不过期、无需续签**。

### 2. AltStore / Sideloadly / 牛蛙助手（个人免费 Apple ID 签名）
- **适用机型**：所有 iOS 15.0 ~ 18.x 任意机型，无需越狱；
- **安装步骤**：
  1. 电脑连接 iPhone 并打开 Sideloadly / AltStore；
  2. 拖入 `OmniDownloader.ipa`，输入个人免费 Apple ID 进行签名并安装；
  3. 手机打开“设置 -> 通用 -> VPN 与设备管理”，信任您的开发者证书即可打开使用（每 7 天在同一 Wi-Fi 下即可自动续签）。

### 3. 爱思助手自签名安装
- 电脑打开爱思助手 -> 工具箱 -> IPA 签名；
- 添加 `OmniDownloader.ipa`，使用 Apple ID 签名后，点击一键安装到手机。

---

## 📄 授权与免责声明
本项目仅供编程学习与个人个人影音离线归档使用，解析下载音视频之著作权归各平台及原作者所有。
