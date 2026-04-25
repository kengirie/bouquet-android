package io.github.kengirie.bouquet.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [getContentType], covering the precedence rules ported from
 * `bouquet-desktop/src/core/mime.ts`.
 */
class MimeTest {

    @Test
    fun `known extension with no blossom hint returns mapped MIME`() {
        assertEquals(
            "text/html; charset=utf-8",
            getContentType("/index.html", null),
        )
    }

    @Test
    fun `known extension with octet-stream hint prefers mapped MIME`() {
        assertEquals(
            "text/html; charset=utf-8",
            getContentType("/index.html", "application/octet-stream"),
        )
    }

    @Test
    fun `known extension with real blossom hint prefers blossom hint`() {
        assertEquals(
            "text/plain; charset=utf-8",
            getContentType("/index.html", "text/plain; charset=utf-8"),
        )
    }

    @Test
    fun `unknown extension with no blossom hint returns octet-stream`() {
        assertEquals(
            "application/octet-stream",
            getContentType("/file.xyz", null),
        )
    }

    @Test
    fun `unknown extension with real blossom hint returns blossom hint`() {
        assertEquals(
            "application/x-custom",
            getContentType("/file.xyz", "application/x-custom"),
        )
    }

    @Test
    fun `path with no extension and no blossom hint returns octet-stream`() {
        assertEquals(
            "application/octet-stream",
            getContentType("/foo", null),
        )
    }

    @Test
    fun `case-insensitive extension lookup`() {
        assertEquals(
            "text/html; charset=utf-8",
            getContentType("/foo.HTML", null),
        )
    }

    @Test
    fun `multiple dots only checks last segment`() {
        // .gz is not in the map, so we expect the octet-stream fallback.
        assertEquals(
            "application/octet-stream",
            getContentType("/archive.tar.gz", null),
        )
    }

    @Test
    fun `jpg extension maps to image jpeg`() {
        assertEquals(
            "image/jpeg",
            getContentType("/photo.jpg", null),
        )
    }

    @Test
    fun `jpeg extension maps to image jpeg`() {
        assertEquals(
            "image/jpeg",
            getContentType("/photo.jpeg", null),
        )
    }
}
