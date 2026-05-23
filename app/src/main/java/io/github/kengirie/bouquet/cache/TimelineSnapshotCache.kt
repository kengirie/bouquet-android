package io.github.kengirie.bouquet.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import io.github.kengirie.bouquet.ui.timeline.AuthorProfile
import io.github.kengirie.bouquet.ui.timeline.TimelineEntry
import io.github.kengirie.bouquet.ui.timeline.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Best-effort snapshot of the most-recent Timeline page so cold launches
 * can render content immediately without waiting for relays.
 *
 * Unlike [JsonEventCache], this cache has no TTL, no in-memory mirror,
 * and no debounced flush — the timeline screen calls [save] exactly once
 * per successful refresh and [load] exactly once per cold start. Failures
 * (parse errors, missing file, IO) silently degrade to "no snapshot
 * available" so a corrupted file never blocks startup.
 *
 * Storage format mirrors [JsonEventCache]: events are embedded as their
 * canonical JSON string and reconstructed via [Event.fromJson], which
 * routes them back to the typed subclass.
 */
class TimelineSnapshotCache(private val file: File) {

    suspend fun save(items: List<TimelineItem>): Unit = withContext(Dispatchers.IO) {
        try {
            val mapper = MAPPER
            val root = mapper.createObjectNode()
            root.put("version", FILE_FORMAT_VERSION)
            val arr = root.putArray("items")
            for (item in items) {
                val event = when (val e = item.entry) {
                    is TimelineEntry.Named -> e.event
                    is TimelineEntry.Root -> e.event
                }
                val node = arr.addObject()
                node.put("event", event.toJson())
                item.author?.let { author ->
                    val authorNode = node.putObject("author")
                    authorNode.put("pubkey", author.pubkey)
                    author.displayName?.let { authorNode.put("displayName", it) }
                    author.pictureUrl?.let { authorNode.put("pictureUrl", it) }
                }
            }

            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(mapper.writeValueAsString(root), Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Exception) {
            // Snapshot is purely an optimization — a write failure must
            // not surface as a UI error. Log to stderr to stay consistent
            // with JsonEventCache.
            System.err.println("[TimelineSnapshotCache] save failed: ${e.message}")
        }
    }

    suspend fun load(): List<TimelineItem> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val mapper = MAPPER
            val root = mapper.readTree(file)
            if (root["version"]?.asInt() != FILE_FORMAT_VERSION) return@withContext emptyList()
            val items = root["items"] ?: return@withContext emptyList()
            if (!items.isArray) return@withContext emptyList()
            items.mapNotNull { node ->
                val eventJson = node["event"]?.asText() ?: return@mapNotNull null
                val event = try {
                    Event.fromJson(eventJson)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                val entry: TimelineEntry = when (event) {
                    is NamedSiteEvent -> TimelineEntry.Named(event)
                    is RootSiteEvent -> TimelineEntry.Root(event)
                    else -> return@mapNotNull null
                }
                val authorNode = node["author"]
                val author = authorNode?.let {
                    AuthorProfile(
                        pubkey = it["pubkey"]?.asText() ?: entry.pubkey,
                        displayName = it["displayName"]?.asText(),
                        pictureUrl = it["pictureUrl"]?.asText(),
                    )
                }
                TimelineItem(entry = entry, author = author)
            }
        } catch (e: Exception) {
            System.err.println("[TimelineSnapshotCache] load failed: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val FILE_FORMAT_VERSION = 1
        private val MAPPER: ObjectMapper = ObjectMapper()
    }
}
