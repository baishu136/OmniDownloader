<div align="center">

# OmniDownloader (全能影音下載器)

<p>
  <a href="README.md">English</a> •
  <a href="README_ZH.md">简体中文</a> •
  <a href="README_ZH_TW.md">繁體中文</a> •
  <a href="README_JA.md">日本語</a>
</p>

[![Release](https://img.shields.io/github/v/release/baishu136/OmniDownloader?color=blue&label=Release)](https://github.com/baishu136/OmniDownloader/releases)
[![Android](https://img.shields.io/badge/Android-v1.4.7-green.svg)](https://developer.android.com)
[![iOS](https://img.shields.io/badge/iOS-SwiftUI%20Native-orange.svg)](OmniDownloader-iOS)
[![Windows](https://img.shields.io/badge/Windows-Desktop%20%26%20Web-blue.svg)](windows)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)

**OmniDownloader** 是一套現代化全平台多媒體影音下載矩陣（涵蓋 **Android 原生、Apple iOS 原生、Windows 桌面獨立版、Web 網頁純後台服務版**）。支援 **Bilibili (4K/1080P60/DASH分離流/合輯聚合)、抖音、快手、小紅書、TikTok、YouTube、X (Twitter)** 等各大主流平台的原畫無浮水印極速下載。

</div>

---

## 🌟 全平台產品型態一覽

| 平台型態 | 適用裝置 | 核心技術棧 | 取得與安裝方式 |
| :--- | :--- | :--- | :--- |
| **🤖 Android 原生版** | Android 手機 / 平板 / 電視盒 (Android 8.0+) | Kotlin + Jetpack Compose + FFmpegKit + Aria2c | [下載最新 APK (v1.4.7)](OmniDownloader_LATEST.apk) 或 Releases 頁面 |
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

- **Android 最新版**：[`OmniDownloader_LATEST.apk`](OmniDownloader_LATEST.apk)
- **iOS 側載指南**：參考 [`OmniDownloader-iOS/README_IOS.md`](OmniDownloader-iOS/README_IOS.md)
- **Windows 發行包**：位於 [`windows/release/`](windows/release/)
