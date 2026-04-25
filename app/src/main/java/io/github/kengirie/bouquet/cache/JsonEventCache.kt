package io.github.kengirie.bouquet.cache

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.vitorpamplona.quartz.nip01Core.core.Event
import io.github.kengirie.bouquet.core.EventCacheAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent event cache backed by a single JSON file.
 *
 * Direct port of `bouquet-desktop/electron/storage/event-cache.ts`:
 * - In-memory `ConcurrentHashMap<String, Entry>` mirrors the file contents.
 * - Writes are debounced (default 500ms) and flushed atomically via
 *   temp-file + rename (`Files.move(..., ATOMIC_MOVE)` with a non-atomic
 *   fallback if the underlying filesystem doesn't support it).
 * - TTL is enforced on `get`: expired entries return `null` by default but
 *   are preserved in memory so that `get(..., allowStale = true)` can still
 *   return them as a network-failure fallback.
 *
 * Events are stored as embedded JSON strings inside the outer container so
 * the container serialization is decoupled from Quartz's Jackson setup.
 * `Event.fromJson` routes back to the typed subclass via Quartz's
 * `EventFactory`, so a `RootSiteEvent` written with [set] reads back as a
 * `RootSiteEvent` (verified in [JsonEventCacheTest]).
 */
class JsonEventCache(
    private val file: File,
    private val ttlMs: Long,
    private val flushDebounceMs: Long = 500,
    private val now: () -> Long = { System.currentTimeMillis() },
) : EventCacheAdapter {
    private data class Entry(val event: Event, val savedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val flushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Guard for `loadDeferred` and `flushDeferred` membership. */
    private val stateLock = Mutex()

    @Volatile private var loaded = false
    @Volatile private var isClosed = false
    @Volatile private var loadDeferred: Deferred<Unit>? = null

    /** Pending debounce timer (cancellable). Null while no flush is queued. */
    @Volatile private var flushTimer: Job? = null

    /** In-flight flush — overlapping callers all await the same Deferred. */
    @Volatile private var flushDeferred: Deferred<Unit>? = null

    /**
     * Read the backing file into memory. Idempotent: a second call is a
     * no-op, and concurrent calls share a single in-flight Deferred.
     *
     * If the file does not exist, parsing fails, or the version mismatches,
     * the cache starts empty (a warning is logged for non-ENOENT errors).
     */
    suspend fun load() {
        if (loaded) return
        val deferred = stateLock.withLock {
            if (loaded) return
            loadDeferred ?: flushScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                doLoad()
            }.also { loadDeferred = it }
        }
        deferred.await()
    }

    private suspend fun doLoad() = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                // First run: no file yet, start empty. Not a warning.
                return@withContext
            }
            val raw = file.readText(Charsets.UTF_8)
            val parsed = parseFile(raw)
            if (parsed == null) {
                System.err.println(
                    "[JsonEventCache] unexpected file format at ${file.path}, starting with empty cache",
                )
            } else {
                for ((key, entry) in parsed) {
                    cache[key] = entry
                }
            }
        } catch (e: Exception) {
            System.err.println(
                "[JsonEventCache] failed to load ${file.path}: ${e.message} - starting with empty cache",
            )
        } finally {
            loaded = true
        }
    }

    override suspend fun get(key: String, allowStale: Boolean): Event? {
        load()
        val entry = cache[key] ?: return null
        val age = now() - entry.savedAt
        if (age > ttlMs && !allowStale) {
            // Keep entry in memory so a future allowStale call can still
            // return it as a fallback on network failure.
            return null
        }
        return entry.event
    }

    override suspend fun set(key: String, event: Event) {
        if (isClosed) {
            throw IllegalStateException("JsonEventCache is closed")
        }
        load()
        cache[key] = Entry(event, now())
        scheduleFlush()
    }

    /**
     * Force an immediate write. Cancels any pending debounced flush. If a
     * flush is already mid-write, returns that in-flight Deferred so callers
     * never observe overlapping writes.
     */
    suspend fun flush() {
        // Cancel any pending debounced flush — we're flushing now.
        flushTimer?.cancel()
        flushTimer = null

        val deferred = stateLock.withLock {
            flushDeferred?.let { return@withLock it }

            // Snapshot synchronously inside the lock so any subsequent set
            // safely lands in the next flush.
            val snapshot = HashMap(cache)
            val serialized = serializeFile(snapshot)

            val d = flushScope.async {
                try {
                    doFlush(serialized)
                } finally {
                    stateLock.withLock { flushDeferred = null }
                }
            }
            flushDeferred = d
            d
        }
        deferred.await()
    }

    private suspend fun doFlush(serialized: String): Unit = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(serialized, Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Fall back to non-atomic rename.
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Unit
    }

    /**
     * Mark the cache closed (further `set` will throw), cancel any pending
     * debounced flush, and synchronously flush the current state to disk.
     */
    suspend fun close() {
        isClosed = true
        val timer = flushTimer
        flushTimer = null
        timer?.cancel()
        // Always flush — covers both the "timer pending" and "set since last
        // flush but no timer" edge cases. flush() coalesces with any
        // in-flight write.
        try {
            flush()
        } finally {
            flushScope.coroutineContext[Job]?.cancel()
        }
    }

    private fun scheduleFlush() {
        if (flushTimer != null) return
        flushTimer = flushScope.launch {
            try {
                delay(flushDebounceMs)
            } catch (_: Exception) {
                return@launch
            }
            flushTimer = null
            try {
                flush()
            } catch (_: Exception) {
                // already logged in doFlush
            }
        }
    }

    // ── Container JSON ─────────────────────────────────────────────────────

    private fun serializeFile(snapshot: Map<String, Entry>): String {
        // The container shape is:
        //   { "version": 1, "entries": [ ["key", { "event": "<json>", "savedAt": 123 }], ... ] }
        // We delegate to Jackson for correct string escaping, but build the
        // tree manually so we never round-trip the embedded event JSON
        // through Jackson's `JsonNode` (the embedded JSON was produced by
        // Quartz's customized mapper and must be preserved verbatim).
        val mapper = MAPPER
        val root = mapper.createObjectNode()
        root.put("version", FILE_FORMAT_VERSION)
        val entriesArr = root.putArray("entries")
        for ((key, entry) in snapshot) {
            val pair = entriesArr.addArray()
            pair.add(key)
            val obj = pair.addObject()
            obj.put("event", entry.event.toJson())
            obj.put("savedAt", entry.savedAt)
        }
        return mapper.writeValueAsString(root)
    }

    private fun parseFile(raw: String): Map<String, Entry>? {
        val mapper = MAPPER
        val parser: JsonParser = try {
            mapper.factory.createParser(raw)
        } catch (_: Exception) {
            return null
        }
        parser.use { p ->
            return try {
                if (p.nextToken() != JsonToken.START_OBJECT) return null
                var version: Int? = null
                var entriesNode: com.fasterxml.jackson.databind.JsonNode? = null
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    when (p.currentName()) {
                        "version" -> {
                            p.nextToken()
                            version = p.intValue
                        }
                        "entries" -> {
                            p.nextToken()
                            entriesNode = mapper.readTree<com.fasterxml.jackson.databind.JsonNode>(p)
                        }
                        else -> {
                            p.nextToken()
                            p.skipChildren()
                        }
                    }
                }
                if (version != FILE_FORMAT_VERSION) return null
                if (entriesNode == null || !entriesNode.isArray) return null

                val out = LinkedHashMap<String, Entry>(entriesNode.size())
                for (item in entriesNode) {
                    if (!item.isArray || item.size() != 2) continue
                    val keyNode = item.get(0)
                    val entryNode = item.get(1)
                    if (!keyNode.isTextual || !entryNode.isObject) continue
                    val eventNode = entryNode.get("event") ?: continue
                    val savedAtNode = entryNode.get("savedAt") ?: continue
                    if (!savedAtNode.isNumber) continue
                    val eventJson = when {
                        eventNode.isTextual -> eventNode.asText()
                        eventNode.isObject -> mapper.writeValueAsString(eventNode)
                        else -> continue
                    }
                    val event = try {
                        Event.fromJson(eventJson)
                    } catch (_: Exception) {
                        continue
                    }
                    out[keyNode.asText()] = Entry(event, savedAtNode.asLong())
                }
                out
            } catch (_: Exception) {
                null
            }
        }
    }


    companion object {
        private const val FILE_FORMAT_VERSION = 1
        private val MAPPER: ObjectMapper = ObjectMapper()
    }
}
