package io.github.kengirie.bouquet.blossom

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for [BlossomFetcher.fetchBlob].
 *
 * Uses MockWebServer (`mockwebserver3` — the OkHttp 5.x package) to drive
 * realistic HTTP responses, and an OkHttpClient with short timeouts so a
 * misbehaving test doesn't hang CI. The most important case is "first
 * server returns 200 with WRONG bytes" — that's what proves we actually
 * verify SHA-256 instead of just trusting the status code.
 */
class BlossomFetcherTest {

    private lateinit var server1: MockWebServer
    private lateinit var server2: MockWebServer
    private lateinit var fetcher: BlossomFetcher

    private val payload: ByteArray = "hello".toByteArray(Charsets.UTF_8)
    private lateinit var sha256Hex: String

    @Before
    fun setUp() {
        server1 = MockWebServer().also { it.start() }
        server2 = MockWebServer().also { it.start() }

        // Compute the sha256 of `payload` directly so the test is robust
        // against typos in a hardcoded constant.
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        sha256Hex = digest.toHex()

        val client = OkHttpClient.Builder().build()
        fetcher = BlossomFetcher(client)
    }

    @After
    fun tearDown() {
        server1.close()
        server2.close()
    }

    @Test
    fun `first server returns valid bytes`() = runTest {
        server1.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/plain")
                .body(Buffer().write(payload))
                .build(),
        )

        val result = fetcher.fetchBlob(sha256Hex, listOf(serverBase(server1)))

        assertNotNull(result)
        assertArrayEquals(payload, result!!.bytes)
        assertEquals("text/plain", result.contentType)

        val recorded = server1.takeRequest()
        assertEquals("/$sha256Hex", recorded.url.encodedPath)
    }

    @Test
    fun `falls through 404 to second server`() = runTest {
        server1.enqueue(MockResponse.Builder().code(404).build())
        server2.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/octet-stream")
                .body(Buffer().write(payload))
                .build(),
        )

        val result = fetcher.fetchBlob(
            sha256Hex,
            listOf(serverBase(server1), serverBase(server2)),
        )

        assertNotNull(result)
        assertArrayEquals(payload, result!!.bytes)
        assertEquals("application/octet-stream", result.contentType)
    }

    @Test
    fun `falls through SHA mismatch to second server`() {
        // The "important" test: if we naively returned on first 200,
        // we'd hand back garbage. Verifies SHA-256 is actually checked.
        runTest {
            val wrongBytes = "world".toByteArray(Charsets.UTF_8)
            server1.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/plain")
                    .body(Buffer().write(wrongBytes))
                    .build(),
            )
            server2.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/x-correct")
                    .body(Buffer().write(payload))
                    .build(),
            )

            val result = fetcher.fetchBlob(
                sha256Hex,
                listOf(serverBase(server1), serverBase(server2)),
            )

            assertNotNull(result)
            assertArrayEquals(payload, result!!.bytes)
            assertEquals("text/x-correct", result.contentType)
        }
    }

    @Test
    fun `all servers fail returns null`() = runTest {
        server1.enqueue(MockResponse.Builder().code(404).build())
        server2.enqueue(MockResponse.Builder().code(500).build())

        val result = fetcher.fetchBlob(
            sha256Hex,
            listOf(serverBase(server1), serverBase(server2)),
        )

        assertNull(result)
    }

    @Test
    fun `trailing slash on server url is normalized`() = runTest {
        server1.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/plain")
                .body(Buffer().write(payload))
                .build(),
        )

        // serverBase() produces a no-slash base; pass with a trailing
        // slash explicitly to exercise the removeSuffix("/") branch.
        val result = fetcher.fetchBlob(
            sha256Hex,
            listOf(serverBase(server1) + "/"),
        )

        assertNotNull(result)
        val recorded = server1.takeRequest()
        // Critically: not "//<sha>".
        assertEquals("/$sha256Hex", recorded.url.encodedPath)
    }

    @Test
    fun `missing content-type header yields null contentType`() = runTest {
        server1.enqueue(
            MockResponse.Builder()
                .code(200)
                .headers(Headers.headersOf()) // no Content-Type
                .body(Buffer().write(payload))
                .build(),
        )

        val result = fetcher.fetchBlob(sha256Hex, listOf(serverBase(server1)))

        assertNotNull(result)
        assertNull(result!!.contentType)
    }

    @Test
    fun `empty server list returns null`() = runTest {
        val result = fetcher.fetchBlob(sha256Hex, emptyList())
        assertNull(result)
        // Servers were never started for; no requests should have happened.
        assertEquals(0, server1.requestCount)
        assertEquals(0, server2.requestCount)
    }

    private fun serverBase(server: MockWebServer): String {
        // url("/") gives e.g. "http://127.0.0.1:54321/" — strip the path.
        val full = server.url("/").toString()
        return full.removeSuffix("/")
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0f, 16))
        }
        return sb.toString()
    }
}
