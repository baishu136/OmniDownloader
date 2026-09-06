<div align="center">

# OmniDownloader (全能音視頻下載器)

<p>
  <a href="README.md">English</a> •
  <a href="README_ZH.md">简体中文</a> •
  <a href="README_ZH_TW.md">繁體中文</a> •
  <a href="README_JA.md">日本語</a>
</p>

[![Release](https://img.shields.io/github/v/release/baishu136/OmniDownloader?color=blue&label=Release)](https://github.com/baishu136/OmniDownloader/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)

專為 Android 平台打造的現代化原生多媒體下載利器。基於 **Jetpack Compose (Material 3)** 構建，底層內嵌工業級 **yt-dlp**、**FFmpeg** 與 **Aria2c** 原生引擎，支援 10 大主流平台的音視頻高效解析與下載。

<br />

<img src="docs/images/screenshot.png" alt="OmniDownloader 應用主介面截圖" width="320" style="border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.15);" />

</div>

---

## 🌟 核心特性

### 1. 🌐 10+ 主流媒體平台全方位支援
- **中文社群平台深度適配**：
  - **嗶哩嗶哩 (Bilibili)**：支援常規影片、多 P 分集影片、系列合集、番劇；自動還原手機端 `b23.tv` 短網址；原生 Dash 音視頻分離串流自動合併。
  - **抖音 (Douyin)**：支援分享口令與短鏈解析，自動重新導向嗅探無浮水印影片串流。
  - **快手 (Kuaishou)**：一鍵嗅探短影音直鏈。
  - **小紅書 (Xiaohongshu)**：支援筆記影音解析。
- **國際主流媒體全面覆蓋**：
  - **YouTube**：支援普通影片、Shorts 短影片，最高支援 4K/2K/1080P 超高清串流。
  - **TikTok**：支援全球版影片無浮水印解析。
  - **X (Twitter)**：支援推文附帶的各類解析度影音。
  - **Instagram**：Reels 與貼文影片。
  - **Facebook**：公開影片與短影音。
  - **Pinterest**：高畫質靈感影片。

### 2. 🎬 靈活的下載模式選擇
| 下載模式 | 功能說明 | 技術實現 |
| :--- | :--- | :--- |
| **音畫合流** | 自由挑選目標解析度（4K / 1080P / 720P / 480P），提供檔案預估大小 | 自動挑選最佳畫質軌與最佳音軌，調用內置 FFmpeg 無縫合併為 MP4 |
| **純影片畫面** | 剝除音訊軌道，僅下載純淨畫面（適合剪輯二創素材） | 原生注入 `-an` 參數去除聲軌，免除二次消音處理 |
| **擷取音訊** | 抽取並轉碼高音質獨立音訊檔案 | 支援導出為 **MP3**、**M4A (AAC)**、**FLAC (無損)**、**OPUS**，自動注入標題元數據 |
| **動態圖 (GIF)** | 將影片精彩片段轉換為輕量動態圖 | FFmpeg 調優調色盤生成高畫質 GIF |
| **儲存高畫質封面** | 獨立可開關選項，隨影片下載或一鍵單獨儲存 | 自動下載影片高畫質縮圖並直接同步至系統相冊 |

### 3. 📦 智慧合集與多影片防誤觸機制
- **合集智慧識別**：使用「貼上剪貼簿」時，若連結為合集（如 B站多 P 影片、番劇、系列資源），**系統自動攔截直接下載**，彈出下載選項彈窗供用戶自主決定。
- **自主勾選與批次管理**：
  - 支援直接在單集模式下橫向預覽各集並指定下載；
  - 支援一鍵切換為「合集模式」，提供每集複選框、一鍵「全選」與「反選」，自主勾選所需分集進行批次下載。

### 4. 🚀 強大的後台服務與系統整合
- **前台常駐服務 (Foreground Service)**：鎖定螢幕或切換至後台時下載不中斷，通知列即時回饋傳輸百分比、下載速度與預估剩餘時間。
- **多語言國際化**：內建簡體中文、繁體中文、English、日本語 4 種語言，預設智慧跟隨系統語言並支援隨時在設定中切換。
- **公共儲存與相冊自動同步**：符合 Android 分區儲存規範，下載完成即時觸發系統媒體庫掃描，相簿與播放器即刻可見。
- **網路代理支援**：支援配置 HTTP 與 SOCKS5 代理埠，便於解析訪問外網資源。
- **線上引擎熱更新**：設定內支援一鍵線上更新底層 yt-dlp 規則引擎，平台規則變更時無需重新安裝應用程式。

---

## 📲 快速下載安裝

您可以前往 [Releases 頁面](https://github.com/baishu136/OmniDownloader/releases) 下載最新的安裝包：

- 📦 **`OmniDownloader-v1.2.7-debug.apk`**：最新 Android 安裝包（支援 Android 8.0 及更高版本，arm64-v8a 架構）。
- 📦 **`OmniDownloader-v1.2.7-Source.zip`**：完整工程純淨原始碼壓縮包。

---

## 🛠️ 技術架構

- **開發語言**：Kotlin 1.9.23
- **構建系統**：Gradle 8.5 + AGP 8.3.2
- **介面架構**：Jetpack Compose + Material Design 3
- **架構設計**：MVVM (Model-View-ViewModel) + Clean Architecture
- **異步響應**：Kotlin Coroutines + StateFlow
- **解析與音視頻引擎**：
  - `io.github.junkfood02.youtubedl-android:library:0.18.1` (yt-dlp)
  - `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` (FFmpeg)
  - `io.github.junkfood02.youtubedl-android:aria2c:0.18.1` (Aria2c)
- **網路與嗅探**：Square OkHttp 4.12.0
- **圖片加載**：Coil Compose 2.6.0

---

## 💻 原始碼編譯指南

如果您想自行編譯和二次開發：

```bash
# 1. 複製倉庫
git clone https://github.com/baishu136/OmniDownloader.git
cd OmniDownloader

# 2. Windows 環境下編譯 Debug APK
.\gradlew.bat assembleDebug --no-daemon

# 3. macOS / Linux 環境下編譯
chmod +x ./gradlew
./gradlew assembleDebug --no-daemon
```

編譯生成檔案位於：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 📄 開源許可證

本專案基於 [MIT 許可證](LICENSE) 開源。僅供個人學習、技術研究與離線備份使用，請嚴格遵守各平台服務條款與版權法規。
