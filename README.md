# Bouquet for Android

[![Build](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml/badge.svg)](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml)

Nostr static-website gateway for Android. Paste an `npub1…` / `naddr1…` address, fetch the manifest event from relays, fetch blobs from Blossom servers, and render the site in a hardened in-app WebView or in the system browser.

Inspired by [bouquet-desktop](https://github.com/kengirie/bouquet-desktop).

## インストール

1. [Releases](https://github.com/kengirie/bouquet-android/releases) ページから最新の `bouquet-android-vX.Y.Z.apk` をダウンロード
2. Pixel / Android 端末でファイルを開く（Files アプリ等から）
3. 「不明なソースのインストール」を許可
4. インストール → 起動

Android 8.0 (API 26) 以降が必要です。

## 使い方

1. 入力欄に `npub1…` / `naddr1…`（kind 15128 root site または kind 35128 named site）を貼り付ける
2. デコード結果（pubkey / リレーヒント / kind）が即座に下に表示される
3. **「ブラウザで開く」** または **「WebView で開く」** をタップ
4. 自動でリレー検索 → マニフェスト取得 → Blossom サーバ列取得 → ブラウザ / WebView で表示

## ソースからビルド

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

JDK 21 が必要です（Android Studio 同梱の JBR / Homebrew の `openjdk@21` どちらでも可）。

## 構成

| Layer | Library / Component |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose Material3, dark-only |
| Nostr | [Quartz](https://github.com/vitorpamplona/amethyst/tree/main/quartz) (`com.vitorpamplona.quartz:quartz`) |
| Blossom | OkHttp 5 + SHA-256 verification |
| Viewer | NanoHTTPD per-session loopback listener + WebView |
| Cache | JSON-backed event cache (TTL 5 min) + content-addressed blob cache |
| Build | AGP 9.1.1, JVM 21, R8 minified |

詳細な設計は [PLAN.md](PLAN.md) を参照。

## 開発

ユニットテスト:

```bash
./gradlew :app:testDebugUnitTest
```

リリースビルド（ローカル、debug 署名にフォールバック）:

```bash
./gradlew :app:assembleRelease
```

## リリース手順（メンテナ向け）

GitHub Actions が `v*` タグの push を検知すると、release keystore で署名された APK を Releases ページに自動添付します。

```bash
git tag v0.1.0
git push origin v0.1.0
```

## License

[MIT](LICENSE)
