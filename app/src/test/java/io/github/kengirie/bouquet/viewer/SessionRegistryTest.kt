package io.github.kengirie.bouquet.viewer

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import io.github.kengirie.bouquet.core.BlobCacheAdapter
import io.github.kengirie.bouquet.core.BlobFetchResult
import io.github.kengirie.bouquet.core.CachedBlob
import io.github.kengirie.bouquet.core.EventCacheAdapter
import io.github.kengirie.bouquet.core.GatewayDeps
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for the loopback gateway listener. Each test spins up a real
 * NanoHTTPD server on `127.0.0.1:0` (ephemeral port) and exercises it with
 * OkHttp, so we cover the actual `serve()` path including HEAD/GET/POST
 * dispatch and error rendering.
 */
class SessionRegistryTest {

    private val pubkey = "a".repeat(64)
    private val sha256Index = "abcdef0123456789".repeat(4)
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun npubFor(pubkeyHex: String): String = NPub.create(pubkeyHex)

    private class MemEventCache : EventCacheAdapter {
        val store = ConcurrentHashMap<String, Event>()
        override suspend fun get(key: String, allowStale: Boolean): Event? = store[key]
        override suspend fun set(key: String, event: Event) {
            store[key] = event
        }
    }

    private class MemBlobCache : BlobCacheAdapter {
        val store = ConcurrentHashMap<String, CachedBlob>()
        override suspend fun get(sha256: String): CachedBlob? = store[sha256]
        override suspend fun set(sha256: String, body: ByteArray, contentType: String) {
            store[sha256] = CachedBlob(body, contentType)
        }
    }

    private fun buildRootSite(): RootSiteEvent {
        val tags: Array<Array<String>> = arrayOf(
            arrayOf("path", "/index.html", sha256Index),
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

    private fun deps(blob: ByteArray): GatewayDeps = GatewayDeps(
        eventCache = MemEventCache(),
        blobCache = MemBlobCache(),
        fetchRelayList = { _, _ -> null },
        fetchManifest = { _, _ -> buildRootSite() },
        fetchBlossomList = { _, _ -> null },
        fetchBlob = { _, _ -> BlobFetchResult(blob, "text/html; charset=utf-8") },
    )

    @Test
    fun `happy path serves resolved blob bytes`() {
        val expected = "<html>hi</html>".toByteArray()
        val registry = SessionRegistry(deps(expected))
        val session = registry.createSession(npubFor(pubkey))
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${session.port}/")
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("text/html; charset=utf-8", resp.header("Content-Type"))
                assertEquals("no-store", resp.header("Cache-Control"))
                val body = resp.body.bytes()
                assertEquals(String(expected), String(body))
            }
        } finally {
            registry.closeAllSessions()
        }
    }

    @Test
    fun `POST returns 405 with Allow header`() {
        val registry = SessionRegistry(deps("x".toByteArray()))
        val session = registry.createSession(npubFor(pubkey))
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${session.port}/")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                assertEquals(405, resp.code)
                val allow = resp.header("Allow")
                assertNotNull(allow)
                assertTrue(allow!!.contains("GET"))
                assertTrue(allow.contains("HEAD"))
                val ct = resp.header("Content-Type") ?: ""
                assertTrue(ct.startsWith("text/html"))
            }
        } finally {
            registry.closeAllSessions()
        }
    }

    @Test
    fun `gateway 404 returns html error page`() {
        val noManifestDeps = GatewayDeps(
            eventCache = MemEventCache(),
            blobCache = MemBlobCache(),
            fetchRelayList = { _, _ -> null },
            fetchManifest = { _, _ -> null }, // → 404
            fetchBlossomList = { _, _ -> null },
            fetchBlob = { _, _ -> null },
        )
        val registry = SessionRegistry(noManifestDeps)
        val session = registry.createSession(npubFor(pubkey))
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${session.port}/")
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                assertEquals(404, resp.code)
                val ct = resp.header("Content-Type") ?: ""
                assertTrue("expected text/html, got $ct", ct.startsWith("text/html"))
                val body = resp.body.string()
                assertTrue(body.contains("404"))
            }
        } finally {
            registry.closeAllSessions()
        }
    }

    @Test
    fun `closeSession releases the listener`() {
        val registry = SessionRegistry(deps("x".toByteArray()))
        val session = registry.createSession(npubFor(pubkey))
        registry.closeSession(session.id)

        val request = Request.Builder()
            .url("http://127.0.0.1:${session.port}/")
            .get()
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                // Some platforms may briefly accept the connection during
                // socket teardown; if so, we should at least not be hitting
                // a fully-functional session.
                assertNotEquals(200, resp.code)
            }
            // Either a non-IOException response (acceptable per the comment
            // above) or an IOException both prove we're no longer being
            // served by the closed session.
        } catch (_: IOException) {
            // Connection refused — the listener is gone. This is the
            // expected outcome on most platforms.
        } catch (e: Exception) {
            fail("unexpected exception type after close: $e")
        }
    }
}
