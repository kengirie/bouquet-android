package io.github.kengirie.bouquet.cache

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** Unit tests for [FileBlobCache]. */
class FileBlobCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sha = "0123456789abcdef".repeat(4)

    private fun newCache(sub: String = "blobs"): FileBlobCache =
        FileBlobCache(rootDir = File(tempFolder.root, sub))

    // ── 1. Empty cache ────────────────────────────────────────────────────

    @Test
    fun `get on empty cache returns null`() = runTest {
        val cache = newCache()
        assertNull(cache.get("0".repeat(64)))
    }

    // ── 2. Set → get round-trip ───────────────────────────────────────────

    @Test
    fun `set then get returns the same body and content type`() = runTest {
        val cache = newCache()
        val body = "<html>hello</html>".toByteArray()
        cache.set(sha, body, "text/html; charset=utf-8")
        val got = cache.get(sha)
        assertNotNull(got)
        assertArrayEquals(body, got!!.body)
        assertEquals("text/html; charset=utf-8", got.contentType)
    }

    // ── 3. has() reflects state ──────────────────────────────────────────

    @Test
    fun `has reflects state across set and delete`() = runTest {
        val cache = newCache()
        assertFalse(cache.has(sha))
        cache.set(sha, "x".toByteArray(), "text/plain")
        assertTrue(cache.has(sha))
        cache.delete(sha)
        assertFalse(cache.has(sha))
    }

    // ── 4. delete removes both files ──────────────────────────────────────

    @Test
    fun `delete removes both blob and meta files`() = runTest {
        val root = File(tempFolder.root, "blobs")
        val cache = FileBlobCache(root)
        cache.set(sha, "x".toByteArray(), "text/plain")
        assertTrue(File(root, sha).exists())
        assertTrue(File(root, "$sha.meta.json").exists())

        cache.delete(sha)
        assertFalse(File(root, sha).exists())
        assertFalse(File(root, "$sha.meta.json").exists())
    }

    // ── 5. Path traversal blocked ────────────────────────────────────────

    @Test
    fun `path traversal sha256 throws on get`() = runTest {
        val cache = newCache()
        try {
            cache.get("../etc/passwd")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `path traversal sha256 throws on set`() = runTest {
        val cache = newCache()
        try {
            cache.set("../etc/passwd", "x".toByteArray(), "text/plain")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── 6. Invalid hex blocked ────────────────────────────────────────────

    @Test
    fun `non-hex chars are rejected`() = runTest {
        val cache = newCache()
        try {
            cache.get("Z".repeat(64))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── 7. Wrong length blocked ───────────────────────────────────────────

    @Test
    fun `length 63 sha256 is rejected`() = runTest {
        val cache = newCache()
        try {
            cache.get("a".repeat(63))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `length 65 sha256 is rejected`() = runTest {
        val cache = newCache()
        try {
            cache.get("a".repeat(65))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── 8. Mixed case allowed ────────────────────────────────────────────

    @Test
    fun `uppercase hex sha256 is accepted`() = runTest {
        val cache = newCache()
        // Must not throw; result is just null (no entry).
        assertNull(cache.get("ABCDEF0123456789".repeat(4)))
    }

    // ── 9. Missing meta file → null ───────────────────────────────────────

    @Test
    fun `body without meta returns null`() = runTest {
        val root = File(tempFolder.root, "blobs")
        root.mkdirs()
        File(root, sha).writeBytes("orphan body".toByteArray())
        // No meta sidecar.
        val cache = FileBlobCache(root)
        assertNull(cache.get(sha))
    }

    // ── 10. Corrupt meta file → null ──────────────────────────────────────

    @Test
    fun `corrupt meta returns null`() = runTest {
        val root = File(tempFolder.root, "blobs")
        root.mkdirs()
        File(root, sha).writeBytes("body".toByteArray())
        File(root, "$sha.meta.json").writeText("not json", Charsets.UTF_8)

        val cache = FileBlobCache(root)
        assertNull(cache.get(sha))
    }

    // ── 11. Atomic write leaves no .tmp ───────────────────────────────────

    @Test
    fun `set leaves no tmp files behind`() = runTest {
        val root = File(tempFolder.root, "blobs")
        val cache = FileBlobCache(root)
        cache.set(sha, "x".toByteArray(), "text/plain")
        val tmpFiles = root.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("no tmp files expected, found: $tmpFiles", tmpFiles.isEmpty())
    }

    // ── 12. Random binary bytes round-trip exactly ────────────────────────

    @Test
    fun `random binary bytes round-trip byte-for-byte`() = runTest {
        val cache = newCache()
        val random = Random(0xCAFEBABE)
        val body = ByteArray(8192).also { random.nextBytes(it) }
        cache.set(sha, body, "application/octet-stream")
        val got = cache.get(sha)
        assertNotNull(got)
        assertArrayEquals(body, got!!.body)
        assertEquals("application/octet-stream", got.contentType)
    }

    // ── 13. Wrong-shape meta JSON → null ──────────────────────────────────

    @Test
    fun `meta with wrong shape returns null`() = runTest {
        val root = File(tempFolder.root, "blobs")
        root.mkdirs()
        File(root, sha).writeBytes("body".toByteArray())
        // Missing `size` and `savedAt`.
        File(root, "$sha.meta.json").writeText("""{"contentType":"text/plain"}""", Charsets.UTF_8)

        val cache = FileBlobCache(root)
        assertNull(cache.get(sha))
    }
}
