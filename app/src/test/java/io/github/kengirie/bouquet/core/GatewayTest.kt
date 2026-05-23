package io.github.kengirie.bouquet.core

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import io.github.kengirie.bouquet.config.Defaults
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [resolveSiteResource] and the cache-key helpers.
 *
 * Uses in-memory [FakeEventCache] / [FakeBlobCache] adapters and lambda
 * fetchers so the test exercises the full happy/error paths without any
 * IO. The `tags: Array<Array<String>>` plumbing is intentionally hand-rolled
 * (see [buildRelayListEvent] / [buildRootSiteEvent] / [buildNamedSiteEvent] /
 * [buildBlossomListEvent]) — each typed event subclass is constructed
 * directly rather than going through a signer.
 */
class GatewayTest {

    private val pubkey = "a".repeat(64)
    private val sha256Index = "abcdef0123456789".repeat(4)
    private val sha256NotFound = "fedcba9876543210".repeat(4)

    // ── manifestKey helper micro-tests ─────────────────────────────────────

    @Test
    fun `manifestKey without identifier omits suffix`() {
        assertEquals("manifest:$pubkey", manifestKey(pubkey, null))
    }

    @Test
    fun `manifestKey with identifier appends colon and id`() {
        assertEquals("manifest:$pubkey:my-blog", manifestKey(pubkey, "my-blog"))
    }

    // ── 1. Invalid address → 400 ──────────────────────────────────────────

    @Test
    fun `invalid address segment throws GatewayError 400`() = runTest {
        val eventCache = FakeEventCache()
        val blobCache = FakeBlobCache()
        var fetchRelayListCalls = 0
        var fetchManifestCalls = 0
        var fetchBlossomListCalls = 0
        var fetchBlobCalls = 0

        val deps = GatewayDeps(
            eventCache = eventCache,
            blobCache = blobCache,
            fetchRelayList = { _, _ -> fetchRelayListCalls++; null },
            fetchManifest = { _, _ -> fetchManifestCalls++; null },
            fetchBlossomList = { _, _ -> fetchBlossomListCalls++; null },
            fetchBlob = { _, _ -> fetchBlobCalls++; null },
        )

        try {
            resolveSiteResource("garbage", "/", deps)
            fail("expected GatewayError")
        } catch (e: GatewayError) {
            assertEquals(400, e.status)
            assertEquals("Bad Request", e.statusText)
        }

        // No cache reads / network calls.
        assertTrue(eventCache.gets.isEmpty())
        assertTrue(eventCache.sets.isEmpty())
        assertTrue(blobCache.gets.isEmpty())
        assertEquals(0, fetchRelayListCalls)
        assertEquals(0, fetchManifestCalls)
        assertEquals(0, fetchBlossomListCalls)
        assertEquals(0, fetchBlobCalls)
    }

    // ── 2. No manifest → 404 ──────────────────────────────────────────────

