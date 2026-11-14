package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import es.sebas1705.ilabs.evals.Status
import es.sebas1705.ilabs.evals.contract.checkOutcome
import es.sebas1705.ilabs.structured.IssueCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EvalsViewModelTest {
    /** Reads the contract fixtures straight from the repo, so no Android assets are needed. */
    private val loader = FixtureLoader {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/evals") }
            .first { it.isDirectory }
        dir.listFiles { f -> f.extension == "json" }!!.map { it.readText() }
    }

    /** Number of fixture files on disk; the lower bound keeps an empty directory from passing vacuously. */
    private val fixtureCount: Int = loader.load().size.also { assertTrue("expected at least 162 fixtures, got $it", it >= 162) }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = EvalsViewModel(loader, handle)

    @Test
    fun listsAllFixturesSorted() {
        val names = viewModel().uiState.value.names
        assertEquals(fixtureCount, names.size)
        assertEquals(names.sorted(), names)
    }

    @Test
    fun groupsFixturesByNameAndKeepsEveryFixtureInExactlyOneGroup() {
        assertEquals(EvalsGroup.STRING, EvalsGroup.of("equals-exact"))
        assertEquals(EvalsGroup.STRING, EvalsGroup.of("string-graders-ascii-fold-all-kinds"))
        assertEquals(EvalsGroup.LENGTH, EvalsGroup.of("length-bounds-inclusive"))
        assertEquals(EvalsGroup.JSON, EvalsGroup.of("jsonequals-match"))
        assertEquals(EvalsGroup.JUDGE, EvalsGroup.of("judge-pass"))
        assertEquals(EvalsGroup.RUN, EvalsGroup.of("status-fail-beats-pass"))
        assertEquals(EvalsGroup.RUN, EvalsGroup.of("summary-pass-rate-zero"))
        assertEquals(EvalsGroup.INVALID, EvalsGroup.of("invalid-empty-cases"))
        assertEquals(EvalsGroup.OTHER, EvalsGroup.of("something-else"))
        val groups = viewModel().uiState.value.groups
        assertEquals(groups.map { it.first }.sortedBy { it.ordinal }, groups.map { it.first })
        assertEquals(fixtureCount, groups.sumOf { it.second.size })
        assertTrue(groups.first { it.first == EvalsGroup.INVALID }.second.all { it.startsWith("invalid-") })
        assertTrue("no fixture should fall in OTHER", groups.none { it.first == EvalsGroup.OTHER })
    }

    @Test
    fun failBeatsPassExposesTheFailCellAndItsGraderDetails() {
        val vm = viewModel()
        vm.select("status-fail-beats-pass")
        val state = vm.uiState.value
        val run = state.run!!
        assertNull(state.invalidSuite)
        assertEquals(listOf("a"), run.targets)
        assertEquals(listOf("c1"), run.cases.map { it.id })
        val cell = run.result("c1", "a")!!
        assertEquals(Status.FAIL, cell.status)
        assertEquals("hello there", cell.reply)
        assertEquals(listOf("contains" to true, "length" to false), cell.graders.map { it.kind to it.passed })
        val summary = run.summary.single()
        assertEquals(0, summary.passed)
        assertEquals(1, summary.failed)
        assertEquals(mapOf("a" to 1), run.calls)

        assertNull(state.cell)
        vm.selectCell("c1", "a")
        val selected = vm.uiState.value
        assertEquals(CellRef("c1", "a"), selected.cell)
        assertEquals(cell, selected.cellResult)
        assertEquals("c1", selected.cellCase!!.id)
    }

    @Test
    fun providerErrorExposesTheErrorCellWithoutGraders() {
        val vm = viewModel()
        vm.select("provider-error-skips-graders")
        val run = vm.uiState.value.run!!
        val cell = run.result("c1", "a")!!
        assertEquals(Status.ERROR, cell.status)
        assertEquals("timeout", cell.error)
        assertNull(cell.reply)
        assertTrue(cell.graders.isEmpty())
        assertEquals(1, run.summary.single().errored)
    }

    @Test
    fun judgeFixtureReportsTheJudgeCallCount() {
        val vm = viewModel()
        vm.select("judge-pass")
        assertEquals(1, vm.uiState.value.run!!.judgeCalls)
        vm.select("status-fail-beats-pass")
        assertNull(vm.uiState.value.run!!.judgeCalls)
        vm.select("calls-judge-key-zero")
        assertEquals(0, vm.uiState.value.run!!.judgeCalls)
    }

    @Test
    fun invalidSuiteFixturesExposeTheValidationStateAndNoRun() {
        val vm = viewModel()
        for (name in vm.uiState.value.names.filter { it.startsWith("invalid-") }) {
            vm.select(name)
            val state = vm.uiState.value
            assertNull(name, state.runError)
            assertNull(name, state.run)
            assertTrue(name, state.invalidSuite!!.startsWith("invalid suite:"))
            assertNull(name, state.cellResult)
        }
    }

    @Test
    fun emptyRunHasCasesButNoTargetsAndNoCells() {
        val vm = viewModel()
        vm.select("no-targets-empty-run")
        val run = vm.uiState.value.run!!
        assertTrue(run.targets.isEmpty())
        assertTrue(run.results.isEmpty())
        assertTrue(run.summary.isEmpty())
        assertTrue(run.cases.isNotEmpty())
    }

    @Test
    fun multiTargetMatrixKeepsTargetAndCaseOrder() {
        val vm = viewModel()
        vm.select("multi-target-order")
        val run = vm.uiState.value.run!!
        assertTrue(run.targets.size >= 2)
        assertEquals(run.targets, run.summary.map { it.targetId })
        for (case in run.cases) for (target in run.targets) assertNotNull(run.result(case.id, target))
        assertNull(run.result("nope", run.targets.first()))
    }

    @Test
    fun selectingAnotherFixtureClearsTheSelectedCell() {
        val vm = viewModel()
        vm.select("status-fail-beats-pass")
        vm.selectCell("c1", "a")
        vm.select("provider-error-skips-graders")
        assertNull(vm.uiState.value.cell)
    }

    @Test
    fun selectingAnUnknownCellIsIgnoredAndClearCellWorks() {
        val vm = viewModel()
        vm.select("status-fail-beats-pass")
        vm.selectCell("ghost", "a")
        assertNull(vm.uiState.value.cell)
        vm.selectCell("c1", "a")
        vm.clearCell()
        assertNull(vm.uiState.value.cell)
    }

    // ---- playground ----

    @Test
    fun groupsAreTheSixOfTheWebPageWithRunFirst() {
        val groups = viewModel().uiState.value.groups.map { it.first }
        assertEquals(
            listOf(EvalsGroup.RUN, EvalsGroup.STRING, EvalsGroup.LENGTH, EvalsGroup.JSON, EvalsGroup.JUDGE, EvalsGroup.INVALID),
            groups,
        )
    }

    @Test
    fun theCellDetailKnowsEachGradersParameters() {
        val vm = viewModel()
        vm.select("status-fail-beats-pass")
        assertEquals(listOf("""{"value":"hello"}""", """{"max":3}"""), vm.uiState.value.run!!.graderParams["c1"])
        vm.select("judge-pass")
        assertTrue(vm.uiState.value.run!!.graderParams.getValue("c1").single().startsWith("""{"rubric":"""))
    }

    @Test
    fun playgroundStartsLikeTheWebPage() {
        val p = viewModel().uiState.value.playground
        assertEquals(PlaygroundKind.EQUALS, p.kind)
        assertEquals("hello", p.param)
        assertEquals("Hello", p.reply)
        assertFalse(p.verdict!!.passed)
    }

    @Test
    fun switchingKindNeverShowsTheTextOfAnotherKind() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.CONTAINS)
        vm.setParam("typed for contains")
        vm.setGraderKind(PlaygroundKind.JSON)
        // The schema field keeps its own sample, so there is a verdict and no "not valid JSON" error.
        assertNull(vm.uiState.value.playground.error)
        assertNotNull(vm.uiState.value.playground.verdict)
        vm.setGraderKind(PlaygroundKind.JSON_EQUALS)
        assertNull(vm.uiState.value.playground.error)
        vm.setGraderKind(PlaygroundKind.ONE_OF)
        assertEquals("yes\nno", vm.uiState.value.playground.param)
        // Going back finds the text typed before.
        vm.setGraderKind(PlaygroundKind.CONTAINS)
        assertEquals("typed for contains", vm.uiState.value.playground.param)
    }

    @Test
    fun playgroundVerdictChangesWithTheReply() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.CONTAINS)
        vm.setParam("hello")
        vm.setReply("hello world")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setReply("goodbye")
        val p = vm.uiState.value.playground
        assertFalse(p.verdict!!.passed)
        assertNull(p.error)
    }

    @Test
    fun playgroundOptionsApplyTheContractSemantics() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.EQUALS)
        vm.setParam("Yes")
        vm.setReply("  yes\n")
        assertFalse(vm.uiState.value.playground.verdict!!.passed)
        vm.setTrim(true)
        assertFalse(vm.uiState.value.playground.verdict!!.passed)
        vm.setIgnoreCase(true)
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        // Only ASCII letters fold: the sharp s never matches "ss".
        vm.setParam("STRASSE")
        vm.setReply("straße")
        assertFalse(vm.uiState.value.playground.verdict!!.passed)
    }

    @Test
    fun playgroundOneOfTakesOneValuePerLine() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.ONE_OF)
        vm.setParam("red\ngreen\nblue")
        vm.setReply("green")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setReply("yellow")
        assertFalse(vm.uiState.value.playground.verdict!!.passed)
        // Empty text is the single value "": the library accepts it, so there is a verdict and no error of ours.
        vm.setParam("")
        vm.setReply("")
        val p = vm.uiState.value.playground
        assertNull(p.error)
        assertTrue(p.verdict!!.passed)
        // CRLF-separated lines split cleanly.
        vm.setParam("red\r\ngreen")
        vm.setReply("green")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
    }

    @Test
    fun playgroundLengthCountsCodePointsAndValidatesBounds() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.LENGTH)
        vm.setMin("")
        vm.setMax("")
        fun rejected(): ParamError = vm.uiState.value.playground.error!!.also {
            assertEquals(ParamErrorCode.INVALID_PARAMS, it.code)
            assertTrue(it.detail!!.startsWith("invalid suite:"))
        }
        rejected()
        vm.setMax("2")
        vm.setReply("😀😀")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setReply("abc")
        assertFalse(vm.uiState.value.playground.verdict!!.passed)
        // The library judges every bound: min > max, negative, fractional, not a number.
        for (min in listOf("5", "-1", "1.5", "abc", "0x10", "Infinity")) {
            vm.setMin(min)
            rejected()
        }
        vm.setMin("")
        vm.setMax("abc")
        rejected()
    }

    @Test
    fun playgroundLengthBoundsFollowTheLibraryForNumbersAsTyped() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.LENGTH)
        vm.setReply("abc")
        // `2.0`, `1e1` and `+3` are whole numbers to the library (+3 is JavaScript's Number("+3")).
        vm.setMin("2.0")
        vm.setMax("")
        assertNull(vm.uiState.value.playground.error)
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setMin("")
        vm.setMax("1e1")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setMax("+2")
        assertFalse(vm.uiState.value.playground.verdict!!.passed)
        // A huge bound is a valid bound; min above it is still rejected by the library.
        vm.setMax("99999999999999999999")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setMin("99999999999999999999999")
        assertEquals(ParamErrorCode.INVALID_PARAMS, vm.uiState.value.playground.error!!.code)
        vm.setMin(" 1 ")
        assertNull(vm.uiState.value.playground.error)
    }

    @Test
    fun playgroundJsonGraderShowsIssuesAndABadSchemaIsAParameterError() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.JSON)
        vm.setParam("""{"type":"object","required":["name"]}""")
        vm.setReply("""Here: {"age": 3}""")
        val failing = vm.uiState.value.playground
        assertFalse(failing.verdict!!.passed)
        assertEquals(listOf("/name" to IssueCode.REQUIRED), failing.verdict.issues!!.map { it.path to it.code })
        vm.setReply("""{"name": "x"}""")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setReply("no json here")
        assertEquals(listOf("" to IssueCode.NO_JSON), vm.uiState.value.playground.verdict!!.issues!!.map { it.path to it.code })

        vm.setParam("{ not json")
        assertEquals(ParamErrorCode.INVALID_JSON, vm.uiState.value.playground.error!!.code)
        assertNull(vm.uiState.value.playground.verdict)
        vm.setParam("[1]")
        assertEquals(ParamErrorCode.INVALID_SCHEMA, vm.uiState.value.playground.error!!.code)
        vm.setParam("""{"type":"banana"}""")
        val bad = vm.uiState.value.playground.error!!
        assertEquals(ParamErrorCode.INVALID_SCHEMA, bad.code)
        assertTrue(bad.detail!!.isNotEmpty())
    }

    @Test
    fun playgroundJsonEqualsAcceptsNullAndRejectsMalformedText() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.JSON_EQUALS)
        vm.setParam("null")
        vm.setReply("null")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setParam("""{"a":[1,2]}""")
        vm.setReply("""{"a":[2,1]}""")
        val mismatch = vm.uiState.value.playground.verdict!!
        assertFalse(mismatch.passed)
        assertEquals(emptyList<Any>(), mismatch.issues)
        vm.setParam("")
        assertEquals(ParamErrorCode.INVALID_JSON, vm.uiState.value.playground.error!!.code)
    }

    @Test
    fun playgroundEmptyValuesBehaveAsPinnedByTheContract() {
        val vm = viewModel()
        vm.setGraderKind(PlaygroundKind.CONTAINS)
        vm.setParam("")
        vm.setReply("anything")
        assertTrue(vm.uiState.value.playground.verdict!!.passed)
        vm.setGraderKind(PlaygroundKind.NOT_CONTAINS)
        assertFalse(vm.uiState.value.playground.verdict!!.passed)
    }

    @Test
    fun playgroundKindsCoverEveryPureGraderAndNotJudge() {
        assertEquals(
            listOf("equals", "contains", "notContains", "startsWith", "endsWith", "oneOf", "length", "json", "jsonEquals"),
            PlaygroundKind.entries.map { it.wire },
        )
    }

    // ---- loading, errors, saved state ----

    @Test
    fun selectionCellAndPlaygroundSurviveRecreationThroughSavedStateHandle() {
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.select("status-fail-beats-pass")
        first.selectCell("c1", "a")
        first.setGraderKind(PlaygroundKind.ONE_OF)
        first.setParam("x\ny")
        first.setReply("y")
        first.setTrim(true)
        first.setIgnoreCase(true)
        first.setMin("3")
        first.setMax("9")
        val recreated = viewModel(handle)
        val state = recreated.uiState.value
        assertEquals("status-fail-beats-pass", state.selected)
        assertNotNull(state.run)
        assertEquals(CellRef("c1", "a"), state.cell)
        val p = state.playground
        assertEquals(PlaygroundKind.ONE_OF, p.kind)
        assertEquals("x\ny", p.param)
        assertEquals("y", p.reply)
        assertTrue(p.trim)
        assertTrue(p.ignoreCase)
        assertEquals("3", p.min)
        assertEquals("9", p.max)
        assertTrue(p.verdict!!.passed)
    }

    @Test
    fun aSavedCellThatNoLongerExistsIsDropped() {
        val handle = SavedStateHandle(mapOf("selected" to "status-fail-beats-pass", "cellCase" to "ghost", "cellTarget" to "a"))
        assertNull(viewModel(handle).uiState.value.cell)
    }

    @Test
    fun unknownSavedSelectionFallsBackToTheDefaultFixture() {
        val vm = viewModel(SavedStateHandle(mapOf("selected" to "does-not-exist", "cellCase" to "c1", "cellTarget" to "a")))
        assertEquals("status-fail-beats-pass", vm.uiState.value.selected)
        assertNull(vm.uiState.value.cell)
    }

    @Test
    fun aSlowRunOfTheFirstFixtureIsIgnoredAfterSelectingAnother() {
        val gate = kotlinx.coroutines.CompletableDeferred<es.sebas1705.ilabs.evals.contract.FixtureOutcome>()
        val vm = EvalsViewModel(
            loader,
            SavedStateHandle(mapOf("selected" to "status-fail-beats-pass")),
            runner = { fixture ->
                if (fixture.name == "status-fail-beats-pass") {
                    // Stays pending until released, then hands back a result for the wrong fixture.
                    gate.await()
                } else {
                    es.sebas1705.ilabs.evals.contract.runFixture(fixture)
                }
            },
        )
        assertNull(vm.uiState.value.run)
        vm.select("provider-error-skips-graders")
        val chosen = vm.uiState.value.run
        assertNotNull(chosen)
        // Release the slow run of fixture A after B is shown: nothing of A may replace B.
        val slow = es.sebas1705.ilabs.evals.contract.parseFixture(loader.load().first { "\"status-fail-beats-pass\"" in it })
        gate.complete(kotlinx.coroutines.runBlocking { es.sebas1705.ilabs.evals.contract.runFixture(slow) })
        val state = vm.uiState.value
        assertEquals("provider-error-skips-graders", state.selected)
        assertEquals(chosen, state.run)
        assertNull(state.runError)
    }

    @Test
    fun malformedFixtureAmongValidOnesKeepsTheValidOnesAndReportsPartialError() {
        val docs = loader.load() + "{ not json"
        val state = EvalsViewModel({ docs }, SavedStateHandle()).uiState.value
        assertEquals(fixtureCount, state.names.size)
        assertTrue(state.loadError!!.contains("#${fixtureCount + 1}"))
        assertNotNull(state.run)
    }

    @Test
    fun allBadOrLoaderFailureGivesAnEmptyListAndAnError() {
        val bad = EvalsViewModel({ listOf("x", "{}") }, SavedStateHandle()).uiState.value
        assertEquals(emptyList<String>(), bad.names)
        assertNull(bad.selected)
        assertNotNull(bad.loadError)
        val failed = EvalsViewModel({ error("assets missing") }, SavedStateHandle()).uiState.value
        assertTrue(failed.loadError!!.contains("assets missing"))
    }

    @Test
    fun runnerFailureEndsInErrorStateWithoutCrashing() {
        val vm = EvalsViewModel(loader, SavedStateHandle(), runner = { throw RuntimeException("boom") })
        val state = vm.uiState.value
        assertNull(state.run)
        assertNull(state.invalidSuite)
        assertTrue(state.runError!!.contains("boom"))
        assertEquals(fixtureCount, state.names.size)
    }

    @Test
    fun runnerCancellationPropagatesAndIsNotAnError() {
        val vm = EvalsViewModel(
            loader, SavedStateHandle(), runner = { throw kotlinx.coroutines.CancellationException("cancelled") },
        )
        assertNull(vm.uiState.value.runError)
    }

    @Test
    fun everyFixtureMatchesItsContractExpectations() {
        val vm = viewModel()
        assertTrue(vm.uiState.value.names.size >= 162)
        for (name in vm.uiState.value.names) {
            vm.select(name)
            val state = vm.uiState.value
            assertNull(name, state.runError)
            val problems = checkOutcome(state.fixture!!, state.outcome!!)
            assertTrue("$name: $problems", problems.isEmpty())
            // The screen's view agrees with the outcome the harness checked.
            assertEquals(name, state.outcome.invalidSuite, state.invalidSuite != null)
            assertEquals(name, state.outcome.output?.results, state.run?.results)
            assertEquals(name, state.outcome.output?.summary, state.run?.summary)
        }
    }
}
