<div align="center">

# OmniDownloader (全能影音下載器)

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

**OmniDownloader** 是一套現代化全平台多媒體影音下載矩陣（涵蓋 **Android 原生、Apple iOS 原生、Windows 桌面獨立版、Web 網頁純後台服務版**）。支援 **Bilibili (4K/1080P60/DASH分離流/合輯聚合)、抖音、快手、小紅書、TikTok、YouTube、X (Twitter)** 等各大主流平台的原畫無浮水印極速下載。

</div>

---

## 🌟 全平台產品型態一覽

| 平台型態 | 適用裝置 | 核心技術棧 | 取得與安裝方式 |
| :--- | :--- | :--- | :--- |
| **🤖 Android 原生版** | Android 手機 / 平板 / 電視盒 (Android 8.0+) | Kotlin + Jetpack Compose + FFmpegKit + Aria2c | [下載最新 APK (v1.4.8)](OmniDownloader_LATEST.apk) 或 Releases 頁面 |
| **🍎 iOS 原生版** | iPhone / iPad (iOS 15.0+) | Swift 5.9 + SwiftUI + AVFoundation 原生硬體混流 + Photos 相簿 | 原始碼工程位於 [`OmniDownloader-iOS`](OmniDownloader-iOS)，支援 GitHub Actions 免費打包 IPA 並透過 TrollStore 巨魔或自簽側載 |
| **💻 Windows 桌面版** | Windows 10 / 11 (64位元) | Python 3 + FastAPI + Edge WebView2 原生獨立視窗 + 內建 FFmpeg | 下載 [`windows/release/OmniDownloader_桌面独立版.zip`](windows/release/)，免環境解壓即用，附帶多國語言說明 |
| **🌐 Web 網頁純後台版** | Windows 電腦，支援區域網路內任意手機/平板瀏覽器存取 | 輕量系統列托盤常駐 + 本地 Web 服務 + 內建 FFmpeg | 下載 [`windows/release/OmniDownloader_网页纯后台版.zip`](windows/release/)，靜默常駐托盤，支援瀏覽器原生接管下載 |

---

## 🚀 核心特性

1. **主流影音平台原生全支援**：
   - **Bilibili**：支援單集、多P分集與番劇/電視劇合輯；原生 DASH 雙流解耦，支援 4K/1080P60 畫質與 `SESSDATA` 授權憑證。
   - **短影音免浮水印直連**：抖音、快手、小紅書短鏈嗅探與自動重新導向，提取官方 CDN 原畫無浮水印直鏈。
   - **國際主流媒體全覆蓋**：YouTube (4K/Shorts)、TikTok、X (Twitter)、Instagram 等。
2. **多樣化導出模式**：
   - 音畫合流 (MP4)、純視訊畫面、高保真音訊提取 (MP3 / M4A / FLAC 無損)、動圖 (GIF)、高清封面圖。
3. **智慧合輯聚合卡片與分集抽屜**：
   - 下載管理頁面支援加權總進度條 + 子任務進度，點擊展開抽屜即可檢視各分集明細。
4. **多國語言國際化支援**：
   - 包含繁體中文、簡體中文、英文與日文，包內皆附完整說明。

---

## 📲 快速下載安裝

- **Android 最新版**：[`OmniDownloader_LATEST.apk`](OmniDownloader_LATEST.apk)（或 [`OmniDownloader-v1.4.8-debug.apk`](OmniDownloader-v1.4.8-debug.apk)）
- **iOS 側載指南**：參考 [`OmniDownloader-iOS/README_IOS.md`](OmniDownloader-iOS/README_IOS.md)
- **Windows 發行包**：位於 [`windows/release/`](windows/release/)

---

## 📝 最近更新日誌 (v1.4.8)

- **[NEW] 備用中轉解析站體驗與架構重構**：
  - 添加入口規範遷移至【設定】中心集中管理，首頁未配置中轉站時完全隱藏空白佔位卡片；
  - 添加入口將【網址輸入框】置頂，支援 700ms 防抖自動非同步檢索網頁 `<title>` 並智慧填入名稱；
  - 恢復舊版「就地靜默秒級直接解析下載」，同時保留「視窗排查（瀏覽器）」作為處理滑塊人機驗證的逃生通道；
  - 針對 `greenvideo.cc` 等 SPA 架構網站實現 URL 參數原生注入與 Pinia 狀態樹自動化觸發。
- **[SEC & FIX] 網路嗅探與下載引擎深度防禦**：
  - **徹底修復 HTTP 405 (Method Not Allowed) 報錯**：嚴格排除內部 API 介面路由，嚴禁向僅支援 POST 的介面發送 GET 下載請求；
  - **徹底修復 MT 管理器等播放器報 source error 無法播放問題**：增加二進位 Magic Bytes 檔案頭核驗，杜絕將 HTML/JSON 儲存為 MP4；支援 HLS/M3U8 自動偵測並調度 FFmpeg 高速無損封裝；支援 JWT Base64 直鏈深度解包。
- **[PERF] 列表渲染與滑動 120fps/60fps 滿幀性能治理**：
  - `DownloadTask` 全面重構為不可變 Stable 資料類別，達成 100% 重組跳過（Recomposition Skip）；
  - 任務下載進度高頻（250ms）更新獨立抽離為局部微組件，徹底杜絕全螢幕任務卡片連帶重繪；
  - 首頁重構為扁平化原生 `LazyColumn`，中轉卡片單項測量精簡至 0.2ms；移除負座標偽保活，完美契合系統 LTPO 動態高更新率排程。
- **[ALIGN] Windows 端與 iOS 端全面對齊**：
  - **Windows 客戶端**：後端新增 `/api/relay-sites/fetch-title` 串流標題探測路由；前端設定頁集中管理中轉站與防抖自動抓取標題；同步引入 JWT 直鏈解密與防 405 攔截。
  - **iOS 原生客戶端**：`CookieStore` 支援中轉站持久化，`UrlSniffer` 擴充非同步串流標題提取與媒體格式判定，設定頁與首頁對齊中轉站管理與原生嗅探彈窗。

---

## 📄 授權協議
本專案遵循 MIT 開源許可協議。
