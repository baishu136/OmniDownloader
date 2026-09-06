<div align="center">

# OmniDownloader (オールインワン音声・動画ダウンローダー)

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

Android プラットフォーム向けに設計された、モダンなネイティブメディアダウンローダー。**Jetpack Compose (Material 3)** で構築され、内部には実績ある **yt-dlp**、**FFmpeg**、**Aria2c** ネイティブエンジンを搭載し、主要 10 以上のプラットフォームから高品質な音声・動画を快適にダウンロードできます。

<br />

<img src="docs/images/screenshot.png" alt="OmniDownloader アプリメイン画面" width="320" style="border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.15);" />

</div>

---

## 🌟 主な機能と特徴

### 1. 🌐 10 以上の主要プラットフォームに対応
- **国内・グローバルプラットフォーム**：
  - **YouTube**：通常動画、Shorts、最大 4K/2K/1080P の超高画質ストリーム。
  - **TikTok**：ウォーターマーク（透かし）なしの高画質動画解析。
  - **X (旧 Twitter)**：ツイート内の動画を最高ビットレートで抽出。
  - **Instagram**：リール動画および投稿動画。
  - **Facebook**：公開動画およびショート動画。
  - **Pinterest**：高画質インスピレーション動画。
- **アジア圏人気メディアへの最適化**：
  - **Bilibili (ビリビリ動画)**：通常動画、マルチパート動画 (P1/P2/...)、シリーズコレクション、アニメ。スマホ版短縮 URL (`b23.tv`) の自動解決および Dash 音声・映像の自動合成。
  - **抖音 (Douyin)**・**快手 (Kuaishou)**・**小紅書 (RED)**：共有テキストからの URL 自動抽出および透かしなし動画取得。

### 2. 🎬 多彩なダウンロードモード
| モード | 説明 | 技術詳細 |
| :--- | :--- | :--- |
| **映像＋音声結合** | 目的の解像度（4K / 1080P / 720P / 480P）を選択し、MP4 形式で保存 | 最高画質映像と最高音質音声を自動選択し、内蔵 FFmpeg でロスレス合成 |
| **映像のみ (無音)** | 音声トラックを除去した純粋な映像素材として保存 | 動画編集者向けに `-an` フラグを注入し、再編集の手間を削減 |
| **音声抽出** | 動画から高音質オーディオトラックを抽出・変換 | **MP3**、**M4A (AAC)**、**FLAC (可逆圧縮)**、**OPUS** への変換出力とタイトルタグ自動埋め込み |
| **GIF アニメーション** | 動画のハイライトシーンを手軽なアニメーション GIF に変換 | FFmpeg パレット最適化による高画質出力 |
| **カバー画像保存** | 動画のサムネイルを端末のギャラリー（フォト）に直接保存 | トグルスイッチで動画と一緒に、または単体で即座に保存可能 |

### 3. 📦 プレイリスト・コレクションの誤ダウンロード防止機能
- **複数動画の自動判定**：「クリップボード貼付」機能使用時、URL がコレクション（Bilibili の複数パートやシリーズなど）である場合、**自動ダウンロードを一時保留**し、選択シートをポップアップ表示します。
- **柔軟な選択と一括ダウンロード**：
  - 1 本だけをプレビューして選択ダウンロード可能；
  - 「コレクションモード」に切り替えることで、各エピソードのチェックボックス、一括「全選択」や「反転」を使って、必要な動画だけをまとめてダウンロードできます。

### 4. 🚀 強力なバックグラウンドサービスと端末連携
- **フォアグラウンドサービス (Foreground Service)**：画面ロック時や別アプリ使用中もダウンロードが中断されず、通知バーに進捗率、転送速度、残り時間をリアルタイム表示。
- **多言語対応**：日本語、簡体字中国語、繁体字中国語、英語の 4 言語に対応。端末のシステム言語を自動認識し、設定画面から手動切替も可能。
- **メディアストレージ即時同期**：Android の Scoped Storage（ストレージ制限）に準拠。ダウンロード完了後、直ちにメディアスキャンを実行し、ギャラリーや音楽アプリですぐに利用可能。
- **プロキシ設定**：HTTP および SOCKS5 プロキシに対応し、ネットワーク制限のある環境でもスムーズに解析可能。
- **エンジンのオンライン更新**：設定画面から yt-dlp コアエンジンをワンタップで最新版にアップデート可能。

---

## 📲 ダウンロードとインストール

[Releases ページ](https://github.com/baishu136/OmniDownloader/releases) から最新の APK をダウンロードできます：

- 📦 **`OmniDownloader-v1.2.7-debug.apk`**：Android 向け APK（Android 8.0 以上、arm64-v8a アーキテクチャ対応）。
- 📦 **`OmniDownloader-v1.2.7-Source.zip`**：クリーンな全ソースコードアーカイブ。

---

## 🛠️ 技術スタック

- **言語**：Kotlin 1.9.23
- **ビルドツール**：Gradle 8.5 + AGP 8.3.2
- **UI フレームワーク**：Jetpack Compose + Material Design 3
- **アーキテクチャ**：MVVM (Model-View-ViewModel) + Clean Architecture
- **非同期処理**：Kotlin Coroutines + StateFlow
- **コアエンジン**：
  - `io.github.junkfood02.youtubedl-android:library:0.18.1` (yt-dlp)
  - `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` (FFmpeg)
  - `io.github.junkfood02.youtubedl-android:aria2c:0.18.1` (Aria2c)
- **ネットワーク・URL 嗅探**：Square OkHttp 4.12.0
- **画像読み込み**：Coil Compose 2.6.0

---

## 💻 ソースコードのビルド手順

ローカル環境でソースコードからビルドする場合：

```bash
# 1. リポジトリをクローン
git clone https://github.com/baishu136/OmniDownloader.git
cd OmniDownloader

# 2. Windows でのビルド
.\gradlew.bat assembleDebug --no-daemon

# 3. macOS / Linux でのビルド
chmod +x ./gradlew
./gradlew assembleDebug --no-daemon
```

ビルドされた APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

---

## 📄 ライセンス

本プロジェクトは [MIT ライセンス](LICENSE) の下で公開されています。個人利用、技術研究、オフラインバックアップ目的でのみご利用ください。各プラットフォームの利用規約および著作権法を遵守してください。
