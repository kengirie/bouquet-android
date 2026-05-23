package io.github.kengirie.bouquet.ui.home

import androidx.lifecycle.SavedStateHandle
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import io.github.kengirie.bouquet.core.SiteAddress
import io.github.kengirie.bouquet.core.encodePubkeyB36
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [HomeViewModel]. Exercises the auto-decode state
 * machine driven by [HomeViewModel.onInputChange] and the open/resolve
 * state machine + event emission using a pluggable [SiteResolver] so
 * no Nostr / OkHttp / NanoHTTPD I/O is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val pubkey = "a".repeat(64)

    // Encoded forms for the test pubkey. Computed once so we don't pay the
    // bech32/base36 cost per test.
    private val npub: String = NPub.create(pubkey)
    private val canonicalLabel: String = "${encodePubkeyB36(pubkey)!!}blog"

    /** Set Main dispatcher to a [StandardTestDispatcher] so viewModelScope launches there. */
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Resolver that runs the supplied lambda on each call. */
    private class FakeResolver(
        private val onResolve: suspend (SiteAddress, ResolutionProgress) -> Unit,
    ) : SiteResolver {
        var calls = 0
        override suspend fun resolve(
            address: SiteAddress,
            progress: ResolutionProgress,
        ) {
            calls++
            onResolve(address, progress)
        }
    }

    private fun viewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        resolver: SiteResolver = FakeResolver { _, _ -> /* succeed instantly */ },
    ): HomeViewModel = HomeViewModel(savedState, resolver)

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun `initial state is empty input and Idle results`() {
        val vm = viewModel()
        val state = vm.uiState.value
        assertEquals("", state.input)
        assertEquals(DecodeResult.Idle, state.decodeResult)
        assertEquals(ResolutionState.Idle, state.resolution)
    }

    @Test
    fun `empty input via onInputChange leaves decodeResult Idle`() {
        // Empty input deliberately maps to Idle rather than Failure: the
        // auto-decode would otherwise flash "Please enter a nostr address."
        // every time the user clears the field.
        val vm = viewModel()
        vm.onInputChange("")
        assertEquals(DecodeResult.Idle, vm.uiState.value.decodeResult)
    }

    @Test
    fun `garbage input via onInputChange produces Invalid parse error`() {
        val vm = viewModel()
        vm.onInputChange("not-a-real-bech32-string")
        val r = vm.uiState.value.decodeResult
        assertTrue("expected Failure, got $r", r is DecodeResult.Failure)
        assertEquals(
            "The address you used is invalid.",
            (r as DecodeResult.Failure).message,
        )
    }

    @Test
    fun `valid npub via onInputChange produces NPub display`() {
        val vm = viewModel()
        vm.onInputChange(npub)
        val r = vm.uiState.value.decodeResult
        assertTrue("expected Success, got $r", r is DecodeResult.Success)
        val s = r as DecodeResult.Success
        assertEquals("NPub", s.display.typeLabel)
        assertEquals(pubkey, s.display.pubkey)
        assertEquals(null, s.display.identifier)
    }

    @Test
    fun `valid canonical label via onInputChange produces Nsite display with identifier`() {
        val vm = viewModel()
        vm.onInputChange(canonicalLabel)
        val r = vm.uiState.value.decodeResult
        assertTrue("expected Success, got $r", r is DecodeResult.Success)
        val s = r as DecodeResult.Success
        assertEquals("Nsite", s.display.typeLabel)
        assertEquals(pubkey, s.display.pubkey)
        assertEquals("blog", s.display.identifier)
    }

    @Test
    fun `onOpenClick is a no-op when no decode has succeeded`() = runTest {
        val resolver = FakeResolver { _, _ -> /* succeed */ }
        val vm = viewModel(resolver = resolver)

        val collected = mutableListOf<HomeViewModel.HomeEvent>()
        val collectJob = launch { vm.events.collect { collected.add(it) } }

        vm.onOpenClick(OpenTarget.WebView)
        advanceUntilIdle()

        assertEquals(0, resolver.calls)
        assertEquals(ResolutionState.Idle, vm.uiState.value.resolution)
        assertTrue("expected no events, got $collected", collected.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun `onOpenClick is idempotent while in flight`() = runTest {
        // The resolver suspends on a deferred so we can deterministically
        // have an in-flight call when the second click arrives.
        val gate = CompletableDeferred<Unit>()
        val resolver = FakeResolver { _, progress ->
            progress.report(ResolutionState.InProgress.Stage.FETCHING_WRITE_RELAYS)
            gate.await()
        }
        val vm = viewModel(resolver = resolver)

        vm.onInputChange(npub)

        vm.onOpenClick(OpenTarget.WebView)
        // Let the launch coroutine start so resolve() is actually invoked.
        // (The first click only sets InProgress synchronously; the actual
        // resolver.resolve() suspends inside the launched coroutine.)
        testDispatcher.scheduler.runCurrent()

        // Second click while still in flight: should NOT invoke resolver again.
        vm.onOpenClick(OpenTarget.WebView)
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, resolver.calls)
        assertTrue(vm.uiState.value.resolution is ResolutionState.InProgress)

        // Now release the gate so the test cleans up.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ResolutionState.Idle, vm.uiState.value.resolution)
    }

    @Test
    fun `onInputChange writes through to SavedStateHandle`() {
        val saved = SavedStateHandle()
        val vm = viewModel(savedState = saved)
        vm.onInputChange("hello")
        assertEquals("hello", saved.get<String>("input"))
        assertEquals("hello", vm.uiState.value.input)
    }

    @Test
    fun `resolver throwing maps to ResolutionState Failure with the exception message`() = runTest {
        val resolver = FakeResolver { _, _ -> throw IllegalStateException("nope") }
        val vm = viewModel(resolver = resolver)

        // Collect events on the side: failure path must NOT emit one.
        val collected = mutableListOf<HomeViewModel.HomeEvent>()
        val collectJob = launch { vm.events.collect { collected.add(it) } }

        vm.onInputChange(npub)
        vm.onOpenClick(OpenTarget.WebView)
        advanceUntilIdle()

        val r = vm.uiState.value.resolution
        assertTrue("expected Failure, got $r", r is ResolutionState.Failure)
        assertEquals("nope", (r as ResolutionState.Failure).message)
        assertTrue(
            "failure path must not emit a launch event, got $collected",
            collected.isEmpty(),
        )
        collectJob.cancel()
    }

    @Test
    fun `successful resolution emits LaunchViewer event with trimmed input and resets to Idle`() = runTest {
        val resolver = FakeResolver { _, _ -> /* succeed instantly */ }
        val vm = viewModel(resolver = resolver)

        // Pad with whitespace to confirm the emitted segment is trimmed.
        val padded = "  $npub  "
        vm.onInputChange(padded)

        val collected = mutableListOf<HomeViewModel.HomeEvent>()
        val collectJob = launch { vm.events.collect { collected.add(it) } }

        vm.onOpenClick(OpenTarget.WebView)
        advanceUntilIdle()

        assertEquals(1, collected.size)
        val ev = collected.first()
        assertTrue("expected LaunchViewer, got $ev", ev is HomeViewModel.HomeEvent.LaunchViewer)
        assertEquals(npub, (ev as HomeViewModel.HomeEvent.LaunchViewer).addressSegment)
        // After success the state is intentionally reset to Idle — the
        // viewer activity carries the rest from here.
        assertEquals(ResolutionState.Idle, vm.uiState.value.resolution)
        collectJob.cancel()
    }

    @Test
    fun `WebView target emits LaunchViewer event with the trimmed input`() = runTest {
        val resolver = FakeResolver { _, _ -> /* succeed instantly */ }
        val vm = viewModel(resolver = resolver)
        vm.onInputChange(npub)

        val collected = mutableListOf<HomeViewModel.HomeEvent>()
        val collectJob = launch { vm.events.collect { collected.add(it) } }

        vm.onOpenClick(OpenTarget.WebView)
        advanceUntilIdle()

        assertEquals(1, collected.size)
        val ev = collected.first()
        assertTrue("expected LaunchViewer, got $ev", ev is HomeViewModel.HomeEvent.LaunchViewer)
        assertEquals(npub, (ev as HomeViewModel.HomeEvent.LaunchViewer).addressSegment)
        collectJob.cancel()
    }

    @Test
    fun `Browser target emits LaunchBrowser event with the trimmed input`() = runTest {
        val resolver = FakeResolver { _, _ -> /* succeed instantly */ }
        val vm = viewModel(resolver = resolver)
        vm.onInputChange(npub)

        val collected = mutableListOf<HomeViewModel.HomeEvent>()
        val collectJob = launch { vm.events.collect { collected.add(it) } }

        vm.onOpenClick(OpenTarget.Browser)
        advanceUntilIdle()

        assertEquals(1, collected.size)
        val ev = collected.first()
        assertTrue("expected LaunchBrowser, got $ev", ev is HomeViewModel.HomeEvent.LaunchBrowser)
        assertEquals(npub, (ev as HomeViewModel.HomeEvent.LaunchBrowser).addressSegment)
        collectJob.cancel()
    }

    @Test
    fun `onInputChange clears prior resolution Failure state`() = runTest {
        // Drive the VM into ResolutionState.Failure by letting the resolver
        // throw, then type a new address and verify the failure is cleared.
        val resolver = FakeResolver { _, _ -> throw IllegalStateException("kaboom") }
        val vm = viewModel(resolver = resolver)

        vm.onInputChange(npub)
        vm.onOpenClick(OpenTarget.WebView)
        advanceUntilIdle()
        assertTrue(
            "precondition: expected Failure, got ${vm.uiState.value.resolution}",
            vm.uiState.value.resolution is ResolutionState.Failure,
        )

        // Typing a new address should clear the failure and re-decode.
        vm.onInputChange(canonicalLabel)
        assertEquals(ResolutionState.Idle, vm.uiState.value.resolution)
        assertTrue(
            "decode should succeed for a valid canonical label",
            vm.uiState.value.decodeResult is DecodeResult.Success,
        )
    }
}
