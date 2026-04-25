package io.github.kengirie.bouquet.core

import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag

/**
 * Result of resolving a requested path against a manifest's path/sha256 map.
 *
 * Mirrors the desktop `PathResolution` interface from `bouquet-desktop/src/core/manifest.ts`.
 *
 * @property sha256 The blob hash to fetch from Blossom.
 * @property resolvedPath The actual manifest entry that was matched (may differ from
 *   the requested path when an `/index.html` or `/404.html` fallback fired).
 * @property is404 True iff this resolution came from the `/404.html` fallback.
 */
data class PathResolution(
    val sha256: String,
    val resolvedPath: String,
    val is404: Boolean,
)

/**
 * Resolve [requestedPath] against a list of `PathTag` entries from a manifest event.
 *
 * Convenience wrapper around [resolvePathFromMap] that builds the lookup map. When
 * two `PathTag` entries share the same `path`, **the later one wins** — matching the
 * TypeScript `Map.set` semantics (`paths.associate { it.path to it.hash }`).
 */
fun resolvePath(paths: List<PathTag>, requestedPath: String): PathResolution? {
    // associate() picks the LAST value for duplicate keys, matching TS Map.set.
    val lookup = paths.associate { it.path to it.hash }
    return resolvePathFromMap(lookup, requestedPath)
}

/**
 * Pure path resolution against a pre-built `path → sha256` map.
 *
 * Ports `resolvePath` from `bouquet-desktop/src/core/manifest.ts` verbatim:
 *
 * 1. Normalize: ensure leading slash, then strip trailing slash (except root).
 * 2. Exact match.
 * 3. `/index.html` (root) or `<path>/index.html` (non-root).
 * 4. `/404.html` fallback (with `is404 = true`).
 * 5. Otherwise null.
 */
fun resolvePathFromMap(lookup: Map<String, String>, requestedPath: String): PathResolution? {
    // Normalize: ensure leading slash, remove trailing slash (except root).
    // Order matches TS exactly: leading-slash first, then trailing-slash strip.
    var path = requestedPath
    if (!path.startsWith("/")) path = "/$path"
    if (path != "/" && path.endsWith("/")) path = path.dropLast(1)

    // 1. Exact match
    val exact = lookup[path]
    if (exact != null) {
        return PathResolution(sha256 = exact, resolvedPath = path, is404 = false)
    }

    // 2. Try with /index.html appended
    val indexPath = if (path == "/") "/index.html" else "$path/index.html"
    val index = lookup[indexPath]
    if (index != null) {
        return PathResolution(sha256 = index, resolvedPath = indexPath, is404 = false)
    }

    // 3. Fallback to /404.html
    val notFound = lookup["/404.html"]
    if (notFound != null) {
        return PathResolution(sha256 = notFound, resolvedPath = "/404.html", is404 = true)
    }

    return null
}
