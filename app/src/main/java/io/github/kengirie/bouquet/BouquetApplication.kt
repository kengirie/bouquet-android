package io.github.kengirie.bouquet

import android.app.Application
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import io.github.kengirie.bouquet.blossom.BlossomFetcher
import io.github.kengirie.bouquet.cache.FileBlobCache
import io.github.kengirie.bouquet.cache.JsonEventCache
import io.github.kengirie.bouquet.cache.TimelineSnapshotCache
import io.github.kengirie.bouquet.config.Defaults
import io.github.kengirie.bouquet.core.BlobFetchResult
import io.github.kengirie.bouquet.core.GatewayDeps
import io.github.kengirie.bouquet.nostr.fetchOne
import io.github.kengirie.bouquet.nostr.fetchManifest
import io.github.kengirie.bouquet.viewer.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

/**
 * Application class that owns the long-lived [NostrClient] singleton, the
 * shared [OkHttpClient], the in-memory cache adapters (Phase 8 stand-ins
 * for Phase 9's persistent caches) and the [SessionRegistry] that backs
 * the WebView viewer.
 */
class BouquetApplication : Application() {
    val appScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val nostrClient: NostrClient by lazy {
        NostrClient(BasicOkHttpWebSocket.Builder { _ -> httpClient }, appScope)
    }

    val blossomFetcher: BlossomFetcher by lazy { BlossomFetcher(httpClient) }

    val eventCache: JsonEventCache by lazy {
        JsonEventCache(
            file = File(filesDir, "event-cache.json"),
            ttlMs = Defaults.EVENT_CACHE_TTL_MS,
        ).also { cache ->
            // Trigger initial load asynchronously so app startup is not
            // blocked on the JSON parse.
            appScope.launch { cache.load() }
        }
    }

    val blobCache: FileBlobCache by lazy {
        FileBlobCache(rootDir = File(cacheDir, "blob-cache"))
    }

    val timelineSnapshotCache: TimelineSnapshotCache by lazy {
        TimelineSnapshotCache(file = File(filesDir, "timeline-cache.json"))
    }

    val gatewayDeps: GatewayDeps by lazy {
        GatewayDeps(
            eventCache = eventCache,
            blobCache = blobCache,
            fetchRelayList = { pubkey, relays ->
                nostrClient.fetchOne(
                    relays,
                    Filter(
                        kinds = listOf(AdvertisedRelayListEvent.KIND),
                        authors = listOf(pubkey),
                    ),
                )
            },
            fetchManifest = { address, relays ->
                nostrClient.fetchManifest(address, relays)
            },
            fetchBlossomList = { pubkey, relays ->
                nostrClient.fetchOne(
                    relays,
                    Filter(
                        kinds = listOf(BlossomServersEvent.KIND),
                        authors = listOf(pubkey),
                    ),
                )
            },
            fetchBlob = { sha256, servers ->
                blossomFetcher.fetchBlob(sha256, servers)?.let {
                    BlobFetchResult(it.bytes, it.contentType)
                }
            },
        )
    }

    val sessionRegistry: SessionRegistry by lazy { SessionRegistry(gatewayDeps) }

    override fun onTerminate() {
        sessionRegistry.closeAllSessions()
        runBlocking { eventCache.close() }
        super.onTerminate()
    }
}
