package io.github.kengirie.bouquet.ui.timeline

import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import io.github.kengirie.bouquet.core.SiteAddress
import io.github.kengirie.bouquet.core.encodePubkeyB36

/**
 * UI-side projection of a single nsite manifest event. Holds just enough
 * structure for the Timeline list to render and to hand the address back
 * to the existing Home resolve pipeline when tapped.
 *
 *   - [Named]  → NIP-5A `kind=35128`, addressable, multiple per pubkey
 *   - [Root]   → NIP-5A `kind=15128`, replaceable, one per pubkey
 *
 * Other kinds are filtered out at the repository layer (see
 * `DefaultTimelineRepository`) so this sealed class only enumerates the
 * forms we actually render.
 */
sealed class TimelineEntry {
    abstract val pubkey: String
    abstract val createdAt: Long
    abstract val eventId: String
    /** Optional human-readable site title from the manifest tags. */
    abstract val title: String?
    /**
     * String to hand to the existing resolve pipeline (HomeViewModel's
     * `DefaultSiteResolver` plus `Gateway.resolveSiteResource`). The
     * gateway re-decodes this so we mirror the on-the-wire forms the
     * Home input accepts.
     */
    abstract val addressSegment: String

    data class Named(val event: NamedSiteEvent) : TimelineEntry() {
        override val pubkey: String = event.pubKey
        override val createdAt: Long = event.createdAt
        override val eventId: String = event.id
        override val title: String? = event.title()
        val identifier: String = event.identifier()
        // NIP-5A canonical subdomain label: <pubkeyB36 50 chars><dTag>.
        // encodePubkeyB36 returns null only when the pubkey hex is malformed,
        // which should never happen for an event already verified by Quartz —
        // but in case it does, fall back to the raw pubkey so the resolve
        // pipeline can surface a proper error rather than crashing here.
        override val addressSegment: String =
            (encodePubkeyB36(pubkey) ?: pubkey) + identifier
    }

    data class Root(val event: RootSiteEvent) : TimelineEntry() {
        override val pubkey: String = event.pubKey
        override val createdAt: Long = event.createdAt
        override val eventId: String = event.id
        override val title: String? = event.title()
        override val addressSegment: String = NPub.create(pubkey)
    }

    /**
     * Build the internal [SiteAddress] sealed type directly from the
     * already-decoded event fields. Skips the bech32/base36 round-trip
     * that `SiteAddress.parse([addressSegment])` would otherwise do — the
     * raw pubkey hex and (optional) identifier are right here.
     */
    fun toSiteAddress(): SiteAddress = when (this) {
        is Named -> SiteAddress.Named(pubkey = pubkey, identifier = identifier)
        is Root -> SiteAddress.Root(pubkey = pubkey)
    }
}

/**
 * Author display extracted from a kind=0 metadata event. Both fields are
 * nullable to model "no metadata published" vs "metadata fetched but the
 * field is empty" without a third state.
 */
data class AuthorProfile(
    val pubkey: String,
    val displayName: String?,
    val pictureUrl: String?,
)

/**
 * Row model consumed by the LazyColumn: a manifest entry plus the
 * already-resolved author profile (or null if we never fetched / found one).
 */
data class TimelineItem(
    val entry: TimelineEntry,
    val author: AuthorProfile?,
)
