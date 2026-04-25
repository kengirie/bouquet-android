package io.github.kengirie.bouquet.cache

import com.vitorpamplona.quartz.nip01Core.core.Event
import io.github.kengirie.bouquet.core.EventCacheAdapter
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8 in-memory stand-in for the Phase 9 [JsonEventCache]. Entries are
 * considered "fresh" while their age is within [ttlMs]; older entries are
 * still returned when callers pass `allowStale = true`, mirroring the
 * desktop cache contract.
 */
class InMemoryEventCacheAdapter(
    private val ttlMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() },
) : EventCacheAdapter {
    private data class Entry(val event: Event, val savedAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    override suspend fun get(key: String, allowStale: Boolean): Event? {
        val entry = entries[key] ?: return null
        val age = now() - entry.savedAt
        return if (age <= ttlMs || allowStale) entry.event else null
    }

    override suspend fun set(key: String, event: Event) {
        entries[key] = Entry(event, now())
    }
}
