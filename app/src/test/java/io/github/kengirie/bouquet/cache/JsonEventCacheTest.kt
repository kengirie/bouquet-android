package io.github.kengirie.bouquet.cache

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [JsonEventCache]. Each test creates its own temp file via
 * [TemporaryFolder] and a controllable `now` clock so TTL behavior is
 * deterministic.
 *
 * Real I/O on `Dispatchers.IO` is fine here — the file system reads/writes
 * are tiny and the tests stay well under a millisecond each.
 */
class JsonEventCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val pubkey = "a".repeat(64)
    private val ttlMs = 60_000L

    // ── Test event builders (mirrors GatewayTest) ─────────────────────────

    private fun buildRootSite(
        sha: String = "abcdef0123456789".repeat(4),
    ): RootSiteEvent {
        val tags: Array<Array<String>> = arrayOf(
            arrayOf("path", "/index.html", sha),
            arrayOf("server", "https://server.example"),
        )
        return RootSiteEvent(
            id = "0".repeat(64),
            pubKey = pubkey,
            createdAt = 1_700_000_000L,
            tags = tags,
            content = "",
            sig = "0".repeat(128),
        )
    }

    private fun buildRelayList(): AdvertisedRelayListEvent {
        val tags: Array<Array<String>> = arrayOf(
            arrayOf("r", "wss://relay.example"),
        )
        return AdvertisedRelayListEvent(
            id = "1".repeat(64),
            pubKey = pubkey,
            createdAt = 1_700_000_000L,
            tags = tags,
            content = "",
            sig = "0".repeat(128),
        )
    }

    private fun cacheFile(): File = File(tempFolder.root, "event-cache.json")

    // ── 1. Empty file → empty cache ───────────────────────────────────────

    @Test
    fun `nonexistent file results in empty cache and get returns null`() = runTest {
        val cache = JsonEventCache(cacheFile(), ttlMs)
        cache.load()
        assertNull(cache.get("missing"))
        assertNull(cache.get("missing", allowStale = true))
    }

    // ── 2. Set → get round-trip ───────────────────────────────────────────

    @Test
    fun `set then get returns the same event in memory`() = runTest {
        val cache = JsonEventCache(cacheFile(), ttlMs)
        val event = buildRootSite()
        cache.set("manifest:$pubkey", event)
        val got = cache.get("manifest:$pubkey")
        assertNotNull(got)
        assertEquals(event.id, got!!.id)
        assertEquals(event.pubKey, got.pubKey)
    }

    // ── 3. Set → flush → reload ───────────────────────────────────────────

    @Test
    fun `set flush reload preserves event and routes to typed subclass`() = runTest {
        val event = buildRootSite()
        run {
            val cache = JsonEventCache(cacheFile(), ttlMs)
            cache.set("k", event)
            cache.flush()
        }

        val reloaded = JsonEventCache(cacheFile(), ttlMs)
        reloaded.load()
        val got = reloaded.get("k")
        assertNotNull(got)
        assertEquals(event.id, got!!.id)
        // Typed-subclass routing: a RootSiteEvent must come back as a
        // RootSiteEvent (Quartz EventFactory routes by kind).
        assertTrue("expected RootSiteEvent, got ${got::class}", got is RootSiteEvent)
    }

    // ── 4. TTL expiration ─────────────────────────────────────────────────

    @Test
    fun `entry past TTL returns null without allowStale`() = runTest {
        var now = 0L
        val cache = JsonEventCache(cacheFile(), ttlMs, now = { now })
        cache.set("k", buildRootSite())
        now = ttlMs + 1
        assertNull(cache.get("k"))
    }

    // ── 5. TTL with allowStale ────────────────────────────────────────────

    @Test
    fun `entry past TTL returns event with allowStale true`() = runTest {
        var now = 0L
        val cache = JsonEventCache(cacheFile(), ttlMs, now = { now })
        val event = buildRootSite()
        cache.set("k", event)
        now = ttlMs + 1_000
        val got = cache.get("k", allowStale = true)
        assertNotNull(got)
        assertEquals(event.id, got!!.id)
    }

    // ── 6. Missing entry ──────────────────────────────────────────────────

    @Test
    fun `missing key returns null in both modes`() = runTest {
        val cache = JsonEventCache(cacheFile(), ttlMs)
        assertNull(cache.get("nope"))
        assertNull(cache.get("nope", allowStale = true))
    }

    // ── 7. Idempotent load ────────────────────────────────────────────────

    @Test
    fun `load is idempotent`() = runTest {
        val event = buildRootSite()
        run {
            val cache = JsonEventCache(cacheFile(), ttlMs)
            cache.set("k", event)
            cache.flush()
        }

        val cache = JsonEventCache(cacheFile(), ttlMs)
        cache.load()
        cache.load()
        cache.load()
        assertNotNull(cache.get("k"))
    }

    // ── 8. Close prevents set ─────────────────────────────────────────────

    @Test
    fun `set after close throws IllegalStateException`() = runTest {
        val cache = JsonEventCache(cacheFile(), ttlMs)
        cache.close()
        try {
            cache.set("k", buildRootSite())
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    // ── 9. Close flushes pending writes ───────────────────────────────────

    @Test
    fun `close flushes pending writes so they survive on reload`() = runTest {
        val event = buildRootSite()
        val cache = JsonEventCache(cacheFile(), ttlMs, flushDebounceMs = 5_000L)
        cache.set("k", event)
        // No explicit flush — close() must flush.
        cache.close()

        val reloaded = JsonEventCache(cacheFile(), ttlMs)
        reloaded.load()
        val got = reloaded.get("k")
        assertNotNull(got)
        assertEquals(event.id, got!!.id)
    }

    // ── 10. Stray .tmp file is ignored ────────────────────────────────────

    @Test
    fun `stray tmp file does not affect load`() = runTest {
        val event = buildRootSite()
        run {
            val cache = JsonEventCache(cacheFile(), ttlMs)
            cache.set("k", event)
            cache.flush()
        }
        // Simulate a half-written tmp file from a crashed earlier run.
        File(tempFolder.root, "event-cache.json.tmp").writeText("partial garbage", Charsets.UTF_8)

        val reloaded = JsonEventCache(cacheFile(), ttlMs)
        reloaded.load()
        // Real file is still readable.
        assertNotNull(reloaded.get("k"))
    }

    // ── 11. Version mismatch → empty cache, no crash ──────────────────────

    @Test
    fun `version mismatch starts with empty cache`() = runTest {
        cacheFile().writeText(
            """{"version":999,"entries":[]}""",
            Charsets.UTF_8,
        )
        val cache = JsonEventCache(cacheFile(), ttlMs)
        cache.load()
        assertNull(cache.get("anything"))
    }

    // ── 12. Garbage file → empty cache, no crash ──────────────────────────

    @Test
    fun `garbage file starts with empty cache without crashing`() = runTest {
        cacheFile().writeText("not json at all", Charsets.UTF_8)
        val cache = JsonEventCache(cacheFile(), ttlMs)
        cache.load()
        assertNull(cache.get("anything"))
        // And we can still set/flush after a failed load.
        cache.set("k", buildRootSite())
        cache.flush()
    }

    // ── 13. Mixed event types round-trip ──────────────────────────────────

    @Test
    fun `multiple events of different types round-trip independently`() = runTest {
        val rootEvent: Event = buildRootSite()
        val relayEvent: Event = buildRelayList()
        run {
            val cache = JsonEventCache(cacheFile(), ttlMs)
            cache.set("manifest:$pubkey", rootEvent)
            cache.set("relay:$pubkey", relayEvent)
            cache.flush()
        }

        val reloaded = JsonEventCache(cacheFile(), ttlMs)
        reloaded.load()
        val gotRoot = reloaded.get("manifest:$pubkey")
        val gotRelay = reloaded.get("relay:$pubkey")
        assertTrue("manifest must come back as RootSiteEvent", gotRoot is RootSiteEvent)
        assertTrue(
            "relay list must come back as AdvertisedRelayListEvent",
            gotRelay is AdvertisedRelayListEvent,
        )
    }
}
