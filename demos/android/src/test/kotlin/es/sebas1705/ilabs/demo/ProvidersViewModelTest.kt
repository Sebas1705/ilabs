package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import dev.ilabs.providers.ErrorKind
import dev.ilabs.providers.Event
import dev.ilabs.providers.FixtureResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersViewModelTest {
    /** Reads the contract fixtures straight from the repo, so no Android assets are needed. */
    private val loader = FixtureLoader {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/providers") }
            .first { it.isDirectory }
        dir.listFiles { f -> f.extension == "json" }!!.map { it.readText() }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = ProvidersViewModel(handle, loader)

    @Test
    fun listsAllThirteenFixturesSorted() {
        val names = viewModel().uiState.value.names
        assertEquals(13, names.size)
        assertEquals(names.sorted(), names)
    }

    @Test
    fun retryThenSuccessExposesCallWaitCall() {
        val vm = viewModel()
        vm.select("retry-then-success")
        val state = vm.uiState.value
        assertEquals("retry-then-success", state.selected)
        assertEquals(
            listOf(Event.Call("a", "rate_limit"), Event.Wait(100), Event.Call("a", "ok")),
            state.outcome?.events,
        )
        assertEquals(FixtureResult.Success("ok", "a"), state.outcome?.result)
        assertEquals(mapOf("a" to 2), state.outcome?.calls)
    }

    @Test
    fun fallbackEmptyExposesUnavailable() {
        val vm = viewModel()
        vm.select("fallback-empty")
        val result = vm.uiState.value.outcome?.result
        assertEquals(FixtureResult.Failure(ErrorKind.UNAVAILABLE), result)
    }

    @Test
    fun selectionSurvivesRecreationThroughSavedStateHandle() {
        val handle = SavedStateHandle()
        viewModel(handle).select("fallback-second-wins")
        val recreated = viewModel(handle)
        assertEquals("fallback-second-wins", recreated.uiState.value.selected)
        assertNotNull(recreated.uiState.value.outcome)
    }

    @Test
    fun unknownSavedSelectionFallsBackToFirstFixture() {
        val vm = viewModel(SavedStateHandle(mapOf("selected" to "does-not-exist")))
        assertEquals(vm.uiState.value.names.first(), vm.uiState.value.selected)
    }

    @Test
    fun everyFixtureMatchesItsContractExpectations() {
        val vm = viewModel()
        for (name in vm.uiState.value.names) {
            vm.select(name)
            val state = vm.uiState.value
            val fixture = state.fixture!!
            val outcome = state.outcome!!
            assertEquals(name, fixture.expect.calls, outcome.calls)
            assertEquals(name, fixture.expect.sleeps, outcome.sleeps)
            val expected = fixture.expect.result
            val actual = outcome.result
            if (expected.error != null) {
                assertEquals(name, expected.error!!.kind, (actual as FixtureResult.Failure).kind)
            } else {
                assertEquals(name, FixtureResult.Success(expected.text!!, expected.provider!!), actual)
            }
        }
    }

    private fun realDocs(): List<String> = loader.load()

    @Test
    fun badFixtureAmongGoodOnesKeepsTheValidOnesAndReportsPartialError() {
        val docs = realDocs() + "{ not json"
        val vm = ProvidersViewModel(SavedStateHandle(), { docs })
        val state = vm.uiState.value
        assertEquals(13, state.names.size)
        assertNotNull(state.loadError)
        assertEquals(true, state.loadError!!.contains("#14"))
        vm.select("retry-then-success")
        assertEquals(FixtureResult.Success("ok", "a"), vm.uiState.value.outcome?.result)
    }

    @Test
    fun badFixtureErrorNamesTheFixtureWhenItHasAName() {
        val docs = realDocs() + """{"name": "broken-one", "setup": 5}"""
        val state = ProvidersViewModel(SavedStateHandle(), { docs }).uiState.value
        assertEquals(true, state.loadError!!.contains("broken-one"))
    }

    @Test
    fun allFixturesBadGivesEmptyListAndError() {
        val state = ProvidersViewModel(SavedStateHandle(), { listOf("x", "{}") }).uiState.value
        assertEquals(emptyList<String>(), state.names)
        assertEquals(null, state.selected)
        assertNotNull(state.loadError)
    }

    @Test
    fun loaderFailureGivesEmptyListAndError() {
        val state = ProvidersViewModel(SavedStateHandle(), { error("assets missing") }).uiState.value
        assertEquals(emptyList<String>(), state.names)
        assertEquals(true, state.loadError!!.contains("assets missing"))
    }

    @Test
    fun runnerFailureEndsInErrorStateWithoutCrashing() {
        val vm = ProvidersViewModel(SavedStateHandle(), loader, runner = { throw RuntimeException("boom") })
        val state = vm.uiState.value
        assertEquals(null, state.outcome)
        assertEquals(true, state.runError!!.contains("boom"))
        assertEquals(13, state.names.size)
    }

    @Test
    fun runnerCancellationPropagatesAndIsNotAnError() {
        val vm = ProvidersViewModel(
            SavedStateHandle(), loader,
            runner = { throw kotlinx.coroutines.CancellationException("cancelled") },
        )
        assertEquals(null, vm.uiState.value.runError)
    }
}
