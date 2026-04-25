# Bouquet Android 実装計画

> bouquet-desktop (Electron + nostr-tools) を Android (Compose + **Quartz**) に移植する。
> 段階を踏んで、各フェーズ完了時にビルド & 起動可能な状態を維持する。

## 1. 全体像と移植方針

**bouquet-desktop の本質**: ユーザーが入力した nostr アドレス（`npub1…` / `nprofile1…` / `naddr1…`）から、kind 10002（NIP-65 リレーリスト）→ kind 15128/35128（マニフェスト）→ kind 10063（BUD-03 Blossom サーバーリスト）と辿り、`path → sha256` のマップから Blossom サーバー上の blob を取得して、サンドボックス化されたビューワで表示する **Nostr Web ゲートウェイ**。

**採用ライブラリ**: [Quartz](https://github.com/vitorpamplona/amethyst/tree/main/quartz) （Amethyst の Nostr プロトコル実装、`com.vitorpamplona.quartz:quartz:1.08.0`、Maven Central 公開、MIT）

**移植マッピング**:

| desktop (Electron + nostr-tools) | Android (Compose + Quartz) |
|---|---|
| `src/core/address.ts` (NIP-19 decode) | **`Nip19Parser.uriToRoute()` → `NPub` / `NProfile` / `NAddress`** |
| `src/core/nostr.ts` (`SimplePool.get`) | **`NostrClient.subscribeAsFlow(relay, filter).first()` + `withTimeoutOrNull`** |
| kind 10002 (NIP-65) | **`AdvertisedRelayListEvent.writeRelays()`** |
| kind 15128 root site | **`RootSiteEvent.paths()` / `.servers()`** |
| kind 35128 named site | **`NamedSiteEvent.paths()` / `.servers()` / `.identifier()`** |
| kind 10063 (BUD-03) | **`BlossomServersEvent.servers()`** |
| `path` タグ → `sha256` | **`PathTag(path, hash)`** |
| `src/core/blossom.ts` (`fetch + SHA-256`) | OkHttp + `MessageDigest("SHA-256")` |
| `src/core/manifest.ts` の `resolvePath` (`/index.html` / `404.html` フォールバック) | 自前実装（純粋関数 ~30 行） |
| `src/core/mime.ts` | 自前実装（拡張子マップ） |
| `src/core/gateway.ts` | 自前実装（Quartz の API を組み合わせる薄い service） |
| Per-session `http.Server` + `BrowserWindow` | **NanoHTTPD で `127.0.0.1:<ephemeral>` ループバック + WebView**（採用方式） |
| `JsonEventCache` / `FileBlobCache` | `context.filesDir/event-cache.json` / `context.cacheDir/blob-cache/<sha256>` |
| Tailwind index.html 入力画面 | Compose Material3 のホーム画面 |

## 2. 重要な制約・前提

1. **Quartz は `compileSdk=37` / `minSdk=26` / Java 21**。現状の `minSdk=24` / Java 11 から引き上げる必要がある:
   - `minSdk = 24 → 26`（影響軽微、2026 年現在 24/25 のシェアは事実上ゼロ）
   - `sourceCompatibility / targetCompatibility = 11 → 21`（AGP 9.1.1 で対応可）
2. **Quartz の Filter は任意タグ filter をサポート**（`tags: Map<String, List<String>>?`）→ kind 35128 + `#d` での identifier 絞り込みがリレー側で完結（ローカル後処理不要）。
3. **Quartz には `BasicOkHttpWebSocket` 内蔵** → Ktor アダプタ自作は不要、OkHttp をそのまま使える。
4. **Quartz は Amethyst で本番運用** → 枯れている。
5. **ビューワ方式 = ループバック HTTP**: desktop と同形に `127.0.0.1:<ephemeral>` に NanoHTTPD で listener を立て、WebView から `http://127.0.0.1:<port>/<path>` を読む。`network_security_config.xml` で `127.0.0.1` のみ cleartext 許可。

## 3. 段階的実装計画（各フェーズ完了時にビルド & 起動可能を維持）

各フェーズの DoD（Done の判定）は **`./gradlew :app:assembleDebug` が通り、エミュレータで起動して既存画面 + その回追加分が動く** こと。

### Phase 0 — 出発点（現状）
- 空の Compose プロジェクト、`MainActivity` が "Hello Android" を表示
- DoD: 既に達成済み

### Phase 1 — 依存関係の追加 & Quartz スモークテスト
**目的**: Quartz が実機で動くことを最速で確認
- `gradle/libs.versions.toml` に追加:
  - `quartz = "1.08.0"` → `com.vitorpamplona.quartz:quartz`
  - `okhttp = "5.3.2"` （Quartz が要求する版）
  - `kotlinx-coroutines-core`
  - NanoHTTPD（Phase 11 用に先行追加してもよい）
- `app/build.gradle.kts` で:
  - `compileOptions` の `sourceCompatibility` / `targetCompatibility` を `JavaVersion.VERSION_21` に
  - `kotlinOptions { jvmTarget = "21" }` 追加
  - `defaultConfig.minSdk = 26`
  - 上記依存追加
- `AndroidManifest.xml` に `<uses-permission android:name="android.permission.INTERNET"/>`
- `MainActivity` に「Connect」ボタン: 押すと `NostrClient(BasicOkHttpWebSocket.Builder { OkHttpClient() }, scope).subscribeAsFlow("wss://relay.damus.io", Filter(kinds = listOf(1), limit = 1))` を `.first()` し、得られた event の content を Toast 表示
- DoD: 実機/エミュで note の content が画面に出る

### Phase 2 — ホーム画面 UI（NIP-19 デコード結果を表示）
- Compose Navigation を導入し `HomeScreen` を作成（desktop のヘッダ "Bouquet" + TextField + GO ボタンを Material3 で再現）
- ボタン押下で `Nip19Parser.uriToRoute(input)` を呼び、`NPub` / `NProfile` / `NAddress` のどれかを判定
- `NAddress` の場合は `kind` が `15128` / `35128` のどちらかをチェック（不正なら表示）
- 結果（pubkey hex / identifier / relay hints）を画面下部に表示。**まだ Nostr 通信はしない**
- 不正アドレス時のエラーバナー（desktop の `errors.ts` の文言を踏襲）
- DoD: 妥当な npub/nprofile/naddr で正しい中身が、不正アドレスでエラーが出る

### Phase 3 — One-shot リレー取得ヘルパ
- `nostr/RelayFetcher.kt`:
  ```kotlin
  suspend fun NostrClient.fetchOne(
      relays: Set<NormalizedRelayUrl>,
      filter: Filter,
      timeoutMs: Long = 8_000,
  ): Event?
  ```
- 内部で各 relay に `subscribeAsFlow(relay, filter)` をかけて `merge` して `firstOrNull()`、外側で `withTimeoutOrNull`、finally で subscription を `unsubscribe`
- `config/Defaults.kt` に desktop と同じ定数:
  - `DEFAULT_LOOKUP_RELAYS = listOf("wss://relay.damus.io", "wss://relay.nostr.band", "wss://nos.lol", "wss://purplepag.es")`
  - `DEFAULT_BLOSSOM_SERVERS = listOf("https://cdn.satellite.earth", "https://blossom.primal.net", "https://blossoml3001.site")`
  - `RELAY_TIMEOUT_MS = 8_000`、`BLOB_TIMEOUT_MS = 15_000`、`EVENT_CACHE_TTL_MS = 5 * 60_000`
- 動作確認用に Phase 2 のホーム画面に「kind 0 取得」テストボタンを追加し、`Filter(kinds = listOf(0), authors = listOf(pubkeyHex))` で `UserMetadata` を fetch して content を表示
- DoD: 既知の npub について metadata が表示される

### Phase 4 — リレー & マニフェスト解決
- `nostr/SiteResolver.kt`:
  ```kotlin
  suspend fun fetchWriteRelays(pubkey: HexKey, lookupRelays: Set<NormalizedRelayUrl>): List<String>
  suspend fun fetchManifest(addr: SiteAddress, relays: Set<NormalizedRelayUrl>): Event?
  ```
- リレー一覧: `Filter(kinds = listOf(AdvertisedRelayListEvent.KIND), authors = listOf(pubkey))` → `event as AdvertisedRelayListEvent` → `writeRelaysNorm()`。空なら lookup relays をフォールバック
- マニフェスト:
  - `npub` / `nprofile` → `Filter(kinds = listOf(RootSiteEvent.KIND), authors = listOf(pubkey))`
  - `naddr` → `Filter(kinds = listOf(NamedSiteEvent.KIND), authors = listOf(pubkey), tags = mapOf("d" to listOf(identifier)))` ← **Quartz のおかげで `#d` 直接 filter**
- 取得した event を `event.toEventClass()` あるいは `as? RootSiteEvent` / `NamedSiteEvent` にダウンキャスト
- ホーム画面で結果として「path タグ件数」「server タグ件数」を表示
- DoD: 既知の bouquet サイトで paths/servers のカウントが画面に出る

### Phase 5 — マニフェスト path 解決ロジック
- `core/PathResolution.kt`: desktop `manifest.ts` の `resolvePath` を Kotlin 化
  ```kotlin
  data class PathResolution(val sha256: String, val resolvedPath: String, val is404: Boolean)
  fun resolvePath(paths: List<PathTag>, requestedPath: String): PathResolution?
  ```
- ロジックは desktop と完全に同じ（exact → +/index.html → /404.html）
- `core/Mime.kt`: 拡張子 → MIME マップ（desktop `mime.ts` をそのまま）
- 純粋関数なのでユニットテストで網羅（exact / index.html / 404 / 末尾スラッシュ正規化）
- DoD: テスト緑

### Phase 6 — Blossom サーバーリスト + blob 取得 + SHA-256 検証
- `nostr/BlossomList.kt`: `Filter(kinds = listOf(BlossomServersEvent.KIND), authors = listOf(pubkey))` を fetch → `BlossomServersEvent.servers()`
- `core/ServerDedup.kt`: desktop `deduplicateServers` の Kotlin 化（末尾スラッシュ無視）
- `blossom/BlossomFetcher.kt`: OkHttp で `GET <server>/<sha256>` をサーバ列順に試行、`MessageDigest("SHA-256")` で検証、最初の整合 blob を返す
  ```kotlin
  suspend fun fetchBlob(sha256: String, servers: List<String>, timeoutMs: Long = 15_000): BlobResult?
  ```
- desktop の `BLOB_TIMEOUT_MS` 同等を OkHttp の `callTimeout` で実現
- DoD: ヘルパ単体のユニットテスト（`MockWebServer`）が通る

### Phase 7 — Gateway 統合
- `core/Gateway.kt`: desktop `gateway.ts` の `resolveSiteResource` を Kotlin に移植。Quartz の API を組み合わせる薄い service
  ```kotlin
  data class ResolvedSiteResource(val status: Int, val contentType: String, val body: ByteArray, val resolvedPath: String, val is404: Boolean)
  class GatewayError(val status: Int, val statusText: String, message: String) : Exception(message)
  
  suspend fun resolveSiteResource(
      addressSegment: String,
      path: String,
      deps: GatewayDeps,
  ): ResolvedSiteResource
  ```
- `GatewayDeps` インターフェース（`EventCacheAdapter` / `BlobCacheAdapter` / Quartz の `NostrClient`）
- フローは desktop と同じ: 1) `Nip19Parser` で decode → 2) リレー取得 → 3) マニフェスト取得 → 4) `resolvePath` → 5) blob キャッシュ確認 → 6) Blossom サーバー解決 → 7) blob 取得 → 8) MIME 推論 → 9) blob キャッシュ保存
- インメモリ実装の deps（`HashMap` ベース）でユニットテスト
- DoD: Gateway のユニットテスト緑、UI とは未結合

