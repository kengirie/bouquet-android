package io.github.kengirie.bouquet.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Substring-style tests for the polished error HTML / [hintForStatus]
 * helper. We deliberately don't full-string-equality the rendered HTML so
 * minor CSS tweaks don't break the suite.
 */
class ErrorPagesTest {

    private fun assertContains(needle: String, haystack: String) {
        assertTrue(
            "expected to find: $needle\nin:\n$haystack",
            haystack.contains(needle),
        )
    }

    // ── Hint defaults ────────────────────────────────────────────────────

    @Test
    fun `hint for unknown status falls back to default`() {
        assertEquals("The gateway returned an error.", hintForStatus(999))
    }

    @Test
    fun `hint for 400 mentions invalid address`() {
        assertEquals("The address you used is invalid.", hintForStatus(400))
    }

    @Test
    fun `hint for 405 mentions GET and HEAD`() {
        val hint = hintForStatus(405)
        assertContains("GET", hint)
        assertContains("HEAD", hint)
    }

    @Test
    fun `hint for 500 says gateway error`() {
        assertContains("Something went wrong inside the gateway.", hintForStatus(500))
    }

    @Test
    fun `hint for 502 mentions Blossom server`() {
        assertContains("Could not fetch the blob from any Blossom server.", hintForStatus(502))
    }

    // ── Page rendering ───────────────────────────────────────────────────

    @Test
    fun `404 page contains status code, hint, detail and path block`() {
        val html = renderErrorHtml(
            status = 404,
            statusText = "Not Found",
            detail = "Path not found: /missing",
            hint = hintForStatus(404),
            path = "/missing",
        )
        assertContains("404", html)
        assertContains("Not Found", html)
        // The hint text contains an apostrophe which is escaped to `&#39;`
        // in the rendered HTML, so we look for a stable substring.
        assertContains("The site or the requested path", html)
        assertContains("Path not found: /missing", html)
        assertContains("<code>/missing</code>", html)
        // Polished theme color from the desktop palette.
        assertContains("#111125", html)
    }

    @Test
    fun `400 page contains the invalid-address hint`() {
        val html = renderErrorHtml(
            status = 400,
            statusText = "Bad Request",
            detail = "Invalid address: garbage",
        )
        assertContains("400", html)
        assertContains("The address you used is invalid.", html)
    }

    @Test
    fun `405 page contains the GET HEAD hint`() {
        val html = renderErrorHtml(
            status = 405,
            statusText = "Method Not Allowed",
            detail = "Method POST is not supported.",
        )
        assertContains("405", html)
        assertContains("Only GET and HEAD are supported.", html)
    }

    @Test
    fun `500 page contains the gateway error hint`() {
        val html = renderErrorHtml(
            status = 500,
            statusText = "Internal Server Error",
            detail = "boom",
        )
        assertContains("500", html)
        assertContains("Something went wrong inside the gateway.", html)
    }

    @Test
    fun `502 page contains the Blossom hint`() {
        val html = renderErrorHtml(
            status = 502,
            statusText = "Bad Gateway",
            detail = "All Blossom servers failed.",
        )
        assertContains("502", html)
        assertContains("Could not fetch the blob from any Blossom server.", html)
    }

    @Test
    fun `detail with HTML is escaped`() {
        val html = renderErrorHtml(
            status = 500,
            statusText = "Internal Server Error",
            detail = "<script>alert(1)</script>",
        )
        assertContains("&lt;script&gt;alert(1)&lt;/script&gt;", html)
        assertFalse(
            "raw <script> tag must not appear in rendered HTML",
            html.contains("<script>alert"),
        )
    }

    @Test
    fun `path with ampersand is escaped`() {
        val html = renderErrorHtml(
            status = 404,
            statusText = "Not Found",
            detail = "Path not found.",
            path = "/foo&bar",
        )
        assertContains("/foo&amp;bar", html)
        // The literal `/foo&bar` (with a raw `&`) must not appear since
        // it should be escaped to `/foo&amp;bar`.
        assertFalse(
            "raw '&' in path must be escaped",
            html.contains(">/foo&bar<"),
        )
    }

    @Test
    fun `omitted hint falls back to hintForStatus`() {
        val html = renderErrorHtml(
            status = 404,
            statusText = "Not Found",
            detail = "x",
        )
        // Apostrophe is escaped, so test the stable prefix.
        assertContains("The site or the requested path", html)
    }
}
