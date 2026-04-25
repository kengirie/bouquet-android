package io.github.kengirie.bouquet.blossom

import io.github.kengirie.bouquet.config.Defaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The bytes returned from a Blossom server, plus the `Content-Type` the
 * server reported (or `null` if absent). The body of the response is
 * already fully buffered — callers are not expected to stream.
 *
 * `equals` / `hashCode` are overridden because Kotlin's auto-generated
 * versions on a `data class` containing a `ByteArray` use reference
 * equality on the array, which would defeat the test assertions.
 */
class BlobResult(
    val bytes: ByteArray,
    val contentType: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobResult) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (contentType != other.contentType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}

/**
 * Fetches a SHA-256 addressed blob from a list of Blossom servers in
 * order, verifying integrity before returning. Pure port of the desktop
 * `fetchBlob` (`bouquet-desktop/src/core/blossom.ts`).
 *
 * The supplied [client] is reused for connection pooling; a per-call
 * client is built via `client.newBuilder().callTimeout(...)` so the
 * timeout doesn't leak to other consumers of the shared client.
 */
class BlossomFetcher(private val client: OkHttpClient) {

    /**
     * Iterate through [servers] in order, GETting `<server>/<sha256>`.
     * Returns the first response whose body's SHA-256 matches [sha256]
     * (case-insensitive on the hex string), or `null` once every server
     * has been tried unsuccessfully.
     *
     * Behavior:
     *  - Trailing `/` on a server URL is stripped before path concatenation.
     *  - HTTP status outside 200..299 is treated as a miss; we move on.
     *  - SHA mismatch is treated as a miss; we move on.
     *  - Any IOException / timeout / other throwable is swallowed and we
     *    move on to the next server.
     */
    suspend fun fetchBlob(
        sha256: String,
        servers: List<String>,
        timeoutMs: Long = Defaults.BLOB_TIMEOUT_MS,
    ): BlobResult? {
        if (servers.isEmpty()) return null

        // Per-call client: don't mutate the shared OkHttpClient. The
        // connection pool / dispatcher / cache all stay shared because
        // newBuilder() copies them by reference.
        val callClient = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val expected = sha256.lowercase()

        for (server in servers) {
            val baseUrl = server.removeSuffix("/")
            val url = "$baseUrl/$sha256"

            val result = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(url).get().build()
                    callClient.newCall(request).execute().use { response ->
                        if (response.code !in 200..299) return@use null
                        val bytes = response.body.bytes()
                        if (!verifyBlobIntegrity(bytes, expected)) return@use null
                        BlobResult(bytes, response.header("Content-Type"))
                    }
                } catch (_: Throwable) {
                    null
                }
            }

            if (result != null) return result
        }

        return null
    }

    private fun verifyBlobIntegrity(bytes: ByteArray, expectedHexLower: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val actual = digest.toHexLower()
        return actual == expectedHexLower
    }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0f])
        }
        return sb.toString()
    }

    companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
