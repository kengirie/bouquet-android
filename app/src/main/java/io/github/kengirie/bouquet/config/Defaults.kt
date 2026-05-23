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

    // No DEFAULT_BLOSSOM_SERVERS: NIP-5A requires the host to 404 when the
    // pubkey publishes neither manifest `server` tags nor a kind 10063
    // event. Injecting fallback CDNs would mask that contract.

    const val RELAY_TIMEOUT_MS = 8_000L
    const val BLOB_TIMEOUT_MS = 15_000L
    const val EVENT_CACHE_TTL_MS = 5L * 60_000L // 5 minutes
}