### Phase 8 — ループバック HTTP サーバ + WebView ビューワ
- 依存に NanoHTTPD（`org.nanohttpd:nanohttpd:2.3.1`）を追加
- `viewer/SessionRegistry.kt`: desktop `electron/session-registry.ts` を Kotlin 化
  ```kotlin
  data class ViewerSession(val id: String, val addressSegment: String, val port: Int)
  class SessionRegistry(private val deps: GatewayDeps) {
      fun createSession(addressSegment: String): ViewerSession  // NanoHTTPD を 127.0.0.1:0 で起動
      fun closeSession(id: String)
      fun closeAllSessions()
  }
  ```
- リクエストごとに `Gateway.resolveSiteResource(addressSegment, reqPath, deps)` を呼び、`NanoHTTPD.Response` を返す
- エラーは desktop の `hintForStatus()` を Kotlin 化して同じ HTML エラーページを返す
- `res/xml/network_security_config.xml`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <network-security-config>
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="false">127.0.0.1</domain>
      </domain-config>
  </network-security-config>
  ```
- `AndroidManifest.xml` に `android:networkSecurityConfig="@xml/network_security_config"`
- 新 Activity `ViewerActivity`: Intent extra で `addressSegment` を受け取り、`SessionRegistry.createSession()` で得たポートで `WebView.loadUrl("http://127.0.0.1:<port>/")`
- WebView 設定: `javaScriptEnabled = true`、`allowFileAccess = false`、`allowContentAccess = false`、`setAllowFileAccessFromFileURLs(false)`、`setAllowUniversalAccessFromFileURLs(false)`、`setMixedContentMode(MIXED_CONTENT_NEVER_ALLOW)`
- `WebViewClient.shouldOverrideUrlLoading` で **異なる origin への遷移をブロック**（外部リンクは `Intent.ACTION_VIEW` で OS に渡す） — desktop の `will-navigate` 相当
- `Activity.onDestroy` で `closeSession(id)` を呼んでポート解放
- `Application` クラスでアプリ終了時 `closeAllSessions()` 実行
- DoD: 既知の bouquet サイトが WebView で開いて HTML+CSS+画像が表示される

### Phase 9 — 永続キャッシュ
- `cache/JsonEventCache.kt`: `context.filesDir/event-cache.json`、TTL=5 分、デバウンス flush（desktop と同じ）
- `cache/FileBlobCache.kt`: `context.cacheDir/blob-cache/<sha256>` + `<sha256>.meta.json`、`SHA256_REGEX` で path traversal 防御
- `Gateway` の `deps` をインメモリから差し替え
- DoD: 一度開いたサイトを機内モードで再オープンしてもキャッシュから表示される

### Phase 10 — ライフサイクル & エラー UI
- `Application` シングルトンに `NostrClient` プールと `SessionRegistry` を保持し、ViewModel スコープから利用
- ホーム画面に進捗インジケータ（リレー検索中 → マニフェスト取得中 → blob ダウンロード中）
- ビューワサーバーから 400/404/405/500/502 のエラーページ HTML を返す（desktop と同じ配色・文言）
- DoD: 不正 npub / 存在しないパス / Blossom 全滅 / リレー全滅 の各シナリオで分かりやすい画面が出る

### Phase 11 — 仕上げ
- desktop のダークテーマ配色（`#111125` / `#ffb2b7`）を `Color.kt` / `Theme.kt` に反映
- アプリアイコン、`strings.xml`
- `release` ビルドの ProGuard/R8 ルール（Quartz / OkHttp / Jackson / NanoHTTPD）
- DoD: `./gradlew assembleRelease` 通過

