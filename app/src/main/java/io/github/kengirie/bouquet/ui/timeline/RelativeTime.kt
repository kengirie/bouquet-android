package io.github.kengirie.bouquet.ui.timeline

/**
 * Coarse-grained "X ago" formatter for nostr `createdAt` values (UNIX
 * seconds). Intentionally locale-agnostic and dependency-free — uses
 * fixed English buckets so we don't pull in `java.time.format` machinery
 * for a single label.
 *
 * Future work: switch to ICU `RelativeDateTimeFormatter` if/when we add
 * proper i18n.
 */
fun formatRelativeTime(
    createdAtSeconds: Long,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
): String {
    val delta = nowSeconds - createdAtSeconds
    return when {
        delta < 60 -> "just now"
        delta < 3_600 -> "${delta / 60}m ago"
        delta < 86_400 -> "${delta / 3_600}h ago"
        delta < 7L * 86_400 -> "${delta / 86_400}d ago"
        delta < 30L * 86_400 -> "${delta / (7L * 86_400)}w ago"
        delta < 365L * 86_400 -> "${delta / (30L * 86_400)}mo ago"
        else -> "${delta / (365L * 86_400)}y ago"
    }
}
