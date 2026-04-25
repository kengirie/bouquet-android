package io.github.kengirie.bouquet.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import io.github.kengirie.bouquet.config.Defaults
import io.github.kengirie.bouquet.core.SiteAddress

/**
 * Result of resolving a user's NIP-65 relay list. Keeps track of whether
 * the relays came from the user's published kind=10002 or whether we had
 * to fall back to the default lookup relays so the UI can label the
 * source.
 */
data class WriteRelaysResult(
    val relays: List<NormalizedRelayUrl>,
    val fromKind10002: Boolean,
)

/**
 * Fetch the user's NIP-65 relay-list event (kind 10002) and return the
 * write relays. Falls back to [defaultLookupRelays] when no event was
 * found or the event has no `write` entries.
 */
suspend fun NostrClient.fetchWriteRelays(
    pubkey: String,
    lookupRelays: Set<NormalizedRelayUrl>,
    defaultLookupRelays: List<NormalizedRelayUrl> = Defaults.DEFAULT_LOOKUP_RELAYS,
): WriteRelaysResult {
    val filter = Filter(
        kinds = listOf(AdvertisedRelayListEvent.KIND),
        authors = listOf(pubkey),
    )

    val event = fetchOne(lookupRelays, filter) ?: return WriteRelaysResult(
        relays = defaultLookupRelays,
        fromKind10002 = false,
    )

    // EventFactory.create routes by kind during deserialization, so we
    // expect the typed subclass straight off the wire (see EventDeserializer
    // in Quartz). `as?` is purely defensive.
    val typed = event as? AdvertisedRelayListEvent
        ?: return WriteRelaysResult(defaultLookupRelays, fromKind10002 = false)

    val writes = typed.writeRelaysNorm()
    return if (writes.isNullOrEmpty()) {
        WriteRelaysResult(defaultLookupRelays, fromKind10002 = false)
    } else {
        WriteRelaysResult(writes, fromKind10002 = true)
    }
}

/**
 * Fetch the site manifest event for [address]:
 *  - [SiteAddress.Root]  → kind 15128 (RootSiteEvent)
 *  - [SiteAddress.Named] → kind 35128 (NamedSiteEvent), filtered by `#d`
 *
 * Returns the typed event subclass (a [RootSiteEvent] / [NamedSiteEvent])
 * or `null` if no matching event was found within the timeout.
 */
suspend fun NostrClient.fetchManifest(
    address: SiteAddress,
    relays: Set<NormalizedRelayUrl>,
): Event? {
    val filter = when (address) {
        is SiteAddress.Root -> Filter(
            kinds = listOf(RootSiteEvent.KIND),
            authors = listOf(address.pubkey),
        )

        is SiteAddress.Named -> Filter(
            kinds = listOf(NamedSiteEvent.KIND),
            authors = listOf(address.pubkey),
            tags = mapOf("d" to listOf(address.identifier)),
        )
    }

    return fetchOne(relays, filter)
}
