package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import es.sebas1705.ilabs.context.BudgetTooSmallException
import es.sebas1705.ilabs.context.WindowOptions
import es.sebas1705.ilabs.context.defaultCounter
import es.sebas1705.ilabs.context.slidingWindow
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import es.sebas1705.ilabs.context.contract.Fixture

@OptIn(ExperimentalCoroutinesApi::class)
class ContextViewModelTest {
    /** Reads the contract fixtures straight from the repo, so no Android assets are needed. */
    private val loader = FixtureLoader {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/context") }
            .first { it.isDirectory }
        dir.listFiles { f -> f.extension == "json" }!!.map { it.readText() }
    }

    /** Number of fixture files on disk; the lower bound keeps an empty directory from passing vacuously. */
    private val fixtureCount: Int = loader.load().size.also { assertTrue("expected at least 48 fixtures, got $it", it >= 48) }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = ContextViewModel(loader, handle)

    private fun ContextViewModel.at(name: String): ContextUiState {
        select(name)
        return uiState.value
    }

    private fun costs(messages: List<Message>) = messages.map(defaultCounter)

    @Test
    fun listsAllFixturesSorted() {
        val names = viewModel().uiState.value.names
        assertEquals(fixtureCount, names.size)
        assertEquals(names.sorted(), names)
    }

    @Test
    fun groupsFixturesByPrefix() {
        assertEquals(ContextGroup.WINDOW, ContextGroup.of("window-empty-list"))
        assertEquals(ContextGroup.SUMMARIZE, ContextGroup.of("summarize-one-summary"))
        assertEquals(ContextGroup.OTHER, ContextGroup.of("something-else"))
        val groups = viewModel().uiState.value.groups
        assertEquals(listOf(ContextGroup.WINDOW, ContextGroup.SUMMARIZE), groups.map { it.first })
        assertEquals(fixtureCount, groups.sumOf { it.second.size })
        assertTrue(groups.first { it.first == ContextGroup.SUMMARIZE }.second.all { it.startsWith("summarize-") })
    }

    @Test
    fun windowFixtureStartsAtItsOwnBudgetWithTheExpectedLiteral() {
        val state = viewModel().at("window-drops-oldest")
        assertEquals(30, state.budget)
        assertEquals(42, state.budgetMax)
        assertTrue(state.budgetEditable)
        val view = state.view!!
        // Literals from the fixture's expectation at its own budget.
        assertEquals(42, view.tokensBefore)
        assertEquals(28, view.tokensAfter)
        assertEquals(2, view.droppedCount)
        assertEquals(
            listOf(MessageState.KEPT, MessageState.DROPPED, MessageState.DROPPED, MessageState.KEPT, MessageState.KEPT, MessageState.KEPT),
            view.rows.map { it.state },
        )
        assertEquals(listOf(7, 7, 7, 7, 7, 7), view.rows.map { it.cost })
        assertNull(view.requiredTokens)
        assertNull(view.summarizerRequest)
    }

    @Test
    fun everyBudgetRecomputesKeptAndDroppedRowsExactlyAsSlidingWindow() {
        val vm = viewModel()
        val state = vm.at("window-drops-oldest")
        val messages = state.fixture!!.messages
        val total = costs(messages).sum()
        for (budget in 1..total) {
            vm.setBudget(budget)
            val ui = vm.uiState.value
            assertEquals(budget, ui.budget)
            val view = ui.view!!
            val expected = try {
                slidingWindow(messages, WindowOptions(budget))
            } catch (e: BudgetTooSmallException) {
                assertEquals("budget $budget", e.required, view.requiredTokens)
                assertTrue(view.rows.all { it.state == null })
                continue
            }
            assertNull(view.requiredTokens)
            assertEquals("budget $budget", expected.messages, view.rows.filter { it.state == MessageState.KEPT }.map { it.message })
            assertEquals("budget $budget", expected.droppedCount, view.droppedCount)
            assertEquals("budget $budget", expected.droppedCount, view.rows.count { it.state == MessageState.DROPPED })
            assertEquals("budget $budget", expected.tokensBefore, view.tokensBefore)
            assertEquals("budget $budget", expected.tokensAfter, view.tokensAfter)
            assertEquals(costs(messages), view.rows.map { it.cost })
        }
    }

    @Test
    fun aBudgetBelowTheRequiredOneExposesTheNeedsNStateInsteadOfClamping() {
        val vm = viewModel()
        val state = vm.at("window-last-too-big")
        val required = state.fixture!!.expect.result["error"]!!.jsonObject["required"]!!.jsonPrimitive.int
        assertEquals(required, state.view!!.requiredTokens)
        assertTrue(state.view.rows.all { it.state == null })
        // The budget stays as the fixture set it (or the slider range allows).
        assertTrue(state.budget in 1..state.budgetMax)
        vm.at("window-drops-oldest")
        vm.setBudget(5)
        val tooSmall = vm.uiState.value
        assertEquals(5, tooSmall.budget)
        // "Be brief." (7) plus the last message "User turn 3" (7).
        assertEquals(14, tooSmall.view!!.requiredTokens)
        vm.setBudget(14)
        assertNull(vm.uiState.value.view!!.requiredTokens)
    }

