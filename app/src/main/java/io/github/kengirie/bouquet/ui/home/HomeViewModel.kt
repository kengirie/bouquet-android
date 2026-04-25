package io.github.kengirie.bouquet.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import io.github.kengirie.bouquet.BouquetApplication
import io.github.kengirie.bouquet.config.Defaults
import io.github.kengirie.bouquet.core.SiteAddress
import io.github.kengirie.bouquet.nostr.WriteRelaysResult
import io.github.kengirie.bouquet.nostr.fetchBlossomServers
import io.github.kengirie.bouquet.nostr.fetchManifest
import io.github.kengirie.bouquet.nostr.fetchWriteRelays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selects which surface a successful resolution should launch into. The
 * resolve pipeline (write relays → manifest → Blossom servers) is the
 * same for both targets — only the final one-shot navigation event
 * differs.
 */
enum class OpenTarget { Browser, WebView }

/**
 * UI projection of the Home screen state. Top-level holder consumed by
 * [HomeScreen] via `collectAsStateWithLifecycle`.
 */
data class HomeUiState(
    val input: String = "",
    val decodeResult: DecodeResult = DecodeResult.Idle,
    val resolution: ResolutionState = ResolutionState.Idle,
)

/**
 * Result of decoding the user's nostr address input. The [Idle] case
 * indicates that no decode has been attempted yet (or the input has been
 * cleared).
 */
sealed class DecodeResult {
    data object Idle : DecodeResult()

    data class Success(
        val address: SiteAddress,
        val display: AddressDisplay,
    ) : DecodeResult()

    data class Failure(val message: String) : DecodeResult()
}

/**
 * UI projection of a [SiteAddress]: pre-formatted strings for the success
 * panel. `identifier` and `kind` are populated only for [SiteAddress.Named]
 * inputs; relay hints are stringified to avoid leaking Quartz types into
 * the UI layer.
 */
data class AddressDisplay(
    val typeLabel: String,
    val pubkey: String,
    val identifier: String?,
    val kind: Int?,
    val relayHints: List<String>,
)

/**
 * State machine for the "Open" flow. Transitions: Idle → InProgress(stage)
 * progresses through the three fetch stages, then either resets to Idle on
 * success (with a one-shot [HomeViewModel.HomeEvent.LaunchViewer] or
 * [HomeViewModel.HomeEvent.LaunchBrowser] event) or lands on [Failure]
 * which is rendered inline by HomeScreen.
 */
sealed class ResolutionState {
    data object Idle : ResolutionState()

    data class InProgress(val stage: Stage) : ResolutionState() {
        enum class Stage { FETCHING_WRITE_RELAYS, FETCHING_MANIFEST, FETCHING_BLOSSOM_SERVERS }
    }

    data class Failure(val message: String) : ResolutionState()
}

/**
 * Reports the current stage of an in-flight resolution. Implemented by the
 * ViewModel so the resolver can drive UI progress without taking a
 * dependency on Compose / state flows.
 */
fun interface ResolutionProgress {
    fun report(stage: ResolutionState.InProgress.Stage)
}

/**
 * Thrown by [DefaultSiteResolver] when the manifest event cannot be found
 * for the given [SiteAddress]. Bubbles up to [HomeViewModel.onOpenClick]'s
 * catch block and is rendered as an inline [ResolutionState.Failure].
 */
class SiteNotFoundException(message: String) : Exception(message)

/**
 * Abstracts the Nostr-side resolution flow used by the "Open" button. The
 * default implementation drives the live [BouquetApplication] NostrClient
 * to populate the persistent caches that ViewerActivity will subsequently
 * read from; tests inject a fake.
 */
interface SiteResolver {
    suspend fun resolve(
        address: SiteAddress,
        progress: ResolutionProgress = ResolutionProgress {},
    )
}

