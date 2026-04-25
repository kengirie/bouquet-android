package io.github.kengirie.bouquet.cache

import io.github.kengirie.bouquet.core.BlobCacheAdapter
import io.github.kengirie.bouquet.core.CachedBlob
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8 in-memory stand-in for the Phase 9 [FileBlobCache]. Blobs are
 * keyed by their SHA-256 hex string and survive only for the lifetime of
 * the process.
 */
class InMemoryBlobCacheAdapter : BlobCacheAdapter {
    private val store = ConcurrentHashMap<String, CachedBlob>()

    override suspend fun get(sha256: String): CachedBlob? = store[sha256]

    override suspend fun set(sha256: String, body: ByteArray, contentType: String) {
        store[sha256] = CachedBlob(body, contentType)
    }
}