    @Test
    fun budgetIsClampedToTheSliderRangeButNeverToTheRequiredBudget() {
        val vm = viewModel()
        vm.at("window-drops-oldest")
        vm.setBudget(0)
        assertEquals(1, vm.uiState.value.budget)
        vm.setBudget(10_000)
        assertEquals(42, vm.uiState.value.budget)
        assertEquals(0, vm.uiState.value.view!!.droppedCount)
    }

    @Test
    fun initialBudgetIsClampedToTheTotalTokens() {
        val roomy = viewModel().at("window-fits-unchanged")
        assertEquals(minOf(roomy.fixture!!.setup.maxTokens, roomy.budgetMax), roomy.budget)
        assertTrue(roomy.budget in 1..roomy.budgetMax)
    }

    @Test
    fun summarizeFixtureExposesTheRequestTheSummaryAndTheCallsWithAFixedBudget() {
        val vm = viewModel()
        val state = vm.at("summarize-one-summary")
        assertFalse(state.budgetEditable)
        assertEquals(state.fixture!!.setup.maxTokens, state.budget)
        val view = state.view!!
        assertEquals(1, view.calls)
        assertTrue(view.summarized)
        assertEquals(1, view.summaryCalls)
        assertEquals(listOf(Role.SYSTEM, Role.USER), view.summarizerRequest!!.messages.map { it.role })
        val summaryContent = state.fixture.expect.result["messages"]!!.jsonArray
            .map { it.jsonObject["content"]!!.jsonPrimitive.content }
            .single { it.startsWith("Summary of the earlier conversation:\n") }
        assertEquals(summaryContent, view.summary!!.content)
        assertEquals(1, view.rows.count { it.state == MessageState.NEW_SUMMARY })
        assertTrue(view.rows.any { it.state == MessageState.REPLACED })
        // The slider is disabled: setBudget does nothing.
        vm.setBudget(1)
        assertEquals(state.fixture.setup.maxTokens, vm.uiState.value.budget)
        assertEquals(view, vm.uiState.value.view)
    }

    @Test
    fun summarizeFailureAfterTheCallStillShowsTheRequestAndTheNeededBudget() {
        val view = viewModel().at("summarize-call-then-budget-too-small-protected-heavy").view!!
        assertEquals(32, view.requiredTokens)
        assertEquals(1, view.calls)
        assertNotNull(view.summarizerRequest)
        assertNull(view.summary)
    }

    @Test
    fun summarizeProviderErrorExposesTheFailureKind() {
        val state = viewModel().at("summarize-provider-error")
        val kind = state.fixture!!.expect.result["error"]!!.jsonObject["kind"]!!.jsonPrimitive.content
        assertEquals(kind, state.view!!.failureKind)
        assertNull(state.view.requiredTokens)
    }

    @Test
    fun anEmptyConversationExposesTheEmptyState() {
        val state = viewModel().at("window-empty-list")
        assertTrue(state.view!!.rows.isEmpty())
        assertEquals(0, state.view.tokensBefore)
        assertEquals(0, state.view.tokensAfter)
        assertFalse(state.budgetEditable)
        assertTrue(viewModel().at("summarize-empty-list").view!!.rows.isEmpty())
    }

    @Test
    fun malformedFixtureAmongValidOnesKeepsTheValidOnesAndReportsPartialError() {
        val docs = loader.load() + "{ not json"
        val state = ContextViewModel({ docs }, SavedStateHandle()).uiState.value
        assertEquals(fixtureCount, state.names.size)
        assertTrue(state.loadError!!.contains("#${fixtureCount + 1}"))
        assertNotNull(state.view)
    }

    @Test
    fun allBadOrLoaderFailureGivesAnEmptyListAndAnError() {
        val bad = ContextViewModel({ listOf("x", "{}") }, SavedStateHandle()).uiState.value
        assertEquals(emptyList<String>(), bad.names)
        assertNull(bad.selected)
        assertNull(bad.view)
        assertNotNull(bad.loadError)
        val failed = ContextViewModel({ error("assets missing") }, SavedStateHandle()).uiState.value
        assertTrue(failed.loadError!!.contains("assets missing"))
    }

    @Test
    fun runnerFailureEndsInErrorStateWithoutCrashing() {
        val handle = SavedStateHandle(mapOf("selected" to "summarize-one-summary"))
        val vm = ContextViewModel(loader, handle, runner = { throw RuntimeException("boom") })
        val state = vm.uiState.value
        assertNull(state.view)
        assertTrue(state.runError!!.contains("boom"))
        assertEquals(fixtureCount, state.names.size)
        // Window fixtures never need the runner, so the screen recovers by picking one.
        assertNull(vm.at("window-drops-oldest").runError)
    }

