package io.github.kengirie.bouquet.config

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer

/**
 * App-wide defaults: lookup relays, blossom servers, timeouts, cache TTL.
 * Mirrors bouquet-desktop's `src/core/defaults.ts`.
 */
object Defaults {
    val LOOKUP_RELAY_URLS = listOf(
        "wss://relay.damus.io",
        "wss://relay.nostr.band",
        "wss://nos.lol",
        "wss://purplepag.es",
    )

    /** Pre-normalized lookup relays for use with Quartz APIs. */
    val DEFAULT_LOOKUP_RELAYS: List<NormalizedRelayUrl> =
        LOOKUP_RELAY_URLS.map { RelayUrlNormalizer.normalize(it) }

    // Relays known to carry NIP-5A static-site events (kind 15128 / 35128).
    // Disjoint from LOOKUP_RELAY_URLS so timeline discovery doesn't share a
    // socket pool with per-pubkey relay-list lookups. relay.nsite.lol is
    // the canonical indexer; the rest are general-purpose relays that
    // accept these kinds in practice.
    val TIMELINE_RELAY_URLS = listOf(
        "wss://relay.nsite.lol",
        "wss://relay.damus.io",
        "wss://relay.nostr.band",
        "wss://nos.lol",
    )

    val DEFAULT_TIMELINE_RELAYS: List<NormalizedRelayUrl> =
        TIMELINE_RELAY_URLS.map { RelayUrlNormalizer.normalize(it) }

    // No DEFAULT_BLOSSOM_SERVERS: NIP-5A requires the host to 404 when the
    // pubkey publishes neither manifest `server` tags nor a kind 10063
    // event. Injecting fallback CDNs would mask that contract.

    const val RELAY_TIMEOUT_MS = 8_000L
    const val BLOB_TIMEOUT_MS = 15_000L
    const val EVENT_CACHE_TTL_MS = 5L * 60_000L // 5 minutes

    // Timeline discovery aggregates events across multiple relays until all
    // EOSE or this timeout elapses, so it needs more headroom than the
    // single-event fetchOne flow.
    const val TIMELINE_TIMEOUT_MS = 12_000L
    const val TIMELINE_METADATA_TIMEOUT_MS = 8_000L

    /** Maximum nsite events fetched per timeline page. */
    const val TIMELINE_PAGE_LIMIT = 30

    // Browser sessions have no owning Activity, so the registry sweeps them
    // after this window of inactivity. WebView sessions opt out — their
    // lifetime tracks the hosting Activity instead.
    const val BROWSER_SESSION_IDLE_TIMEOUT_MS = 10L * 60_000L // 10 minutes
    const val BROWSER_SESSION_SWEEP_INTERVAL_MS = 60_000L // 1 minute
}