    @Test
    fun `manifest event missing throws GatewayError 404 site not found`() = runTest {
        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> null },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> null },
        )

        try {
            resolveSiteResource(npubFor(pubkey), "/", deps)
            fail("expected GatewayError")
        } catch (e: GatewayError) {
            assertEquals(404, e.status)
            assertEquals("Not Found", e.statusText)
            assertTrue("expected 'Site not found' in: ${e.message}", e.message!!.contains("Site not found"))
        }
    }

    // ── 3. Path not found (no /404.html) → 404 ────────────────────────────

    @Test
    fun `path not found without 404 page throws GatewayError 404`() = runTest {
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = emptyList(),
        )

        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> null },
        )

        try {
            resolveSiteResource(npubFor(pubkey), "/missing", deps)
            fail("expected GatewayError")
        } catch (e: GatewayError) {
            assertEquals(404, e.status)
            assertTrue("expected 'Path not found' in: ${e.message}", e.message!!.contains("Path not found"))
        }
    }

    // ── 4. Falls back to /404.html (is404 = true) ─────────────────────────

    @Test
    fun `path missing falls back to 404 html with is404 true`() = runTest {
        val notFoundBytes = "not-found-page".toByteArray()
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/404.html" to sha256NotFound),
            servers = listOf("https://server.example"),
        )

        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { sha, _ ->
                assertEquals(sha256NotFound, sha)
                BlobFetchResult(notFoundBytes, "text/html; charset=utf-8")
            },
        )

        val result = resolveSiteResource(npubFor(pubkey), "/missing", deps)

        assertEquals(404, result.status)
        assertTrue(result.is404)
        assertEquals("/404.html", result.resolvedPath)
        assertArrayEquals(notFoundBytes, result.body)
        assertEquals("text/html; charset=utf-8", result.contentType)
    }

    // ── 5. Happy path (npub) ──────────────────────────────────────────────

    @Test
    fun `happy path npub populates caches and returns 200`() = runTest {
        val indexBytes = "<html>hi</html>".toByteArray()
        val relayList = buildRelayListEvent(
            pubkey,
            relayMarkers = listOf("wss://relay.example" to null),
        )
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://manifest.server"),
        )
        val blossomList = buildBlossomListEvent(
            pubkey,
            servers = listOf("https://user.server"),
        )

        val eventCache = FakeEventCache()
        val blobCache = FakeBlobCache()
        var blobServersUsed: List<String>? = null

        val deps = GatewayDeps(
            eventCache = eventCache,
            blobCache = blobCache,
            fetchRelayList = { _, _ -> relayList },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> blossomList },
            fetchBlob = { sha, servers ->
                assertEquals(sha256Index, sha)
                blobServersUsed = servers
                BlobFetchResult(indexBytes, null) // no content-type → MIME map fires
            },
        )

        val result = resolveSiteResource(npubFor(pubkey), "/", deps)

        assertEquals(200, result.status)
        assertFalse(result.is404)
        assertEquals("/index.html", result.resolvedPath)
        assertArrayEquals(indexBytes, result.body)
        // Blossom returned no content-type → MIME map fires for `.html`.
        assertEquals("text/html; charset=utf-8", result.contentType)

        // Caches populated.
        assertTrue(eventCache.sets.contains("relay:$pubkey"))
        assertTrue(eventCache.sets.contains("manifest:$pubkey"))
        assertTrue(eventCache.sets.contains("blossom:$pubkey"))
        assertTrue(blobCache.sets.contains(sha256Index))
        // Cached blob has the resolved content-type stored.
        assertEquals("text/html; charset=utf-8", blobCache.store[sha256Index]!!.contentType)

        // Server list = manifest + user (no built-in defaults; NIP-5A
        // requires 404 if both sources are empty, so the gateway never
        // injects fallback servers).
        assertNotNull(blobServersUsed)
        assertEquals(
            listOf("https://manifest.server", "https://user.server"),
            blobServersUsed,
        )
    }

    // ── 6. Happy path (naddr / named site) ────────────────────────────────

    @Test
    fun `happy path naddr uses identifier in manifest cache key`() = runTest {
        val identifier = "my-blog"
        val indexBytes = "<html>blog</html>".toByteArray()

        val manifest = buildNamedSiteEvent(
            pubkey,
            identifier = identifier,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://server.example"),
        )

        val eventCache = FakeEventCache()
        val blobCache = FakeBlobCache()

        val deps = GatewayDeps(
            eventCache = eventCache,
            blobCache = blobCache,
            fetchRelayList = { _, _ -> null }, // no relay list event → defaults
            fetchManifest = { addr, _ ->
                assertTrue(addr is SiteAddress.Named)
                assertEquals(identifier, (addr as SiteAddress.Named).identifier)
                manifest
            },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> BlobFetchResult(indexBytes, "text/html; charset=utf-8") },
        )

        val canonicalLabel = "${encodePubkeyB36(pubkey)!!}$identifier"
        val result = resolveSiteResource(canonicalLabel, "/", deps)

        assertEquals(200, result.status)
        assertEquals("/index.html", result.resolvedPath)
        // Cache key uses the identifier.
        assertTrue(
            "expected manifest:$pubkey:$identifier in sets ${eventCache.sets}",
            eventCache.sets.contains("manifest:$pubkey:$identifier"),
        )
    }

    // ── 7. Blob cache hit short-circuits network ──────────────────────────

    @Test
    fun `blob cache hit skips fetchBlob`() = runTest {
        val cachedBytes = "cached-bytes".toByteArray()
        val cachedContentType = "text/html; charset=utf-8"

        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://server.example"),
        )

        val eventCache = FakeEventCache()
        val blobCache = FakeBlobCache().apply {
            store[sha256Index] = CachedBlob(cachedBytes, cachedContentType)
        }
        var fetchBlobCalls = 0

        val deps = GatewayDeps(
            eventCache = eventCache,
            blobCache = blobCache,
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> fetchBlobCalls++; null },
        )

        val result = resolveSiteResource(npubFor(pubkey), "/", deps)

        assertEquals(200, result.status)
        assertArrayEquals(cachedBytes, result.body)
        assertEquals(cachedContentType, result.contentType)
        // Critical: fetchBlob never called.
        assertEquals(0, fetchBlobCalls)
        // Blob cache wasn't re-set either.
        assertFalse(blobCache.sets.contains(sha256Index))
    }

    // ── 8. Octet-stream is overridden by MIME map ─────────────────────────

    @Test
    fun `octet-stream blossom content type is overridden by mime map`() = runTest {
        val indexBytes = "<html>x</html>".toByteArray()
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://server.example"),
        )

        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> BlobFetchResult(indexBytes, "application/octet-stream") },
        )

        val result = resolveSiteResource(npubFor(pubkey), "/", deps)

        assertEquals("text/html; charset=utf-8", result.contentType)
    }

    // ── 9. Specific blossom content type wins ─────────────────────────────

    @Test
    fun `specific blossom content type is preferred over mime map`() = runTest {
        val pngBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/logo.png" to sha256Index),
            servers = listOf("https://server.example"),
        )

        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> BlobFetchResult(pngBytes, "image/png") },
        )

        val result = resolveSiteResource(npubFor(pubkey), "/logo.png", deps)

        assertEquals("image/png", result.contentType)
    }

    // ── 10. Stale event cache fallback ────────────────────────────────────

    @Test
    fun `stale relay list cache is used when network fetch fails`() = runTest {
        val staleRelayList = buildRelayListEvent(
            pubkey,
            relayMarkers = listOf("wss://stale.relay.example" to null),
        )
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://server.example"),
        )

        val eventCache = FakeEventCache().apply {
            // Pre-populate the stale layer only — no fresh hit.
            stale["relay:$pubkey"] = staleRelayList
        }

        val deps = GatewayDeps(
            eventCache = eventCache,
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null }, // network fails
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> BlobFetchResult("x".toByteArray(), null) },
        )

        val result = resolveSiteResource(npubFor(pubkey), "/", deps)

        assertEquals(200, result.status)
        // Both fresh and stale gets happened.
        val relayKeyGets = eventCache.gets.filter { it.first == "relay:$pubkey" }
        assertTrue(
            "expected fresh + stale relay list gets, got: $relayKeyGets",
            relayKeyGets.contains("relay:$pubkey" to false) &&
                relayKeyGets.contains("relay:$pubkey" to true),
        )
    }

    // ── 11. No relay list anywhere → defaults are used for manifest fetch ─

    @Test
    fun `defaults are used as relays when no relay list event exists`() = runTest {
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://server.example"),
        )
        var manifestRelays: Set<NormalizedRelayUrl>? = null

        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, relays ->
                manifestRelays = relays
                manifest
            },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> BlobFetchResult("x".toByteArray(), null) },
        )

        val result = resolveSiteResource(npubFor(pubkey), "/", deps)
        assertEquals(200, result.status)

        // Default lookup relays are passed to fetchManifest (no relay hints
        // for a plain npub).
        assertNotNull(manifestRelays)
        for (default in Defaults.DEFAULT_LOOKUP_RELAYS) {
            assertTrue(
                "expected default relay $default in: $manifestRelays",
                manifestRelays!!.contains(default),
            )
        }
    }

    // ── 12. All Blossom servers fail → 502 ────────────────────────────────

    @Test
    fun `all blossom servers fail throws GatewayError 502`() = runTest {
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://server.example"),
        )

        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> null }, // all servers fail
        )

        try {
            resolveSiteResource(npubFor(pubkey), "/", deps)
            fail("expected GatewayError")
        } catch (e: GatewayError) {
            assertEquals(502, e.status)
            assertEquals("Bad Gateway", e.statusText)
            assertTrue(
                "expected 'Could not fetch' in: ${e.message}",
                e.message!!.contains("Could not fetch"),
            )
        }
    }

    // ── 13. Blossom server dedup (manifest + user) ────────────────────────

    @Test
    fun `blossom server dedup merges manifest and user sources`() = runTest {
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = listOf("https://server1.example", "https://server2.example/"),
        )
        val blossomList = buildBlossomListEvent(
            pubkey,
            servers = listOf("https://server2.example", "https://server3.example"),
        )

        var seenServers: List<String>? = null
        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> blossomList },
            fetchBlob = { _, servers ->
                seenServers = servers
                BlobFetchResult("x".toByteArray(), null)
            },
        )

        resolveSiteResource(npubFor(pubkey), "/", deps)

        assertNotNull(seenServers)
        // First-seen wins on trailing-slash dedup. server2.example comes
        // from the manifest with a trailing slash, and that form is kept.
        val expected = listOf(
            "https://server1.example",
            "https://server2.example/",
            "https://server3.example",
        )
        assertEquals(expected, seenServers)
    }

    // ── 14. Empty manifest paths → 404 ────────────────────────────────────

    @Test
    fun `empty manifest paths and no 404 page returns GatewayError 404`() = runTest {
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = emptyList(),
            servers = listOf("https://server.example"),
        )

        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> null },
        )

        try {
            resolveSiteResource(npubFor(pubkey), "/anything", deps)
            fail("expected GatewayError")
        } catch (e: GatewayError) {
            assertEquals(404, e.status)
            assertTrue(
                "expected 'Path not found' in: ${e.message}",
                e.message!!.contains("Path not found"),
            )
        }
    }

    // ── 15. No manifest servers and no 10063 event → 404 (NIP-5A MUST) ────

    @Test
    fun `no manifest servers and no blossom list throws GatewayError 404`() = runTest {
        // Manifest has paths but no `server` tags; no kind 10063 event for
        // the pubkey. NIP-5A: host MUST respond 404 — fetchBlob must never
        // be called.
        val manifest = buildRootSiteEvent(
            pubkey,
            paths = listOf("/index.html" to sha256Index),
            servers = emptyList(),
        )

        var fetchBlobCalls = 0
        val deps = GatewayDeps(
            eventCache = FakeEventCache(),
            blobCache = FakeBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> manifest },
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> fetchBlobCalls++; null },
        )

        try {
            resolveSiteResource(npubFor(pubkey), "/", deps)
            fail("expected GatewayError")
        } catch (e: GatewayError) {
            assertEquals(404, e.status)
            assertEquals("Not Found", e.statusText)
            assertTrue(
                "expected 'No Blossom servers' in: ${e.message}",
                e.message!!.contains("No Blossom servers"),
            )
        }
        assertEquals(0, fetchBlobCalls)
    }

    // ── In-memory fakes ───────────────────────────────────────────────────

    /**
     * Two-tier in-memory event cache. `fresh` is what `get(key, false)` sees;
     * `stale` is only visible to `get(key, true)`. Tests can call
     * [moveToStale] to simulate TTL expiry.
     */
    class FakeEventCache : EventCacheAdapter {
        val fresh = mutableMapOf<String, Event>()
        val stale = mutableMapOf<String, Event>()
        val gets = mutableListOf<Pair<String, Boolean>>()
        val sets = mutableListOf<String>()

        override suspend fun get(key: String, allowStale: Boolean): Event? {
            gets.add(key to allowStale)
            return fresh[key] ?: if (allowStale) stale[key] else null
        }

        override suspend fun set(key: String, event: Event) {
            sets.add(key)
            fresh[key] = event
        }

        @Suppress("unused")
        fun moveToStale(key: String) {
            fresh.remove(key)?.let { stale[key] = it }
        }
    }

    class FakeBlobCache : BlobCacheAdapter {
        val store = mutableMapOf<String, CachedBlob>()
        val gets = mutableListOf<String>()
        val sets = mutableListOf<String>()

        override suspend fun get(sha256: String): CachedBlob? {
            gets.add(sha256)
            return store[sha256]
        }

        override suspend fun set(sha256: String, body: ByteArray, contentType: String) {
            sets.add(sha256)
            store[sha256] = CachedBlob(body, contentType)
        }
    }

    // ── Test event builders ───────────────────────────────────────────────

    /**
     * Build an `AdvertisedRelayListEvent` (NIP-65) with the given relay URLs.
     * Each entry is a (url, marker) pair; marker is null/"read"/"write".
     */
    private fun buildRelayListEvent(
        pubkey: String,
        relayMarkers: List<Pair<String, String?>>,
    ): AdvertisedRelayListEvent {
        val tags: Array<Array<String>> = relayMarkers.map { (url, marker) ->
            if (marker != null) arrayOf("r", url, marker) else arrayOf("r", url)
        }.toTypedArray()
        return AdvertisedRelayListEvent(
            id = "0".repeat(64),
            pubKey = pubkey,
            createdAt = 1_700_000_000L,
            tags = tags,
            content = "",
            sig = "0".repeat(128),
        )
    }

    /** Build a `RootSiteEvent` (kind 15128). */
    private fun buildRootSiteEvent(
        pubkey: String,
        paths: List<Pair<String, String>>,
        servers: List<String>,
    ): RootSiteEvent {
        val pathTags = paths.map { (p, h) -> arrayOf("path", p, h) }
        val serverTags = servers.map { arrayOf("server", it) }
        val tags: Array<Array<String>> = (pathTags + serverTags).toTypedArray()
        return RootSiteEvent(
            id = "0".repeat(64),
            pubKey = pubkey,
            createdAt = 1_700_000_000L,
            tags = tags,
            content = "",
            sig = "0".repeat(128),
        )
    }

    /** Build a `NamedSiteEvent` (kind 35128) with a `d` tag. */
    private fun buildNamedSiteEvent(
        pubkey: String,
        identifier: String,
        paths: List<Pair<String, String>>,
        servers: List<String>,
    ): NamedSiteEvent {
        val dTag = arrayOf("d", identifier)
        val pathTags = paths.map { (p, h) -> arrayOf("path", p, h) }
        val serverTags = servers.map { arrayOf("server", it) }
        val tags: Array<Array<String>> = (listOf(dTag) + pathTags + serverTags).toTypedArray()
        return NamedSiteEvent(
            id = "0".repeat(64),
            pubKey = pubkey,
            createdAt = 1_700_000_000L,
            tags = tags,
            content = "",
            sig = "0".repeat(128),
        )
    }

    /** Build a `BlossomServersEvent` (kind 10063). */
    private fun buildBlossomListEvent(
        pubkey: String,
        servers: List<String>,
    ): BlossomServersEvent {
        val tags: Array<Array<String>> = servers.map { arrayOf("server", it) }.toTypedArray()
        return BlossomServersEvent(
            id = "0".repeat(64),
            pubKey = pubkey,
            createdAt = 1_700_000_000L,
            tags = tags,
            content = "",
            sig = "0".repeat(128),
        )
    }

    // ── Address segment helpers ──────────────────────────────────────────

    /** Encode a hex pubkey as an `npub1...` bech32 string via Quartz. */
    private fun npubFor(pubkeyHex: String): String = NPub.create(pubkeyHex)

    // Keep the unused import live so future tests can use it without needing
    // to re-add the import.
    @Suppress("unused")
    private val unusedNormalizer = RelayUrlNormalizer
}
