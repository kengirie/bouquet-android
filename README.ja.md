# Bouquet for Android

[![Build](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml/badge.svg)](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml)

[English](README.md) | 日本語

Android 向け Nostr 静的サイトゲートウェイ。`npub1…` または NIP-5A canonical label を貼り付けると、リレーからマニフェストイベントを取得し、Blossom サーバから blob を取得し、ハードニングされた組み込み WebView もしくはシステムブラウザでサイトを描画します。

## 概要 (Abstract)

[NIP-5A](https://github.com/nostr-protocol/nips/blob/master/5A.md) は、パス→SHA-256 のマニフェストを Nostr イベント（kind `15128` / `35128`）として公開し、ファイル本体は Blossom サーバに置く、という静的サイトの仕様です。仕様にはあわせて **smart-server** 型のリファレンス実装 — 中央集権的な HTTPS ホスト（例: `nsite-host.com`）がマニフェストを解決し、blob を取得し、HTTPS で再配信する — が記述されています。このモデルではブラウザは Nostr に関する処理を一切行わず、TLS を終端し、リクエストを観測でき、サブドメインを落とすことで pubkey をオフラインにもできる単一の仲介者を信頼することになります。

Bouquet はこれを **クライアント側で** 実行します。Android アプリ自身がリレーに直接購読し、マニフェストを取得し、各 blob の SHA-256 を検証し、`http://127.0.0.1` 経由でハードニングされた組み込み WebView に配信します。ユーザと Nostr / Blossom ネットワークの間に第三者は立ちません。

NIP-5A 方式 (smart server):

```
  ┌──────────────────────────────────────┐
  │  Relays + Blossom servers   (dumb)   │
  └────────────────────┬─────────────────┘
                       │ site manifest + blobs
                       ▼
  ┌──────────────────────────────────────┐
  │  nsite-host.com             (smart)  │
  │  resolves manifest · fetches blobs · │
  │  verifies SHA-256 · serves HTTPS     │
  └────────────────────┬─────────────────┘
                       │ HTTPS
                       ▼
  ┌──────────────────────────────────────┐
  │  User device                (dumb)   │
  │  browser only — trusts the host      │
  └──────────────────────────────────────┘
```

Bouquet 方式 (smart client):

```
  ┌──────────────────────────────────────┐
  │  Relays + Blossom servers   (dumb)   │
  └────────────────────┬─────────────────┘
                       │ site manifest + blobs
                       ▼
  ┌──────────────────────────────────────┐
  │  User device                (smart)  │
  │  Bouquet resolves manifest ·         │
  │  fetches blobs · verifies SHA-256 ·  │
  │  serves on 127.0.0.1 → WebView       │
  └──────────────────────────────────────┘
```

### 関連プロジェクト: [nsite-deck](https://gitworkshop.dev/npub1hw6amg8p24ne08c9gdq8hhpqx0t0pwanpae9z25crn7m9uy7yarse465gr/nsite-deck)

nsite-deck は **デスクトップ** (macOS / Linux) 向けの smart-client 実装で、ローカル DNS リゾルバが `*.nsite` を横取りし、永続キャッシュとして埋め込み Khatru リレー + Blossom サーバを動かし、管理 UI も備えることで、普通のブラウザから `https://npub1….nsite` を直接開けるようにします。Bouquet は反対側のユースケース、つまり **スマホ上で nsite を *見る*** ことに、システムへの変更なしで特化しています。DNS の書き換えもブラウザ設定も不要で、APK 一つでハードニングされた組み込み WebView が起動し、セッション毎のループバックサーバ越しにサイトを描画します。永続的に残るのはディスク上の小さなキャッシュだけです。

| | nsite-deck | Bouquet |
|---|---|---|
| プラットフォーム | macOS / Linux | Android |
| OS への変更 | DNS リゾルバ + systemd / launchd デーモン | なし — APK 一つ |
| URL 入力 | ブラウザのアドレスバー (`*.nsite` DNS) | アプリ内で npub / canonical label を貼り付け |
| ローカルサービス | 埋め込みリレー + Blossom + gateway | per-session ループバック HTTP |
| キャッシュ | 永続（実体は Nostr リレー） | TTL 5 分のイベント + content-addressed blob |

## デモ

https://github.com/user-attachments/assets/e1bbbbb9-0bc6-419b-9b1e-f44fdb3772fd

## インストール

1. [Releases](https://github.com/kengirie/bouquet-android/releases) ページから最新の `bouquet-android-vX.Y.Z.apk` をダウンロード
2. Pixel / Android 端末でファイルを開く（Files アプリ等から）
3. 「不明なソースのインストール」を許可
4. インストール → 起動

Android 8.0 (API 26) 以降が必要です。

## 使い方

1. 入力欄に `npub1…`（root site, kind 15128）または NIP-5A canonical label `<pubkeyB36><dTag>`（named site, kind 35128）を貼り付ける
2. デコード結果（type / pubkey / 該当する場合は identifier）が入力に合わせて下に表示される
3. **「Open in browser」** または **「Open in WebView」** をタップ
4. アプリが write リレーの取得 → マニフェスト取得 → Blossom サーバ一覧の取得を行い、ブラウザもしくは組み込み WebView でサイトを描画する

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
| UI | Jetpack Compose Material3, ダークモード固定 |
| Nostr | [Quartz](https://github.com/vitorpamplona/amethyst/tree/main/quartz) (`com.vitorpamplona.quartz:quartz`) |
| Viewer | NanoHTTPD セッション毎のループバックリスナー + WebView |
| Cache | JSON ベースのイベントキャッシュ（TTL 5 分） + コンテンツアドレス指定の blob キャッシュ |
| Build | AGP 9.1.1, JVM 21, R8 minify |


## 開発

ユニットテスト:

```bash
./gradlew :app:testDebugUnitTest
```

ローカルでのリリースビルド（リリース用 keystore が未設定なら debug 署名にフォールバック）:

```bash
./gradlew :app:assembleRelease
```

## リリース手順（メンテナ向け）

`v*` タグを push すると GitHub Actions が APK をビルドし、リリース用 keystore で署名して Releases ページに添付します。

```bash
git tag v0.1.0
git push origin v0.1.0
```

## Acknowledgements

このプロジェクトは以下の先行実装を参考にしています。

- [nsite-gateway](https://github.com/hzrd149/nsite-gateway) 
- [nsite-deck](https://gitworkshop.dev/npub1hw6amg8p24ne08c9gdq8hhpqx0t0pwanpae9z25crn7m9uy7yarse465gr/nsite-deck)
- [Amethyst](https://github.com/vitorpamplona/amethyst) 

## License

[MIT](LICENSE)
