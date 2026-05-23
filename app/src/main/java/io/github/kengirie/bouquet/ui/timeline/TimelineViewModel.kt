package io.github.kengirie.bouquet.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kengirie.bouquet.BouquetApplication
import io.github.kengirie.bouquet.cache.TimelineSnapshotCache
import io.github.kengirie.bouquet.nostr.DefaultTimelineRepository
import io.github.kengirie.bouquet.nostr.TimelineRepository
import io.github.kengirie.bouquet.ui.home.DefaultSiteResolver
import io.github.kengirie.bouquet.ui.home.OpenTarget
import io.github.kengirie.bouquet.ui.home.SiteResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the Timeline tab. Owns a single refresh coroutine and a
 * single resolve coroutine — concurrent calls to either are coalesced by
 * cancelling the in-flight job.
 *
 * Resolve-on-tap reuses the production [SiteResolver] from the Home flow
 * so both surfaces drive the same kind 10002 → manifest → kind 10063
 * fetch pipeline (and populate the same persistent caches that
 * ViewerActivity reads on launch).
 */
class TimelineViewModel(
    private val repository: TimelineRepository,
    private val resolver: SiteResolver,
    private val snapshotCache: TimelineSnapshotCache? = null,
) : ViewModel() {

    /**
     * One-shot side effects emitted by the ViewModel. Collected once by
     * [TimelineScreen] via `LaunchedEffect` so each event fires exactly
     * once. Mirrors [io.github.kengirie.bouquet.ui.home.HomeViewModel.HomeEvent].
     */
    sealed class TimelineEvent {
        data class LaunchViewer(val addressSegment: String) : TimelineEvent()
        data class LaunchBrowser(val addressSegment: String) : TimelineEvent()
        data class ResolveFailed(val eventId: String, val message: String) : TimelineEvent()
    }

    private val _uiState = MutableStateFlow<TimelineUiState>(TimelineUiState.Loading)
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    // BUFFERED so emitting never suspends — same rationale as HomeViewModel.
    private val _events = Channel<TimelineEvent>(Channel.BUFFERED)
    val events: Flow<TimelineEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var resolveJob: Job? = null

    init {
        // Cold start: try to surface a cached page immediately, then kick
        // off a network refresh in the background. If the cache is empty
        // we just stay in Loading until the network completes.
        viewModelScope.launch {
            val cached = snapshotCache?.load().orEmpty()
            if (cached.isNotEmpty() && _uiState.value is TimelineUiState.Loading) {
                _uiState.value = TimelineUiState.Loaded(
                    items = cached,
                    isRefreshing = true,
                    resolvingEventId = null,
                )
            }
            refresh()
        }
    }

    fun refresh() {
        loadJob?.cancel()
        val prior = _uiState.value
        _uiState.value = when (prior) {
            is TimelineUiState.Loaded -> prior.copy(isRefreshing = true)
            else -> TimelineUiState.Loading
        }
        loadJob = viewModelScope.launch {
            try {
                val items = repository.loadInitial()
                _uiState.value = if (items.isEmpty()) {
                    // Don't blow away an already-loaded snapshot just
                    // because the network returned empty — keep showing
                    // the cached page with refreshing cleared.
                    when (val cur = _uiState.value) {
                        is TimelineUiState.Loaded ->
                            cur.copy(isRefreshing = false, resolvingEventId = null)
                        else -> TimelineUiState.Empty
                    }
                } else {
                    TimelineUiState.Loaded(
                        items = items,
                        isRefreshing = false,
                        resolvingEventId = null,
                    )
                }
                if (items.isNotEmpty()) {
                    // Persist whatever the network just gave us so the
                    // next cold start has something to show.
                    snapshotCache?.save(items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep any already-loaded snapshot on the screen rather
                // than replacing it with an Error state — refresh failures
                // shouldn't destroy content. Fall through to Error only
                // when there's nothing to fall back to.
                _uiState.value = when (val cur = _uiState.value) {
                    is TimelineUiState.Loaded ->
                        cur.copy(isRefreshing = false)
                    else -> TimelineUiState.Error(
                        message = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /**
     * Run the resolve pipeline for [item] then emit a launch event. Only
     * meaningful when the screen is in [TimelineUiState.Loaded] — calls
     * from other states are dropped. Cancels any prior resolve in flight
     * so back-to-back taps on different cards always reflect the latest
     * tap.
     */
    fun onItemClick(item: TimelineItem, target: OpenTarget) {
        val loaded = _uiState.value as? TimelineUiState.Loaded ?: return
        if (loaded.resolvingEventId != null) return  // ignore reentrancy
        resolveJob?.cancel()
        _uiState.update {
            (it as? TimelineUiState.Loaded)
                ?.copy(resolvingEventId = item.entry.eventId) ?: it
        }
        resolveJob = viewModelScope.launch {
            try {
                resolver.resolve(item.entry.toSiteAddress())
                _uiState.update {
                    (it as? TimelineUiState.Loaded)?.copy(resolvingEventId = null) ?: it
                }
                val ev = when (target) {
                    OpenTarget.WebView ->
                        TimelineEvent.LaunchViewer(item.entry.addressSegment)
                    OpenTarget.Browser ->
                        TimelineEvent.LaunchBrowser(item.entry.addressSegment)
                }
                _events.send(ev)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    (it as? TimelineUiState.Loaded)?.copy(resolvingEventId = null) ?: it
                }
                _events.send(
                    TimelineEvent.ResolveFailed(
                        eventId = item.entry.eventId,
                        message = e.message ?: e.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as BouquetApplication
                TimelineViewModel(
                    repository = DefaultTimelineRepository(app.nostrClient),
                    resolver = DefaultSiteResolver(app),
                    snapshotCache = app.timelineSnapshotCache,
                )
            }
        }
    }
}

/**
 * UI state machine for the Timeline screen.
 *
 * [Loaded.isRefreshing] backs a top progress bar shown during refreshes
 * without blowing away the list. [Loaded.resolvingEventId] flags the
 * single card currently being resolved so the screen can overlay a
 * spinner on just that row.
 */
sealed class TimelineUiState {
    data object Loading : TimelineUiState()
    data object Empty : TimelineUiState()
    data class Loaded(
        val items: List<TimelineItem>,
        val isRefreshing: Boolean,
        val resolvingEventId: String? = null,
    ) : TimelineUiState()
    data class Error(val message: String) : TimelineUiState()
}
