# Bouquet for Android

[![Build](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml/badge.svg)](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml)

[English](README.md) | 日本語

Android 向け Nostr 静的サイトゲートウェイ。`npub1…` または NIP-5A canonical label を貼り付けると、リレーからマニフェストイベントを取得し、Blossom サーバから blob を取得し、ハードニングされた組み込み WebView もしくはシステムブラウザでサイトを描画します。

## 概要 (Abstract)

[NIP-5A](https://github.com/nostr-protocol/nips/blob/master/5A.md) は、パスから sha256 ハッシュへのマニフェストを Nostr イベント（kind `15128` / `35128`）として公開し、ファイル本体（blob）を Blossom サーバに置く、という静的サイトの仕様を定義しています。仕様にはあわせて **smart-server** 型のリファレンス実装が記述されています。すなわち、信頼された HTTP ホスト（例: `nsite-host.com`）が canonical なサブドメインラベルを解釈し、著者の NIP-65 リレーリストを取得し、マニフェストを取得し、参照されている blob を著者の Blossom サーバから引いてきて、HTTPS で再配信する、というモデルです。このモデルでは、ブラウザは Nostr に関する処理を一切行わず、TLS を終端し全リクエストを観測できる中央集権的な仲介者と話します。仲介者は理論上、その pubkey のコンテンツを検閲したり書き換えたりできる立場にいます。

Bouquet はこの構図を反転させます。同じ解決パイプラインを **クライアント側で** 実行するのです。Android アプリ自身がリレーに直接購読し、マニフェストイベントを取得し、各 blob の SHA-256 を検証し、`http://127.0.0.1` 経由で描画されたサイトをハードニングされた組み込み WebView またはシステムブラウザに配信します。ユーザと Nostr / Blossom ネットワークの間に第三者は立ちません。各サイトはユーザ自身が見ているリレー集合に対してロードされ、共有された TLS 終端も、共有されたキャッシュも、サブドメインを落とすことで pubkey をデプラットフォームできる運営者も存在しません。これは精神的には、Nostr クライアントが普段おこなっていること — リレーへ直接購読し、イベントごとに署名検証する — を静的 Web のユースケースに適用したものであり、ネットワークを単一の HTTP オリジンに折りたたんでしまうのとは対照的です。

NIP-5A 方式 (smart server):

```
  ┌──────────────────────────────────────┐
  │  Relays + Blossom servers   (dumb)   │
  └────────────────────┬─────────────────┘
                       │ NIP-65 / BUD-01
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
                       │ NIP-65 / BUD-01
                       ▼
  ┌──────────────────────────────────────┐
  │  User device                (smart)  │
  │  Bouquet resolves manifest ·         │
  │  fetches blobs · verifies SHA-256 ·  │
  │  serves on 127.0.0.1 → WebView       │
  └──────────────────────────────────────┘
```

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

## License

[MIT](LICENSE)
