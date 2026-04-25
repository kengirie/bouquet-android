package io.github.kengirie.bouquet.core

/**
 * Extension → MIME type lookup, ported verbatim from
 * `bouquet-desktop/src/core/mime.ts`.
 *
 * Keys include the leading dot and are matched case-insensitively against
 * the path's last `.<ext>` segment.
 */
private val MIME_MAP: Map<String, String> = mapOf(
    ".html" to "text/html; charset=utf-8",
    ".htm" to "text/html; charset=utf-8",
    ".css" to "text/css; charset=utf-8",
    ".js" to "application/javascript; charset=utf-8",
    ".mjs" to "application/javascript; charset=utf-8",
    ".json" to "application/json; charset=utf-8",
    ".xml" to "application/xml; charset=utf-8",
    ".svg" to "image/svg+xml",
    ".png" to "image/png",
    ".jpg" to "image/jpeg",
    ".jpeg" to "image/jpeg",
    ".gif" to "image/gif",
    ".webp" to "image/webp",
    ".avif" to "image/avif",
    ".ico" to "image/x-icon",
    ".woff" to "font/woff",
    ".woff2" to "font/woff2",
    ".ttf" to "font/ttf",
    ".otf" to "font/otf",
    ".pdf" to "application/pdf",
    ".wasm" to "application/wasm",
    ".txt" to "text/plain; charset=utf-8",
    ".md" to "text/markdown; charset=utf-8",
    ".webmanifest" to "application/manifest+json",
)

/**
 * Determine the Content-Type for a manifest entry, mirroring
 * `getContentType` from `bouquet-desktop/src/core/mime.ts`.
 *
 * Precedence:
 *  1. A non-null, non-`application/octet-stream` Blossom hint wins.
 *  2. Otherwise, look up the path's extension in [MIME_MAP].
 *  3. Otherwise, fall back to the Blossom hint (which may be
 *     `application/octet-stream`) or `application/octet-stream`.
 */
fun getContentType(path: String, blossomContentType: String?): String {
    if (blossomContentType != null && blossomContentType != "application/octet-stream") {
        return blossomContentType
    }
    val dotIndex = path.lastIndexOf('.')
    if (dotIndex != -1) {
        val ext = path.substring(dotIndex).lowercase()
        val mapped = MIME_MAP[ext]
        if (mapped != null) return mapped
    }
    return blossomContentType ?: "application/octet-stream"
}