/**
 * Production resolver: runs the full kind 10002 → manifest → kind 10063
 * pipeline against the live Quartz NostrClient. Each network call hops to
 * [Dispatchers.IO] explicitly even though `fetchOne` is suspending — the
 * underlying socket work isn't strictly main-safe and we want to mirror
 * the Phase 4-7 behavior. The fetches populate the persistent caches as a
 * side effect; nothing is returned because HomeScreen no longer renders a
 * manifest summary.
 */
class DefaultSiteResolver(private val app: BouquetApplication) : SiteResolver {
    override suspend fun resolve(
        address: SiteAddress,
        progress: ResolutionProgress,
    ) {
        progress.report(ResolutionState.InProgress.Stage.FETCHING_WRITE_RELAYS)
        val lookupRelays: Set<NormalizedRelayUrl> =
            LinkedHashSet<NormalizedRelayUrl>().apply {
                addAll(address.relayHints)
                addAll(Defaults.DEFAULT_LOOKUP_RELAYS)
            }

        val writeResult: WriteRelaysResult = withContext(Dispatchers.IO) {
            app.nostrClient.fetchWriteRelays(
                pubkey = address.pubkey,
                lookupRelays = lookupRelays,
            )
        }

        progress.report(ResolutionState.InProgress.Stage.FETCHING_MANIFEST)
        val manifestRelays: Set<NormalizedRelayUrl> =
            writeResult.relays.toSet().ifEmpty { Defaults.DEFAULT_LOOKUP_RELAYS.toSet() }

        // Throw early if the manifest is missing — bubbles up as a
        // ResolutionState.Failure rendered inline by HomeScreen. We don't
        // need the manifest object itself; fetchManifest populates the
        // persistent cache that ViewerActivity will read on launch.
        withContext(Dispatchers.IO) {
            app.nostrClient.fetchManifest(address, manifestRelays)
        } ?: throw SiteNotFoundException(
            "Site not found. No manifest event was published for this address.",
        )

        progress.report(ResolutionState.InProgress.Stage.FETCHING_BLOSSOM_SERVERS)
        withContext(Dispatchers.IO) {
            app.nostrClient.fetchBlossomServers(
                pubkey = address.pubkey,
                relays = manifestRelays,
            )
        }
        // An empty Blossom server list is fine — Gateway falls back to
        // built-in defaults.
    }
}

/**
 * State holder for [HomeScreen]. Owns the user input string (persisted
 * across process death via [SavedStateHandle]) plus the transient
 * [DecodeResult] / [ResolutionState] that drive the UI panels.
 *
 * The "open" coroutine is launched into [viewModelScope] so it is
 * automatically cancelled when the ViewModel is cleared — no manual
 * `Job.cancel` housekeeping needed. On success, a one-shot
 * [HomeEvent.LaunchViewer] (for [OpenTarget.WebView]) or
 * [HomeEvent.LaunchBrowser] (for [OpenTarget.Browser]) is sent on
 * [events]; HomeScreen collects this via `LaunchedEffect` and starts the
 * [io.github.kengirie.bouquet.viewer.ViewerActivity] or dispatches an
 * `Intent.ACTION_VIEW` accordingly.
 */