## 4. ディレクトリ構成（最終形の目安）

```
app/src/main/java/io/github/kengirie/bouquet/
├── BouquetApplication.kt        # NostrClient / SessionRegistry のシングルトン
├── MainActivity.kt
├── ViewerActivity.kt
├── ui/
│   ├── home/HomeScreen.kt
│   ├── viewer/ViewerScreen.kt
│   ├── error/ErrorScreen.kt
│   └── theme/{Color,Theme,Type}.kt
├── nostr/
│   ├── RelayFetcher.kt          # one-shot fetch (subscribeAsFlow + first + timeout)
│   └── SiteResolver.kt          # write relays / manifest / blossom servers の取得
├── blossom/
│   └── BlossomFetcher.kt        # OkHttp GET + SHA-256 verify
├── core/
│   ├── SiteAddress.kt           # NPub/NProfile/NAddress を内部型に正規化
│   ├── Gateway.kt               # resolveSiteResource + GatewayError + GatewayDeps
│   ├── PathResolution.kt        # resolvePath (純粋関数)
│   ├── Mime.kt                  # 拡張子 → MIME
│   └── ServerDedup.kt           # 末尾スラッシュ無視で deduplicate
├── viewer/
│   └── SessionRegistry.kt       # NanoHTTPD で 127.0.0.1 ephemeral listener
├── cache/
│   ├── JsonEventCache.kt
│   └── FileBlobCache.kt
└── config/
    └── Defaults.kt              # 既定 lookup relays / blossom servers / timeouts
```

## 5. 主要依存関係（Phase 1 で追加）

```toml
# gradle/libs.versions.toml に追加する版
[versions]
quartz = "1.08.0"
okhttp = "5.3.2"
kotlinxCoroutines = "1.10.2"
nanohttpd = "2.3.1"

[libraries]
quartz = { module = "com.vitorpamplona.quartz:quartz", version.ref = "quartz" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
nanohttpd = { module = "org.nanohttpd:nanohttpd", version.ref = "nanohttpd" }
```

## 6. オープン課題

- 動作確認用のテスト npub / naddr（既知の bouquet-published サイト）を 1 つ用意する → Phase 8 の e2e で必要
- `BlossomServersEvent` の kind が **10063** であることを Quartz が前提としているか確認（bouquet-desktop の前提と一致）
- Quartz の `NostrClient.subscribeAsFlow` が EOSE で完了する挙動かを Phase 3 のスモークテストで確認（必要なら自前で `SubscriptionListener` を使い `onEose` でフロー終了）
