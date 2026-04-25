package io.github.kengirie.bouquet.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import io.github.kengirie.bouquet.config.Defaults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One-shot subscribe-then-cancel helper, equivalent to nostr-tools'
 * `SimplePool.get()`: returns the first matching event from any of the
 * given relays, or `null` once every relay has sent EOSE without producing
 * one (or `timeoutMs` elapses, whichever comes first).
 *
 * Uses the manual subscription API rather than `subscribeAsFlow` because
 * we need first-event-OR-EOSE-from-all semantics.
 */
suspend fun NostrClient.fetchOne(
    relays: Set<NormalizedRelayUrl>,
    filter: Filter,
    timeoutMs: Long = Defaults.RELAY_TIMEOUT_MS,
): Event? {
    if (relays.isEmpty()) return null

    val subId = "fetchOne-${UUID.randomUUID()}"
    val deferred = CompletableDeferred<Event?>()
    val eosedRelays = mutableSetOf<NormalizedRelayUrl>()
    val filtersByRelay: Map<NormalizedRelayUrl, List<Filter>> =
        relays.associateWith { listOf(filter) }

    val listener = object : SubscriptionListener {
        override fun onEvent(
            event: Event,
            isLive: Boolean,
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            // First event wins; subsequent calls are ignored by CompletableDeferred.
            deferred.complete(event)
        }

        override fun onEose(
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            synchronized(eosedRelays) {
                eosedRelays.add(relay)
                if (eosedRelays.size >= relays.size && !deferred.isCompleted) {
                    deferred.complete(null)
                }
            }
        }
    }

    return try {
        subscribe(subId, filtersByRelay, listener)
        withTimeoutOrNull(timeoutMs) { deferred.await() }
    } finally {
        unsubscribe(subId)
    }
}
