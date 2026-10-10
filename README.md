<div align="center">

# OmniDownloader

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

**OmniDownloader** is a modern, cross-platform multimedia downloader matrix (**Android native, Apple iOS native, Windows Desktop standalone app, and Web background service**). It supports ultra-fast extraction and direct downloading of original-quality videos without watermarks from major platforms including **Bilibili (4K/1080P60 DASH muxing & playlists), Douyin, TikTok, Kuaishou, Xiaohongshu, YouTube, and X (Twitter)**.

</div>

---

## 🌟 Cross-Platform Overview

| Platform | Target Devices | Core Tech Stack | Installation & Download |
| :--- | :--- | :--- | :--- |
| **🤖 Android Native** | Android Phones / Tablets / TV (Android 8.0+) | Kotlin + Jetpack Compose + FFmpegKit + Aria2c | [Download Latest APK (v1.4.8)](OmniDownloader_LATEST.apk) or via Releases |
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

- **Android Latest APK**: [`OmniDownloader_LATEST.apk`](OmniDownloader_LATEST.apk) (or [`OmniDownloader-v1.4.8-debug.apk`](OmniDownloader-v1.4.8-debug.apk))
- **iOS Sideload Guide**: See [`OmniDownloader-iOS/README_IOS.md`](OmniDownloader-iOS/README_IOS.md)
- **Windows Portable ZIPs**: Located in [`windows/release/`](windows/release/)

---

## 📝 Recent Changelog (v1.4.8)

- **[NEW] Relay Sites Architecture Revamp**:
  - Moved relay site management into the Settings tab; empty placeholder cards are cleanly hidden from the Home tab when no sites are configured.
  - Prioritized URL input with 700ms debounce asynchronous web title detection to auto-populate site names via low-bandwidth stream truncation.
  - Restored silent in-place parsing for one-click downloading, while preserving the built-in browser window for manual CAPTCHA verification.
  - Enhanced SPA automation for `greenvideo.cc` with native URL query injection and Pinia state tree triggering.
- **[SEC & FIX] Network Sniffing & Direct Download Hardening**:
  - **Fixed HTTP 405 (Method Not Allowed)**: Strictly excludes internal POST API endpoints from media interceptors, restricting direct capture to GET media streams.
  - **Fixed Media Player Source Error (MT Manager)**: Added ISO BMFF / Magic Bytes binary header validation to prevent saving HTML/JSON errors as media; integrated automated FFmpeg remuxing for HLS/M3U8 streams; added JWT Base64 stream unpacking.
- **[PERF] 120fps / 60fps Full-Framerate Fluidity Optimization**:
  - Completely refactored `DownloadTask` into an immutable Stable data class, achieving 100% recomposition skipping.
  - Extracted 250ms progress and speed updates into isolated micro-composables.
  - Flattened `HomeScreen` to native `LazyColumn`, optimizing card measurement from 15ms to 0.2ms, and replaced off-screen tab keeping with `rememberSaveableStateHolder` to eliminate LTPO dynamic refresh rate conflicts.
- **[ALIGN] Full Cross-Platform Alignment**:
  - **Windows Desktop & Web**: Added `/api/relay-sites/fetch-title` endpoint, centralized settings UI, debounce title fetching, and direct stream decryption.
  - **iOS Native**: Added `RelaySite` UserDefaults persistence in `CookieStore`, async stream title extraction in `UrlSniffer`, and synchronized Settings/Home views.

---

## 📄 License
This project is licensed under the MIT License.
