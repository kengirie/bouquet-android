package io.github.kengirie.bouquet.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.kengirie.bouquet.core.BlobCacheAdapter
import io.github.kengirie.bouquet.core.CachedBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Content-addressed file-backed blob cache.
 *
 * Direct port of `bouquet-desktop/electron/storage/blob-cache.ts`.
 * Storage layout (one pair of files per blob):
 *   `<rootDir>/<sha256>`            raw binary body (exact bytes)
 *   `<rootDir>/<sha256>.meta.json`  sidecar JSON metadata
 *
 * Path-traversal safety: every caller-supplied sha256 must match
 * `^[0-9a-f]{64}$` (case-insensitive). Anything else throws
 * [IllegalArgumentException].
 *
 * No TTL: blobs are immutable by sha256 so eviction is out of scope here.
 * `set` is tolerant of overwrites (write-then-rename) even though in
 * production a given sha256 should only ever map to one body.
 */
class FileBlobCache(
    private val rootDir: File,
) : BlobCacheAdapter {

    @Volatile private var initialized = false

    override suspend fun get(sha256: String): CachedBlob? = withContext(Dispatchers.IO) {
        validateSha256(sha256)

        val metaFile = metaPath(sha256)
        if (!metaFile.exists()) return@withContext null

        val meta: BlobMeta = try {
            val parsed = MAPPER.readTree(metaFile)
            if (!parsed.isObject) {
                System.err.println("[FileBlobCache] meta for $sha256 is not an object")
                return@withContext null
            }
            val ct = parsed.get("contentType")
            val size = parsed.get("size")
            val savedAt = parsed.get("savedAt")
            if (ct == null || !ct.isTextual || size == null || !size.isNumber || savedAt == null || !savedAt.isNumber) {
                System.err.println("[FileBlobCache] meta for $sha256 has wrong shape")
                return@withContext null
            }
            BlobMeta(ct.asText(), size.asLong(), savedAt.asLong())
        } catch (e: Exception) {
            System.err.println("[FileBlobCache] failed to read metadata for $sha256: ${e.message}")
            return@withContext null
        }

        val blobFile = blobPath(sha256)
        if (!blobFile.exists()) return@withContext null

        val body = try {
            blobFile.readBytes()
        } catch (e: Exception) {
            System.err.println("[FileBlobCache] failed to read blob for $sha256: ${e.message}")
            return@withContext null
        }

        CachedBlob(body, meta.contentType)
    }

    override suspend fun set(sha256: String, body: ByteArray, contentType: String) {
        validateSha256(sha256)
        withContext(Dispatchers.IO) {
            ensureDir()

            val blob = blobPath(sha256)
            val blobTmp = File(rootDir, "$sha256.tmp")
            blobTmp.writeBytes(body)
            atomicMove(blobTmp, blob)

            val meta = BlobMeta(contentType, body.size.toLong(), System.currentTimeMillis())
            val metaFile = metaPath(sha256)
            val metaTmp = File(rootDir, "$sha256.meta.json.tmp")
            metaTmp.writeText(MAPPER.writeValueAsString(meta), Charsets.UTF_8)
            atomicMove(metaTmp, metaFile)
        }
    }

    /** Test helper: true iff both the blob and its metadata sidecar exist. */
    suspend fun has(sha256: String): Boolean = withContext(Dispatchers.IO) {
        validateSha256(sha256)
        blobPath(sha256).exists() && metaPath(sha256).exists()
    }

    /** Test helper: remove both files for a blob. Swallows missing files. */
    suspend fun delete(sha256: String) {
        validateSha256(sha256)
        withContext(Dispatchers.IO) {
            blobPath(sha256).delete()
            metaPath(sha256).delete()
        }
    }

    private fun blobPath(sha256: String) = File(rootDir, sha256)
    private fun metaPath(sha256: String) = File(rootDir, "$sha256.meta.json")

    private fun ensureDir() {
        if (initialized) return
        rootDir.mkdirs()
        initialized = true
    }

    private fun atomicMove(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun validateSha256(sha256: String) {
        require(SHA256_REGEX.matches(sha256)) {
            "[FileBlobCache] invalid sha256: expected 64-char hex digest"
        }
    }

    private data class BlobMeta(
        val contentType: String,
        val size: Long,
        val savedAt: Long,
    )

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)
        private val MAPPER: ObjectMapper = ObjectMapper()
    }
}
