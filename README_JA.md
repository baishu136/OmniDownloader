<div align="center">

# OmniDownloader (万能メディアダウンローダー)

<p>
  <a href="README.md">English</a> •
  <a href="README_ZH.md">简体中文</a> •
  <a href="README_ZH_TW.md">繁體中文</a> •
  <a href="README_JA.md">日本語</a>
</p>

[![Release](https://img.shields.io/github/v/release/baishu136/OmniDownloader?color=blue&label=Release)](https://github.com/baishu136/OmniDownloader/releases)
[![Android](https://img.shields.io/badge/Android-v1.5.10-green.svg)](https://developer.android.com)
[![iOS](https://img.shields.io/badge/iOS-SwiftUI%20Native-orange.svg)](OmniDownloader-iOS)
[![Windows](https://img.shields.io/badge/Windows-Desktop%20%26%20Web-blue.svg)](windows)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)

**OmniDownloader** は、クロスプラットフォームに対応した最新のメディアダウンローダーです（**Android ネイティブ、Apple iOS ネイティブ、Windows デスクトップ独立版、Web バックグラウンド版**）。**Bilibili (4K/1080P60/DASH分離ストリーム合成/プレイリスト一括)、TikTok、Douyin (抖音)、YouTube、X (Twitter)、小紅書、快手** などの透かしなし高画質メディアを高速抽出・保存できます。

</div>

---

## 🌟 クロスプラットフォーム概要

| プラットフォーム | 対象デバイス | コア技術 | インストールと取得方法 |
| :--- | :--- | :--- | :--- |
| **🤖 Android 版** | Android スマートフォン / タブレット / TV (Android 8.0+) | Kotlin + Jetpack Compose + yt-dlp + FFmpeg | [最新 APK (v1.5.10) をダウンロード](https://github.com/baishu136/OmniDownloader/releases/latest/download/OmniDownloader_LATEST.apk) または Releases より |
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

---

## 📲 クイックダウンロード

- **Android 最新版**: [`OmniDownloader_LATEST.apk`](https://github.com/baishu136/OmniDownloader/releases/latest/download/OmniDownloader_LATEST.apk) (または [`OmniDownloader-v1.5.10-release.apk`](https://github.com/baishu136/OmniDownloader/releases/latest/download/OmniDownloader-v1.5.10-release.apk))
- **iOS サイドロードガイド**: [`OmniDownloader-iOS/README_IOS.md`](OmniDownloader-iOS/README_IOS.md) を参照
- **Windows 配布パッケージ**: [`windows/release/`](windows/release/)

---

## Android v1.5.10

- コレクションの元動画と Bilibili のパートを正しく選択し、一覧順と実際の動画 URL を保持。
- サイレント解析と手動ブラウザーのメディア捕捉処理を共通化し、リソースパネルと深いスキャンの操作を削除。
- ダウンロード画面に単一の移動する選択インジケーターを採用し、設定 → アプリ情報に GitHub リンクを追加。
- 1.5.7–1.5.9 のタブ・一覧最適化、コンパクトなホーム、7 分類の設定子画面、Material 3 操作、R8 とリソース縮小を含みます。
- Android 8.0+、ARM64、APK **53.35 MiB**。**JVM テスト 27 件、エミュレーターの端末テスト 8 件**と最適化版の検証記録あり。ARM64 実機のネイティブメディア処理は未検証です。

[リリースと APK](https://github.com/baishu136/OmniDownloader/releases/tag/v1.5.10) · [変更詳細](docs/RELAY_AND_COLLECTION_1.5.10.md) · [復元した検証記録](docs/RELEASE_VERIFICATION_1.5.10.md)

---

## 📝 過去の更新履歴 (v1.4.8)

- **[NEW] 中継解析サイト機能の刷新**:
  - 中継サイト管理を【設定】画面へ統合。未登録時はホーム画面の空カードを非表示化。
  - URL入力時に700msデバウンスでWebページ `<title>` を非同期自動取得し、サイト名を自動補完。
  - ワンクリックで静默直接解析・ダウンロードを行うメインルートを復帰。CAPTCHA対策としてブラウザウィンドウも保持。
- **[SEC & FIX] 通信傍受およびダウンロードエンジンの堅牢化**:
  - **HTTP 405 (Method Not Allowed) エラーの完全修正**: 内部 API への誤傍受を防止し、GET メディアストリームのみを確実に取得。
  - **プレイヤーでの source error (再生不可) の解消**: バイナリ Magic Bytes 検証を導入し、HTML/JSONの誤保存を防止。HLS/M3U8 の FFmpeg 自動無劣化多重化および JWT 直リンク復号に対応。
- **[PERF] 120fps / 60fps フルフレームレートの快適性向上**:
  - `DownloadTask` を完全不変データクラスへリファクタリングし、不要な再描画（Recomposition）を100%排除。
  - 進捗表示をマイクロコンポーザブルへ分離し、スクロールのもたつきを完全解消。
- **[ALIGN] Windows 版および iOS 版との完全同期**:
  - **Windows 版**: タイトル自動取得 API (`/api/relay-sites/fetch-title`)、設定画面への移行、直リンク復号に対応。
  - **iOS 版**: UserDefaults 永続化、タイトル自動ストリーム取得、ネイティブ嗅探シートとの同期。

---

## 📄 ライセンス
本プロジェクトは MIT ライセンスの下で公開されています。
