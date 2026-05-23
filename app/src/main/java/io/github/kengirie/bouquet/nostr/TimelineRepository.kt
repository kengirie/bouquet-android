package io.github.kengirie.bouquet.nostr

import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import io.github.kengirie.bouquet.config.Defaults
import io.github.kengirie.bouquet.ui.timeline.AuthorProfile
import io.github.kengirie.bouquet.ui.timeline.TimelineEntry
import io.github.kengirie.bouquet.ui.timeline.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads nsite manifest events from a set of discovery relays and stitches
 * them together with author metadata (kind 0) for display.
 *
 * Two-stage fetch:
 *   1. Pull kinds 35128 + 15128 (no `authors` filter) → maps to
 *      [TimelineEntry] sealed class, dropping unknown kinds.
 *   2. From the unique pubkey set, pull kind 0 metadata in a single
 *      multi-author query and merge into [TimelineItem.author].
 *
 * Phase T2 keeps step (2) optional and gracefully no-ops on empty/failed
 * metadata so the timeline still renders without author chrome.
 */
interface TimelineRepository {
    suspend fun loadInitial(): List<TimelineItem>
}

class DefaultTimelineRepository(
    private val client: NostrClient,
    private val relays: Set<NormalizedRelayUrl> =
        Defaults.DEFAULT_TIMELINE_RELAYS.toSet(),
    private val pageLimit: Int = Defaults.TIMELINE_PAGE_LIMIT,
    private val timelineTimeoutMs: Long = Defaults.TIMELINE_TIMEOUT_MS,
    private val metadataTimeoutMs: Long = Defaults.TIMELINE_METADATA_TIMEOUT_MS,
) : TimelineRepository {

    override suspend fun loadInitial(): List<TimelineItem> = withContext(Dispatchers.IO) {
        val siteEvents = client.fetchMany(
            relays = relays,
            filter = Filter(
                kinds = listOf(NamedSiteEvent.KIND, RootSiteEvent.KIND),
                limit = pageLimit,
            ),
            timeoutMs = timelineTimeoutMs,
        )

        val entries = siteEvents.mapNotNull { ev ->
            when (ev) {
                is NamedSiteEvent -> TimelineEntry.Named(ev)
                is RootSiteEvent -> TimelineEntry.Root(ev)
                // Unknown / unexpected kind sneaks through if a relay
                // doesn't honor the filter — drop silently.
                else -> null
            }
        }
        if (entries.isEmpty()) return@withContext emptyList()

        val pubkeys = entries.map { it.pubkey }.distinct()
        val profiles = fetchProfiles(pubkeys)

        entries.map { TimelineItem(entry = it, author = profiles[it.pubkey]) }
    }

    /**
     * Best-effort multi-author metadata fetch. Returns an empty map on any
     * error so timeline rendering never gets blocked by profile lookup.
     * Filter by `authors` only — we don't pin `since` because metadata is
     * mutable and we always want the latest the relay knows about.
     */
    private suspend fun fetchProfiles(pubkeys: List<String>): Map<String, AuthorProfile> {
        if (pubkeys.isEmpty()) return emptyMap()
        return try {
            val metaEvents = client.fetchMany(
                relays = relays,
                filter = Filter(
                    kinds = listOf(MetadataEvent.KIND),
                    authors = pubkeys,
                ),
                timeoutMs = metadataTimeoutMs,
            )
            metaEvents
                .filterIsInstance<MetadataEvent>()
                // Replaceable events: pick the latest per author. fetchMany
                // dedupes by id, but a pubkey can still have multiple
                // historical versions delivered from different relays.
                .groupBy { it.pubKey }
                .mapValues { (_, evs) -> evs.maxBy { it.createdAt } }
                .mapValues { (pk, ev) -> ev.toProfile(pk) }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/**
 * Pull display name + picture out of a kind 0 event. Tries the structured
 * [UserMetadata] first (handles the standard JSON shape) and falls back to
 * an empty profile when content is unparseable rather than throwing.
 */
private fun MetadataEvent.toProfile(pubkey: String): AuthorProfile {
    val meta = contactMetaData()
    return AuthorProfile(
        pubkey = pubkey,
        displayName = meta?.bestName(),
        pictureUrl = meta?.picture,
    )
}