class HomeViewModel(
    private val savedState: SavedStateHandle,
    private val resolver: SiteResolver,
) : ViewModel() {

    /**
     * One-shot side-effect events emitted by the ViewModel. The two
     * launch events differ only in destination — [LaunchViewer] starts
     * the in-app [io.github.kengirie.bouquet.viewer.ViewerActivity] and
     * [LaunchBrowser] dispatches an `Intent.ACTION_VIEW` to the system
     * browser. Modeled as a sealed class so adding more (e.g. analytics,
     * snackbars) doesn't ripple through the API.
     */
    sealed class HomeEvent {
        data class LaunchViewer(val addressSegment: String) : HomeEvent()
        data class LaunchBrowser(val addressSegment: String) : HomeEvent()
    }

    private val _uiState = MutableStateFlow(
        HomeUiState(
            input = savedState.get<String>(KEY_INPUT) ?: "",
        ),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // BUFFERED so emitting from the producer never suspends. HomeScreen's
    // single LaunchedEffect collector consumes events; we never expect
    // multiple concurrent collectors.
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    fun onInputChange(newInput: String) {
        savedState[KEY_INPUT] = newInput
        val trimmed = newInput.trim()
        // Empty input intentionally maps to Idle (not a Failure). With
        // auto-decode firing on every keystroke, surfacing the
        // "Please enter a nostr address." error as soon as the user
        // clears the field would be noisy; the panel simply hides
        // instead. Non-empty inputs are routed through the standard
        // parse logic.
        val decoded = if (trimmed.isEmpty()) {
            DecodeResult.Idle
        } else {
            decodeInput(trimmed)
        }
        _uiState.update {
            it.copy(
                input = newInput,
                decodeResult = decoded,
                // Any prior in-flight or failed resolution is cleared on
                // input change — typing a new address invalidates the
                // previous Open attempt.
                resolution = ResolutionState.Idle,
            )
        }
    }

    fun onOpenClick(target: OpenTarget) {
        val decoded = (_uiState.value.decodeResult as? DecodeResult.Success) ?: return
        // Idempotency: ignore re-clicks while a resolution is in flight.
        if (_uiState.value.resolution is ResolutionState.InProgress) return

        _uiState.update {
            it.copy(
                resolution = ResolutionState.InProgress(
                    ResolutionState.InProgress.Stage.FETCHING_WRITE_RELAYS,
                ),
            )
        }

        viewModelScope.launch {
            try {
                resolver.resolve(decoded.address) { stage ->
                    _uiState.update {
                        it.copy(resolution = ResolutionState.InProgress(stage))
                    }
                }
                // On success: drop back to Idle and emit the one-shot
                // navigation event with the trimmed raw input — Gateway
                // re-decodes the bech32 string on the other side, mirroring
                // the desktop's `bouquet:open-site` URL contract.
                val segment = _uiState.value.input.trim()
                _uiState.update { it.copy(resolution = ResolutionState.Idle) }
                val event = when (target) {
                    OpenTarget.WebView -> HomeEvent.LaunchViewer(segment)
                    OpenTarget.Browser -> HomeEvent.LaunchBrowser(segment)
                }
                _events.send(event)
            } catch (e: CancellationException) {
                // Cooperative cancellation must propagate so viewModelScope
                // can clean up. Don't lump it in with the generic catch.
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        resolution = ResolutionState.Failure(
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun decodeInput(rawInput: String): DecodeResult =
        when (val outcome = SiteAddress.parse(rawInput)) {
            is SiteAddress.ParseOutcome.Success ->
                DecodeResult.Success(outcome.address, outcome.address.toDisplay())
            is SiteAddress.ParseOutcome.Failure ->
                DecodeResult.Failure(outcome.error.message)
        }

    companion object {
        private const val KEY_INPUT = "input"

        /**
         * Factory that pulls the [BouquetApplication] from the standard
         * `AndroidViewModelFactory.APPLICATION_KEY` and constructs a
         * [DefaultSiteResolver] for production use. Tests construct the
         * ViewModel directly with a fake resolver.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as BouquetApplication
                val saved = this.createSavedStateHandle()
                HomeViewModel(saved, DefaultSiteResolver(app))
            }
        }
    }
}

private fun SiteAddress.toDisplay(): AddressDisplay = when (this) {
    is SiteAddress.Root -> AddressDisplay(
        typeLabel = if (relayHints.isEmpty()) "NPub" else "NProfile",
        pubkey = pubkey,
        identifier = null,
        kind = null,
        relayHints = relayHints.map { it.url },
    )
    is SiteAddress.Named -> AddressDisplay(
        typeLabel = "NAddress",
        pubkey = pubkey,
        identifier = identifier,
        kind = NamedSiteEvent.KIND,
        relayHints = relayHints.map { it.url },
    )
}
