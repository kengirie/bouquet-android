package io.github.kengirie.bouquet.core

/**
 * Deduplicate Blossom server URLs treating a trailing slash as
 * insignificant. Preserves the order of first occurrence and the original
 * spelling of the first occurrence (so `https://x.com` and
 * `https://x.com/` collapse to whichever appeared first).
 *
 * Pure port of `bouquet-desktop/src/core/gateway.ts` `deduplicateServers`.
 * Note: like the desktop version, this does not lowercase the host or
 * touch the scheme — only the trailing slash is normalized.
 */
fun deduplicateServers(servers: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    val result = ArrayList<String>(servers.size)
    for (s in servers) {
        val normalized = if (s.endsWith("/")) s.dropLast(1) else s
        if (seen.add(normalized)) {
            result.add(s)
        }
    }
    return result
}
