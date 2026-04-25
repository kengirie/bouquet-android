package io.github.kengirie.bouquet.core

import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [resolvePath] / [resolvePathFromMap], covering the path
 * normalization + fallback semantics ported from
 * `bouquet-desktop/src/core/manifest.ts`.
 */
class PathResolutionTest {

    @Test
    fun `exact match returns matching entry`() {
        val map = mapOf("/about.html" to "abc")
        val result = resolvePathFromMap(map, "/about.html")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/about.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `root requesting index html falls through to index`() {
        val map = mapOf("/index.html" to "abc")
        val result = resolvePathFromMap(map, "/")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/index.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `subdirectory falls through to index html`() {
        val map = mapOf("/blog/index.html" to "abc")
        val result = resolvePathFromMap(map, "/blog")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/blog/index.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `subdirectory with trailing slash strips slash and falls through to index`() {
        val map = mapOf("/blog/index.html" to "abc")
        val result = resolvePathFromMap(map, "/blog/")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/blog/index.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `path without leading slash gets one prepended`() {
        val map = mapOf("/foo.html" to "abc")
        val result = resolvePathFromMap(map, "foo.html")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/foo.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `missing path falls back to 404 html with is404 true`() {
        val map = mapOf("/404.html" to "nf", "/index.html" to "ix")
        val result = resolvePathFromMap(map, "/missing")
        assertNotNull(result)
        assertEquals("nf", result!!.sha256)
        assertEquals("/404.html", result.resolvedPath)
        assertTrue(result.is404)
    }

    @Test
    fun `missing path with no 404 returns null`() {
        val map = mapOf("/index.html" to "ix")
        val result = resolvePathFromMap(map, "/missing")
        assertNull(result)
    }

    @Test
    fun `empty manifest returns null`() {
        val result = resolvePathFromMap(emptyMap(), "/anything")
        assertNull(result)
    }

    @Test
    fun `exact match wins over index html fallback`() {
        val map = mapOf("/about" to "ex", "/about/index.html" to "ix")
        val result = resolvePathFromMap(map, "/about")
        assertNotNull(result)
        assertEquals("ex", result!!.sha256)
        assertEquals("/about", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `exact match wins over 404 html fallback`() {
        val map = mapOf("/page" to "ok", "/404.html" to "nf")
        val result = resolvePathFromMap(map, "/page")
        assertNotNull(result)
        assertEquals("ok", result!!.sha256)
        assertEquals("/page", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `root path with no index html falls back to 404`() {
        val map = mapOf("/404.html" to "nf")
        val result = resolvePathFromMap(map, "/")
        assertNotNull(result)
        assertEquals("nf", result!!.sha256)
        assertEquals("/404.html", result.resolvedPath)
        assertTrue(result.is404)
    }

    @Test
    fun `trailing slash on root stays root`() {
        val map = mapOf("/index.html" to "abc")
        val result = resolvePathFromMap(map, "/")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/index.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `empty string normalizes to root`() {
        val map = mapOf("/index.html" to "abc")
        val result = resolvePathFromMap(map, "")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/index.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `duplicate paths in PathTag list - later wins`() {
        val tags = listOf(
            PathTag("/x", "first"),
            PathTag("/x", "second"),
        )
        val result = resolvePath(tags, "/x")
        assertNotNull(result)
        assertEquals("second", result!!.sha256)
        assertEquals("/x", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `resolvePath PathTag overload exact match`() {
        val tags = listOf(PathTag("/about.html", "abc"))
        val result = resolvePath(tags, "/about.html")
        assertNotNull(result)
        assertEquals("abc", result!!.sha256)
        assertEquals("/about.html", result.resolvedPath)
        assertEquals(false, result.is404)
    }

    @Test
    fun `resolvePath PathTag overload 404 fallback`() {
        val tags = listOf(
            PathTag("/404.html", "nf"),
            PathTag("/index.html", "ix"),
        )
        val result = resolvePath(tags, "/missing")
        assertNotNull(result)
        assertEquals("nf", result!!.sha256)
        assertEquals("/404.html", result.resolvedPath)
        assertTrue(result.is404)
    }
}
