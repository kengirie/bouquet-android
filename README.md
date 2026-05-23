# Bouquet for Android

[![Build](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml/badge.svg)](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml)

English | [日本語](README.ja.md)

Nostr static-website gateway for Android. Paste an `npub1…` or a NIP-5A canonical label, fetch the manifest event from relays, fetch blobs from Blossom servers, and render the site in a hardened in-app WebView or in the system browser.


## Abstract

[NIP-5A](https://github.com/nostr-protocol/nips/blob/master/5A.md) defines a contract for static websites whose path-to-hash manifest is published as a Nostr event (kind `15128` / `35128`) and whose file blobs are stored on Blossom servers. The spec accompanies this contract with a **smart-server** reference implementation: a trusted HTTP host (e.g. `nsite-host.com`) parses a canonical subdomain label, fetches the author's NIP-65 relay list, retrieves the manifest, pulls each referenced blob from the author's Blossom servers, and re-serves the result over HTTPS. The browser performs no Nostr work; it speaks to a single centralized intermediary that terminates TLS, observes every request, and is in a position to censor or rewrite content for the entire pubkey.

Bouquet inverts that arrangement. The same resolution pipeline runs **on the client**: the Android app subscribes to relays directly, fetches the manifest event, verifies each blob's SHA-256, and serves the rendered site over `http://127.0.0.1` to a hardened in-app WebView or the system browser. No third party stands between the user and the Nostr / Blossom network — each site loads against the user's own view of the relay set, with no shared TLS termination, no shared cache, and no operator capable of deplatforming a pubkey by dropping a subdomain. In spirit this is how a Nostr client normally works — direct relay subscriptions and per-event signature verification — applied to the static-web use case, rather than collapsing the network back into a single HTTP origin.

NIP-5A approach (smart server):

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

Bouquet approach (smart client):

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

## Install

1. Download the latest `bouquet-android-vX.Y.Z.apk` from the [Releases](https://github.com/kengirie/bouquet-android/releases) page.
2. Open the file on your Pixel / Android device (e.g. from the Files app).
3. Allow installation from unknown sources when prompted.
4. Install, then launch.

Android 8.0 (API 26) or newer is required.

## Usage

1. Paste an `npub1…` (root site, kind 15128) or a NIP-5A canonical label `<pubkeyB36><dTag>` (named site, kind 35128) into the input field.
2. The decoded result (type, pubkey, and identifier when applicable) is shown below as you type.
3. Tap **Open in browser** or **Open in WebView**.
4. The app fetches write relays, retrieves the manifest, fetches the Blossom server list, and then renders the site in your browser or the in-app WebView.

## Build from source

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

JDK 21 is required (either the JBR bundled with Android Studio or Homebrew's `openjdk@21` works).

## Stack

| Layer | Library / Component |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose Material3, dark-only |
| Nostr | [Quartz](https://github.com/vitorpamplona/amethyst/tree/main/quartz) (`com.vitorpamplona.quartz:quartz`) |
| Viewer | NanoHTTPD per-session loopback listener + WebView |
| Cache | JSON-backed event cache (TTL 5 min) + content-addressed blob cache |
| Build | AGP 9.1.1, JVM 21, R8 minified |

## Development

Run the unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Build a release APK locally (falls back to debug signing if no release keystore is configured):

```bash
./gradlew :app:assembleRelease
```

## Releasing (for maintainers)

When a `v*` tag is pushed, GitHub Actions builds the APK, signs it with the release keystore, and attaches it to the Releases page.

```bash
git tag v0.1.0
git push origin v0.1.0
```

## License

[MIT](LICENSE)
