<div align="center">

# OmniDownloader

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

**OmniDownloader** is a modern, cross-platform multimedia downloader matrix (**Android native, Apple iOS native, Windows Desktop standalone app, and Web background service**). It supports ultra-fast extraction and direct downloading of original-quality videos without watermarks from major platforms including **Bilibili (4K/1080P60 DASH muxing & playlists), Douyin, TikTok, Kuaishou, Xiaohongshu, YouTube, and X (Twitter)**.

</div>

---

## 🌟 Cross-Platform Overview

| Platform | Target Devices | Core Tech Stack | Installation & Download |
| :--- | :--- | :--- | :--- |
| **🤖 Android Native** | Android Phones / Tablets / TV (Android 8.0+) | Kotlin + Jetpack Compose + FFmpegKit + Aria2c | [Download Latest APK (v1.4.7)](OmniDownloader_LATEST.apk) or via Releases |
| **🍎 iOS Native** | iPhone / iPad (iOS 15.0+) | Swift 5.9 + SwiftUI + AVFoundation hardware-accelerated muxing | Source code in [`OmniDownloader-iOS`](OmniDownloader-iOS); Free cloud build via GitHub Actions for TrollStore / AltStore sideloading |
| **💻 Windows Desktop** | Windows 10 / 11 (64-bit) | Python 3 + FastAPI + Edge WebView2 standalone window + Built-in FFmpeg | Download [`windows/release/OmniDownloader_桌面独立版.zip`](windows/release/); Standalone portable app with multilingual guides |
| **🌐 Web Background** | Windows PC, accessible by any browser across LAN | Lightweight system tray daemon + Local web service + Built-in FFmpeg | Download [`windows/release/OmniDownloader_网页纯后台版.zip`](windows/release/); Silent tray app with browser download takeover |

---

## 🚀 Key Features

1. **Native Platform Support**:
   - **Bilibili**: Full DASH stream muxing up to 4K / 1080P60, playlist batch downloading, SESSDATA authentication.
   - **Short Video No-Watermark Extraction**: Direct official CDN streams from Douyin, Kuaishou, Xiaohongshu, and TikTok.
   - **Global Platforms**: Full coverage of YouTube (4K/Shorts), X (Twitter), Instagram, and Facebook.
2. **Flexible Export Modes**:
   - Video + Audio (MP4), Video Only (Mute for remixing), High-fidelity Audio extraction (MP3 / M4A / FLAC), Animated GIF, and HD Cover art.
3. **Smart Collection Aggregation**:
   - Playlists and multi-video collections display with a weighted progress bar and expandable episode drawers.
4. **Multilingual Support**:
   - Complete guides included in English, Simplified Chinese, Traditional Chinese, and Japanese.

---

## 📲 Quick Downloads

- **Android Latest APK**: [`OmniDownloader_LATEST.apk`](OmniDownloader_LATEST.apk)
- **iOS Sideload Guide**: See [`OmniDownloader-iOS/README_IOS.md`](OmniDownloader-iOS/README_IOS.md)
- **Windows Portable ZIPs**: Located in [`windows/release/`](windows/release/)
