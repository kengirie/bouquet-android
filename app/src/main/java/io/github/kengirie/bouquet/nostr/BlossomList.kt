package io.github.kengirie.bouquet.nostr

import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent

/**
 * Fetch the user's Blossom server list (BUD-03 / kind 10063).
 *
 * Returns the server URLs from `server` tags, or an empty list if no event
 * was found within the timeout. Mirrors the `fetchWriteRelays` helper from
 * Phase 4 — defensive `as?` because `EventFactory.create` *should* route
 * kind 10063 to [BlossomServersEvent], but if that ever regresses we want
 * an empty result rather than a crash.
 */
suspend fun NostrClient.fetchBlossomServers(
    pubkey: String,
    relays: Set<NormalizedRelayUrl>,
): List<String> {
    val filter = Filter(
        kinds = listOf(BlossomServersEvent.KIND),
        authors = listOf(pubkey),
    )
    val event = fetchOne(relays, filter) ?: return emptyList()
    val typed = event as? BlossomServersEvent ?: return emptyList()
    return typed.servers()
}