    @Test
    fun runnerCancellationPropagatesAndIsNotAnError() {
        val handle = SavedStateHandle(mapOf("selected" to "summarize-one-summary"))
        var launched: Job? = null
        val vm = ContextViewModel(
            loader,
            handle,
            runner = {
                launched = currentCoroutineContext()[Job]
                throw CancellationException("cancelled")
            },
        )
        val state = vm.uiState.value
        // Not an error, and no outcome is published: the run is simply abandoned.
        assertNull(state.runError)
        assertNull(state.view)
        // The cancellation reached the coroutine machinery: a swallowed exception would leave the job completed normally.
        val job = launched!!
        assertTrue("the launched job must be cancelled", job.isCancelled)
        assertTrue("the launched job must be completed", job.isCompleted)
    }

    @Test
    fun selectionAndBudgetSurviveRecreationThroughSavedStateHandle() {
        val handle = SavedStateHandle()
        viewModel(handle).apply {
            select("window-drops-oldest")
            setBudget(33)
        }
        val recreated = viewModel(handle).uiState.value
        assertEquals("window-drops-oldest", recreated.selected)
        assertEquals(33, recreated.budget)
        assertEquals(slidingWindow(recreated.fixture!!.messages, WindowOptions(33)).droppedCount, recreated.view!!.droppedCount)
        viewModel(handle).select("summarize-incremental")
        val summarize = viewModel(handle).uiState.value
        assertEquals("summarize-incremental", summarize.selected)
        assertNotNull(summarize.view!!.summary)
    }

    @Test
    fun pickingAnotherFixtureResetsTheBudgetToItsOwn() {
        val vm = viewModel()
        vm.at("window-drops-oldest")
        vm.setBudget(10)
        val other = vm.at("window-fits-unchanged")
        assertEquals(minOf(other.fixture!!.setup.maxTokens, other.budgetMax), other.budget)
    }

    @Test
    fun unknownSavedSelectionFallsBackToTheDefaultFixtureAndAnOutOfRangeBudgetIsClamped() {
        val vm = viewModel(SavedStateHandle(mapOf("selected" to "does-not-exist", "budget" to 999)))
        assertEquals("window-drops-oldest", vm.uiState.value.selected)
        assertEquals(30, vm.uiState.value.budget)
        val clamped = viewModel(SavedStateHandle(mapOf("selected" to "window-drops-oldest", "budget" to 999)))
        assertEquals(42, clamped.uiState.value.budget)
    }

    @Test
    fun everyFixtureMatchesItsContractExpectations() {
        val vm = viewModel()
        for (name in vm.uiState.value.names) {
            val state = vm.at(name)
            assertNull(name, state.runError)
            val view = state.view!!
            val fx: Fixture = state.fixture!!
            val expect = fx.expect
            val want: JsonObject = expect.result
            val wantError = want["error"] as? JsonObject
            if (wantError != null) {
                val kind = wantError.getValue("kind").jsonPrimitive.content
                if (kind == "budget_too_small") {
                    assertEquals(name, wantError.getValue("required").jsonPrimitive.int, view.requiredTokens)
                    assertNull(name, view.failureKind)
                } else {
                    assertEquals(name, kind, view.failureKind)
                    assertNull(name, view.requiredTokens)
                }
                assertTrue(name, view.rows.all { it.state == null })
            } else {
                assertNull(name, view.requiredTokens)
                assertNull(name, view.failureKind)
                val messages = Json.decodeFromJsonElement(ListSerializer(Message.serializer()), want.getValue("messages"))
                assertEquals(name, want.getValue("droppedCount").jsonPrimitive.int, view.droppedCount)
                assertEquals(name, want.getValue("tokensBefore").jsonPrimitive.int, view.tokensBefore)
                assertEquals(name, want.getValue("tokensAfter").jsonPrimitive.int, view.tokensAfter)
                (want["summarized"] as? JsonPrimitive)?.let { assertEquals(name, it.boolean, view.summarized) }
                (want["summaryCalls"] as? JsonPrimitive)?.let { assertEquals(name, it.int, view.summaryCalls) }
                // Row states agree with the result: kept rows plus the new summary are exactly the output, in order.
                val shown = view.rows.filter { it.state == MessageState.KEPT || it.state == MessageState.NEW_SUMMARY }.map { it.message }
                assertEquals(name, messages, shown)
                assertEquals(
                    name,
                    view.droppedCount,
                    view.rows.count { it.state == MessageState.DROPPED || it.state == MessageState.REPLACED },
                )
            }
            expect.calls?.let { assertEquals(name, it, view.calls) }
            if (fx.setup.kind == "window") assertEquals(name, 0, view.calls)
            if (view.calls == 0) assertNull(name, view.summarizerRequest) else assertNotNull(name, view.summarizerRequest)
            val ownBudget = if (fx.setup.kind == "window") fx.setup.maxTokens.coerceIn(1, maxOf(1, state.budgetMax)) else fx.setup.maxTokens
            assertEquals(name, ownBudget, state.budget)
        }
    }
}
