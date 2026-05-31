# Bouquet for Android

[![Build](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml/badge.svg)](https://github.com/kengirie/bouquet-android/actions/workflows/build.yml)

English | [日本語](README.ja.md)

Nostr static-website gateway for Android. Paste an `npub1…` or a NIP-5A canonical label, fetch the manifest event from relays, fetch blobs from Blossom servers, and render the site in a hardened in-app WebView or in the system browser.


## Abstract

[NIP-5A](https://github.com/nostr-protocol/nips/blob/master/5A.md) publishes static sites as a Nostr manifest event (kind `15128` / `35128`) plus file blobs on Blossom servers. The spec ships a **smart-server** reference: a centralized HTTPS host (e.g. `nsite-host.com`) resolves the manifest, fetches blobs, and re-serves the site — so the browser trusts a single intermediary that terminates TLS, can observe requests, and can take a pubkey offline by dropping a subdomain.

Bouquet inverts that. The Android app subscribes to relays directly, fetches the manifest, verifies each blob's SHA-256, and serves the result over `http://127.0.0.1` to a hardened in-app WebView. No third party sits between the user and the Nostr / Blossom network.

NIP-5A approach (smart server):

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

Bouquet approach (smart client):

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

### Related: [nsite-deck](https://gitworkshop.dev/npub1hw6amg8p24ne08c9gdq8hhpqx0t0pwanpae9z25crn7m9uy7yarse465gr/nsite-deck)

nsite-deck is a **desktop** (macOS / Linux) smart-client: it installs a local DNS resolver to intercept `*.nsite`, runs an embedded Khatru relay + Blossom server as a persistent cache, and ships a management UI so any browser can open `https://npub1….nsite` directly. Bouquet covers the opposite case — *viewing* nsites on a phone with no system-level changes. No DNS changes, no browser configuration: a single APK gives you a hardened in-app WebView talking to a per-session loopback server, and the only persistent state is a small on-disk cache.

| | nsite-deck | Bouquet |
|---|---|---|
| Platform | macOS / Linux | Android |
| OS changes | DNS resolver + systemd / launchd daemons | none — single APK |
| URL entry | browser address bar via `*.nsite` DNS | paste npub / canonical label in-app |
| Local services | embedded relay + Blossom + gateway | per-session loopback HTTP |
| Cache | persistent (real Nostr relay) | TTL 5 min events + content-addressed blobs |

## Demo

https://github.com/kengirie/bouquet-android/raw/main/Screen_Recording_20260531_122304_Firefox.mp4

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

## Acknowledgements

This project was inspired by and references the following prior work:

- [nsite-gateway](https://github.com/hzrd149/nsite-gateway)
- [nsite-deck](https://gitworkshop.dev/npub1hw6amg8p24ne08c9gdq8hhpqx0t0pwanpae9z25crn7m9uy7yarse465gr/nsite-deck)
- [Amethyst](https://github.com/vitorpamplona/amethyst) 

## License

[MIT](LICENSE)
