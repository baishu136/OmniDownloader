<div align="center">

# OmniDownloader (万能メディアダウンローダー)

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

**OmniDownloader** は、クロスプラットフォームに対応した最新のメディアダウンローダーです（**Android ネイティブ、Apple iOS ネイティブ、Windows デスクトップ独立版、Web バックグラウンド版**）。**Bilibili (4K/1080P60/DASH分離ストリーム合成/プレイリスト一括)、TikTok、Douyin (抖音)、YouTube、X (Twitter)、小紅書、快手** などの透かしなし高画質メディアを高速抽出・保存できます。

</div>

---

## 🌟 クロスプラットフォーム概要

| プラットフォーム | 対象デバイス | コア技術 | インストールと取得方法 |
| :--- | :--- | :--- | :--- |
| **🤖 Android 版** | Android スマートフォン / タブレット / TV (Android 8.0+) | Kotlin + Jetpack Compose + FFmpegKit + Aria2c | [最新 APK (v1.4.7) をダウンロード](OmniDownloader_LATEST.apk) または Releases より |
| **🍎 iOS 版** | iPhone / iPad (iOS 15.0+) | Swift 5.9 + SwiftUI + AVFoundation ハードウェア合成 + 写真アプリ連携 | ソースコードは [`OmniDownloader-iOS`](OmniDownloader-iOS) に格納。GitHub Actions による無料 IPA ビルド & TrollStore / AltStore サイドロード対応 |
| **💻 Windows デスクトップ版** | Windows 10 / 11 (64bit) | Python 3 + FastAPI + Edge WebView2 独立ウィンドウ + 内蔵 FFmpeg | [`windows/release/OmniDownloader_桌面独立版.zip`](windows/release/) を展開するだけですぐ使えます（多言語マニュアル同梱） |
| **🌐 Web バックグラウンド版** | Windows PC（同一LAN内のスマホ・タブレットからも操作可能） | 軽量タスクトレイ常駐 + ローカルWebサーバー + 内蔵 FFmpeg | [`windows/release/OmniDownloader_网页纯后台版.zip`](windows/release/) をダウンロード。ブラウザ直接保存対応 |

---

## 🚀 主な機能と特徴

1. **主要メディアプラットフォーム完全対応**：
   - Bilibili (4K/1080P60 DASH 映像・音声合成、SESSDATA 認証、合集一括ダウンロード)
   - 透かしなし動画の直接抽出 (TikTok、Douyin、小紅書、快手)
   - YouTube (4K、Shorts)、X (Twitter)、Instagram 等のグローバルメディア
2. **多彩な出力モード**：
   - 映像＋音声 (MP4)、映像のみ (無音)、高音質音声抽出 (MP3 / M4A / FLAC)、アニメーション GIF、HD カバー画像保存
3. **多言語完全サポート**：
   - 日本語、英語、簡体字中国語、繁体字中国語の4言語に対応。
