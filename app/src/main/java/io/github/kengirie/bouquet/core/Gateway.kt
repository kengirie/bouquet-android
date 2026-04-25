package io.github.kengirie.bouquet.core

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import io.github.kengirie.bouquet.config.Defaults

// ── Public types ──────────────────────────────────────────────────────────

/**
 * Result of [resolveSiteResource]: a fully-resolved blob ready to be served
 * by the loopback HTTP server in Phase 8.
 *
 * `equals` / `hashCode` are overridden because Kotlin's auto-generated
 * versions on a `data class` containing a [ByteArray] use reference
 * equality on the array, which would break test assertions.
 */
class ResolvedSiteResource(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val resolvedPath: String,
    val is404: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedSiteResource) return false
        if (status != other.status) return false
        if (contentType != other.contentType) return false
        if (!body.contentEquals(other.body)) return false
        if (resolvedPath != other.resolvedPath) return false
        if (is404 != other.is404) return false
        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + contentType.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + resolvedPath.hashCode()
        result = 31 * result + is404.hashCode()
        return result
    }
}

/**
 * Cache entry for a Blossom blob: the bytes plus the Content-Type that
 * was determined at resolution time (so we don't re-run [getContentType]
 * on every cache hit).
 */
class CachedBlob(
    val body: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CachedBlob) return false
        if (!body.contentEquals(other.body)) return false
        if (contentType != other.contentType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = body.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

/**
 * Persistence-agnostic event cache. Phase 9 will swap an in-memory
 * implementation for a JSON-on-disk one (`JsonEventCache`).
 *
 * The `allowStale` flag mirrors the desktop cache contract: the gateway
 * uses fresh entries first, then a network fetch, and only falls back to
 * stale entries if the network fails entirely.
 */
interface EventCacheAdapter {
    suspend fun get(key: String, allowStale: Boolean = false): Event?
    suspend fun set(key: String, event: Event)
}

/**
 * Persistence-agnostic blob cache. Phase 9 will swap an in-memory
 * implementation for a file-on-disk one (`FileBlobCache`).
 */
interface BlobCacheAdapter {
    suspend fun get(sha256: String): CachedBlob?
    suspend fun set(sha256: String, body: ByteArray, contentType: String)
}

/**
 * Thrown on any non-success outcome of [resolveSiteResource]. The Phase 8
 * loopback HTTP server maps these onto HTTP responses with the same
 * [status] / [statusText] values.
 */
class GatewayError(
    val status: Int,
    val statusText: String,
    message: String,
) : Exception(message)

/**
 * Result of fetching a blob from a Blossom server: the raw bytes plus the
 * `Content-Type` the server reported (or `null` if it didn't). This is the
 * shape `deps.fetchBlob` returns; `BlossomFetcher.fetchBlob` (Phase 6)
 * returns the closely-related `BlobResult` and a thin adapter at the call
 * site converts between the two.
 */
class BlobFetchResult(
    val body: ByteArray,
    val contentType: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobFetchResult) return false
        if (!body.contentEquals(other.body)) return false
        if (contentType != other.contentType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = body.contentHashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}

// Type aliases for fetcher functions — matches the desktop GatewayDeps shape.
typealias FetchRelayListFn = suspend (
    pubkey: String,
    lookupRelays: Set<NormalizedRelayUrl>,
) -> Event?

typealias FetchManifestFn = suspend (
    address: SiteAddress,
    relays: Set<NormalizedRelayUrl>,
) -> Event?

typealias FetchBlossomListFn = suspend (
    pubkey: String,
    relays: Set<NormalizedRelayUrl>,
) -> Event?

typealias FetchBlobFn = suspend (
    sha256: String,
    servers: List<String>,
) -> BlobFetchResult?

/**
 * Bundle of injected dependencies for [resolveSiteResource]. Tests pass an
 * in-memory cache + lambda fetchers; production passes `JsonEventCache` /
 * `FileBlobCache` / `NostrClient`-backed fetchers.
 */
data class GatewayDeps(
    val eventCache: EventCacheAdapter,
    val blobCache: BlobCacheAdapter,
    val fetchRelayList: FetchRelayListFn,
    val fetchManifest: FetchManifestFn,
    val fetchBlossomList: FetchBlossomListFn,
    val fetchBlob: FetchBlobFn,
)

// ── Cache key helpers ─────────────────────────────────────────────────────

internal fun relayListKey(pubkey: String) = "relay:$pubkey"
internal fun blossomListKey(pubkey: String) = "blossom:$pubkey"
internal fun manifestKey(pubkey: String, identifier: String?) =
    if (identifier != null) "manifest:$pubkey:$identifier" else "manifest:$pubkey"

// ── Main entry point ──────────────────────────────────────────────────────

/**
 * Core gateway flow: decode address → fetch relays → fetch manifest →
 * resolve path → blob cache check → fetch blob → MIME detect → cache blob.
 *
 * Pure service: all IO (event cache, blob cache, relay/blob fetch) is
 * injected via [deps], so the same implementation drives both production
 * and unit tests. Direct port of `resolveSiteResource` from
 * `bouquet-desktop/src/core/gateway.ts`.
 *
 * @throws GatewayError 400 (bad address), 404 (no manifest / no path) or
 *   502 (blob fetch failed).
 */
suspend fun resolveSiteResource(
    addressSegment: String,
    path: String,
    deps: GatewayDeps,
): ResolvedSiteResource {
    // 1. Decode the user-provided address segment.
    val address = when (val outcome = SiteAddress.parse(addressSegment)) {
        is SiteAddress.ParseOutcome.Success -> outcome.address
        is SiteAddress.ParseOutcome.Failure -> throw GatewayError(
            status = 400,
            statusText = "Bad Request",
            message = "Invalid address: ${outcome.error.message}",
        )
    }

    // 2. Resolve the user's write relays (cache-first, network, stale fallback).
    val relays = getRelays(address, deps)

    // 3. Fetch the manifest event for this address.
    val manifestEvent = getManifest(address, relays, deps)
        ?: throw GatewayError(
            status = 404,
            statusText = "Not Found",
            message = "Site not found. No manifest event published for this address.",
        )

    // 4. Resolve the requested path against the manifest's path tags.
    val paths = manifestPaths(manifestEvent)
    val resolution = resolvePath(paths, path)
        ?: throw GatewayError(
            status = 404,
            statusText = "Not Found",
            message = "Path not found: $path",
        )

    // 5. Blob cache short-circuit.
    val cached = deps.blobCache.get(resolution.sha256)
    if (cached != null) {
        return ResolvedSiteResource(
            status = if (resolution.is404) 404 else 200,
            contentType = cached.contentType,
            body = cached.body,
            resolvedPath = resolution.resolvedPath,
            is404 = resolution.is404,
        )
    }

    // 6. Discover Blossom servers: manifest first (most likely to host the
    //    blob), then user's published list, then defaults.
    val manifestServers = manifestServerList(manifestEvent)
    val userServers = getBlossomServers(address.pubkey, relays, deps)
    val servers = deduplicateServers(
        manifestServers + userServers + Defaults.DEFAULT_BLOSSOM_SERVERS,
    )

    // 7. Fetch the blob.
    val blobResult = deps.fetchBlob(resolution.sha256, servers)
        ?: throw GatewayError(
            status = 502,
            statusText = "Bad Gateway",
            message = "Could not fetch file from any Blossom server.",
        )

    // 8. Determine the Content-Type.
    val contentType = getContentType(resolution.resolvedPath, blobResult.contentType)

    // 9. Cache the blob for next time.
    deps.blobCache.set(resolution.sha256, blobResult.body, contentType)

    // 10. Done.
    return ResolvedSiteResource(
        status = if (resolution.is404) 404 else 200,
        contentType = contentType,
        body = blobResult.body,
        resolvedPath = resolution.resolvedPath,
        is404 = resolution.is404,
    )
}

// ── Private cache-aware helpers ───────────────────────────────────────────

private suspend fun getRelays(
    address: SiteAddress,
    deps: GatewayDeps,
): Set<NormalizedRelayUrl> {
    val pubkey = address.pubkey
    val key = relayListKey(pubkey)
    val lookupRelays = (address.relayHints + Defaults.DEFAULT_LOOKUP_RELAYS).toSet()

    // Fresh cache.
    deps.eventCache.get(key)?.let { cached ->
        val relays = parseWriteRelays(cached)
        return if (relays.isNotEmpty()) relays.toSet() else lookupRelays
    }

    // Network fetch.
    deps.fetchRelayList(pubkey, lookupRelays)?.let { event ->
        deps.eventCache.set(key, event)
        val relays = parseWriteRelays(event)
        return if (relays.isNotEmpty()) relays.toSet() else lookupRelays
    }

    // Stale fallback.
    deps.eventCache.get(key, allowStale = true)?.let { stale ->
        val relays = parseWriteRelays(stale)
        return if (relays.isNotEmpty()) relays.toSet() else lookupRelays
    }

    return lookupRelays
}

private suspend fun getManifest(
    address: SiteAddress,
    relays: Set<NormalizedRelayUrl>,
    deps: GatewayDeps,
): Event? {
    val identifier = (address as? SiteAddress.Named)?.identifier
    val key = manifestKey(address.pubkey, identifier)

    // Fresh cache.
    deps.eventCache.get(key)?.let { return it }

    // Network fetch.
    deps.fetchManifest(address, relays)?.let { event ->
        deps.eventCache.set(key, event)
        return event
    }

    // Stale fallback.
    return deps.eventCache.get(key, allowStale = true)
}

private suspend fun getBlossomServers(
    pubkey: String,
    relays: Set<NormalizedRelayUrl>,
    deps: GatewayDeps,
): List<String> {
    val key = blossomListKey(pubkey)

    // Fresh cache.
    deps.eventCache.get(key)?.let { return parseBlossomServers(it) }

    // Network fetch.
    deps.fetchBlossomList(pubkey, relays)?.let { event ->
        deps.eventCache.set(key, event)
        return parseBlossomServers(event)
    }

    // Stale fallback.
    deps.eventCache.get(key, allowStale = true)?.let { return parseBlossomServers(it) }

    return emptyList()
}

// ── Pure typed-event accessors ────────────────────────────────────────────

/**
 * `writeRelaysNorm()` returns null when there are zero `r write` tags,
 * not an empty list — see `AdvertisedRelayListEvent.writeRelaysNorm`. We
 * coalesce here so callers always get a list.
 */
private fun parseWriteRelays(event: Event): List<NormalizedRelayUrl> =
    (event as? AdvertisedRelayListEvent)?.writeRelaysNorm() ?: emptyList()

private fun parseBlossomServers(event: Event): List<String> =
    (event as? BlossomServersEvent)?.servers() ?: emptyList()

private fun manifestPaths(event: Event) =
    (event as? RootSiteEvent)?.paths()
        ?: (event as? NamedSiteEvent)?.paths()
        ?: emptyList()

private fun manifestServerList(event: Event): List<String> =
    (event as? RootSiteEvent)?.servers()
        ?: (event as? NamedSiteEvent)?.servers()
        ?: emptyList()
