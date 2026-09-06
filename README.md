<div align="center">

# OmniDownloader

**A Modern, Native Android Media Downloader Powered by Jetpack Compose, yt-dlp & FFmpeg**

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

OmniDownloader is a state-of-the-art native media downloading application designed specifically for Android. Built entirely with **Jetpack Compose (Material Design 3)**, it integrates the industrial-grade **yt-dlp**, **FFmpeg**, and **Aria2c** native engines to deliver fast, reliable, and versatile audio/video downloading across 10+ major social and streaming platforms.

<br />

<img src="docs/images/screenshot.png" alt="OmniDownloader Main Interface" width="320" style="border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.15);" />

</div>

---

## 🌟 Key Features

### 1. 🌐 Comprehensive 10+ Platform Support
- **Global Platforms**:
  - **YouTube**: Regular videos, Shorts, and up to 4K/2K/1080P UHD video streams.
  - **TikTok**: High-speed watermark-free video extraction.
  - **X (Twitter)**: Full resolution tweet videos and GIF conversions.
  - **Instagram**: Reels and post video content.
  - **Facebook**: Public posts, reels, and video clips.
  - **Pinterest**: HD video inspiration pins.
- **Top Asian Platforms**:
  - **Bilibili**: Full support for single episodes, multi-part episodes (P1/P2/...), collections, and anime. Automatic expansion of mobile `b23.tv` short links and Dash stream merging.
  - **Douyin**, **Kuaishou**, **Xiaohongshu (RED)**: Automatic URL sniffing from shared text clips and clean watermark-free video parsing.

### 2. 🎬 Versatile Download Modes
| Mode | Description | Under the Hood |
| :--- | :--- | :--- |
| **Merged Video** | Choose desired resolution (4K / 1080P / 720P / 480P) with estimated file size | Automatically picks best video and audio streams and merges them into standard MP4 using FFmpeg |
| **Video Only** | Downloads muted video track without audio | Injects `-an` parameter to save pure video b-roll for creators |
| **Audio Extraction** | Extracts and converts soundtrack to high-quality audio files | Supports export to **MP3**, **M4A (AAC)**, **FLAC (Lossless)**, and **OPUS** with automatic metadata tagging |
| **Animated GIF** | Converts highlight clips into lightweight animated GIFs | Generates high-fidelity GIFs using customized FFmpeg color palettes |
| **Save Cover Thumbnail** | Compact switch toggle to download HD cover art directly to gallery | Downloads high-resolution thumbnail and immediately registers it in the Android media library |

### 3. 📦 Smart Playlist & Collection Handling
- **Accidental Mass-Download Prevention**: When using "Paste Clipboard", if the link points to a playlist, series, or multi-part collection, the app **automatically pauses direct download** and prompts you with a bottom format selection sheet.
- **Flexible Batch Selection**:
  - Preview each episode in single mode or switch to collection mode;
  - Select individual episodes with checkboxes, or use one-tap "Select All" / "Invert Selection" to batch download only what you need.

### 4. 🚀 Foreground Service & System Integration
- **Foreground Service**: Reliable background downloads with lockscreen and notification shade indicators showing download percentage, transfer speed, and estimated time remaining (ETA).
- **Internationalization (i18n)**: Supports English, Simplified Chinese, Traditional Chinese, and Japanese. Defaults to the device's system language with in-app manual override.
- **Immediate Gallery Sync**: Fully complies with Android Scoped Storage. Downloaded files are immediately indexed into the Android MediaStore.
- **Proxy Configuration**: Supports HTTP and SOCKS5 proxies for bypassing network restrictions.
- **Online Engine Updates**: Hot-update the underlying yt-dlp core directly from Settings without needing to reinstall the app.

---

## 📲 Download & Installation

Grab the latest pre-compiled build from the [GitHub Releases](https://github.com/baishu136/OmniDownloader/releases):

- 📦 **`OmniDownloader-v1.2.7-debug.apk`**: Pre-built Android package (Requires Android 8.0+, arm64-v8a).
- 📦 **`OmniDownloader-v1.2.7-Source.zip`**: Clean standalone source code archive.

---

## 🛠️ Tech Stack

- **Language**: Kotlin 1.9.23
- **Build System**: Gradle 8.5 + AGP 8.3.2
- **UI Framework**: Jetpack Compose + Material Design 3
- **Architecture**: MVVM (Model-View-ViewModel) + Clean Architecture
- **Concurrency**: Kotlin Coroutines + StateFlow
- **Core Download Engines**:
  - `io.github.junkfood02.youtubedl-android:library:0.18.1` (yt-dlp)
  - `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` (FFmpeg)
  - `io.github.junkfood02.youtubedl-android:aria2c:0.18.1` (Aria2c)
- **Networking & URL Sniffing**: Square OkHttp 4.12.0
- **Image Loading**: Coil Compose 2.6.0

---

## 💻 Building from Source

To build OmniDownloader on your local machine:

```bash
# 1. Clone the repository
git clone https://github.com/baishu136/OmniDownloader.git
cd OmniDownloader

# 2. Build on Windows PowerShell
.\gradlew.bat assembleDebug --no-daemon

# 3. Build on macOS / Linux
chmod +x ./gradlew
./gradlew assembleDebug --no-daemon
```

The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE). For educational and personal backup purposes only. Please respect all platform terms of service and applicable copyright laws.
