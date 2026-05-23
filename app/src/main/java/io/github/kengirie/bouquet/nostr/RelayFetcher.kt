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
import java.util.concurrent.ConcurrentHashMap

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

/**
 * Aggregate one-shot fetch: open a subscription against every [relays] entry
 * with [filter], collect every event delivered (deduped by `event.id`), and
 * complete when **all relays** have sent EOSE or when [timeoutMs] elapses.
 *
 * Differs from [fetchOne] in three ways: returns the full set rather than
 * "first wins", dedupes on the way in so duplicate deliveries from
 * overlapping relays collapse, and on timeout returns whatever was already
 * collected rather than `null`. Intended for discovery-style queries
 * (e.g. timeline of all `kind=35128` events) where partial results beat
 * no results.
 *
 * The returned list is sorted by `createdAt` descending — callers expect
 * "newest first" semantics.
 */
suspend fun NostrClient.fetchMany(
    relays: Set<NormalizedRelayUrl>,
    filter: Filter,
    timeoutMs: Long = Defaults.TIMELINE_TIMEOUT_MS,
): List<Event> {
    if (relays.isEmpty()) return emptyList()

    val subId = "fetchMany-${UUID.randomUUID()}"
    // ConcurrentHashMap because Quartz can deliver onEvent callbacks from
    // multiple relay reader threads simultaneously.
    val collected = ConcurrentHashMap<String, Event>()
    val eosedRelays = mutableSetOf<NormalizedRelayUrl>()
    val deferred = CompletableDeferred<Unit>()
    val filtersByRelay: Map<NormalizedRelayUrl, List<Filter>> =
        relays.associateWith { listOf(filter) }

    val listener = object : SubscriptionListener {
        override fun onEvent(
            event: Event,
            isLive: Boolean,
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            collected.putIfAbsent(event.id, event)
        }

        override fun onEose(
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            synchronized(eosedRelays) {
                eosedRelays.add(relay)
                if (eosedRelays.size >= relays.size && !deferred.isCompleted) {
                    deferred.complete(Unit)
                }
            }
        }
    }

    return try {
        subscribe(subId, filtersByRelay, listener)
        // We don't care whether the deferred completed or the timeout fired;
        // either way we return whatever's in `collected`. Calling await() on
        // the deferred inside withTimeoutOrNull suspends the coroutine
        // without busy-waiting.
        withTimeoutOrNull(timeoutMs) { deferred.await() }
        collected.values.sortedByDescending { it.createdAt }
    } finally {
        unsubscribe(subId)
    }
}
