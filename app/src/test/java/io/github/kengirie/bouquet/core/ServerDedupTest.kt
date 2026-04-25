package io.github.kengirie.bouquet.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [deduplicateServers], covering the trailing-slash
 * normalization rules ported from
 * `bouquet-desktop/src/core/gateway.ts` `deduplicateServers`.
 */
class ServerDedupTest {

    @Test
    fun `empty list returns empty list`() {
        assertEquals(emptyList<String>(), deduplicateServers(emptyList()))
    }

    @Test
    fun `no duplicates returns input unchanged`() {
        val input = listOf("https://a.com", "https://b.com", "https://c.com")
        assertEquals(input, deduplicateServers(input))
    }

    @Test
    fun `trailing slash duplicate keeps first occurrence without slash`() {
        val input = listOf("https://a.com", "https://a.com/")
        assertEquals(listOf("https://a.com"), deduplicateServers(input))
    }

    @Test
    fun `trailing slash duplicate keeps first occurrence with slash`() {
        val input = listOf("https://a.com/", "https://a.com")
        assertEquals(listOf("https://a.com/"), deduplicateServers(input))
    }

    @Test
    fun `mixed urls preserve first occurrence and order`() {
        val input = listOf(
            "https://x.com",
            "https://y.com/",
            "https://x.com/",
            "https://y.com",
            "https://z.com",
        )
        assertEquals(
            listOf("https://x.com", "https://y.com/", "https://z.com"),
            deduplicateServers(input),
        )
    }

    @Test
    fun `case-different hosts are treated as distinct`() {
        // Desktop behavior: no host lowercasing, only trailing-slash normalization.
        val input = listOf("https://X.com", "https://x.com")
        assertEquals(input, deduplicateServers(input))
    }

    @Test
    fun `exact string duplicates are collapsed`() {
        val input = listOf("https://a.com", "https://a.com")
        assertEquals(listOf("https://a.com"), deduplicateServers(input))
    }
}
