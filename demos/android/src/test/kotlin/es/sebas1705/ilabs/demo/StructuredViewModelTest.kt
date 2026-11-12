package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import es.sebas1705.ilabs.providers.ErrorKind
import es.sebas1705.ilabs.providers.Role
import es.sebas1705.ilabs.structured.Extraction
import es.sebas1705.ilabs.structured.FixtureResult
import es.sebas1705.ilabs.structured.Issue
import es.sebas1705.ilabs.structured.IssueCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StructuredViewModelTest {
    /** Reads the contract fixtures straight from the repo, so no Android assets are needed. */
    private val loader = FixtureLoader {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/structured") }
            .first { it.isDirectory }
        dir.listFiles { f -> f.extension == "json" }!!.map { it.readText() }
    }

    /** Number of fixture files on disk; the lower bound keeps an empty directory from passing vacuously. */
    private val fixtureCount: Int = loader.load().size.also { assertTrue("expected at least 70 fixtures, got $it", it >= 70) }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = StructuredViewModel(loader, handle)

    @Test
    fun listsAllFixturesSorted() {
        val names = viewModel().uiState.value.names
        assertEquals(fixtureCount, names.size)
        assertEquals(names.sorted(), names)
    }

    @Test
    fun groupsFixturesByPrefix() {
        assertEquals(FixtureGroup.EXTRACT, FixtureGroup.of("extract-plain"))
        assertEquals(FixtureGroup.VALIDATE, FixtureGroup.of("validate-enum"))
        assertEquals(FixtureGroup.LOOP, FixtureGroup.of("loop-first-try"))
        assertEquals(FixtureGroup.OTHER, FixtureGroup.of("something-else"))
        val groups = viewModel().uiState.value.groups
        assertEquals(listOf(FixtureGroup.EXTRACT, FixtureGroup.VALIDATE, FixtureGroup.LOOP), groups.map { it.first })
        assertEquals(fixtureCount, groups.sumOf { it.second.size })
        assertTrue(groups.first { it.first == FixtureGroup.LOOP }.second.all { it.startsWith("loop-") })
    }

    @Test
    fun repairSecondExposesTwoTraceEntriesAndTheFinalValue() {
        val vm = viewModel()
        vm.select("loop-repair-second")
        val state = vm.uiState.value
        val outcome = state.outcome!!
        assertEquals(2, outcome.trace.size)
        assertEquals(2, outcome.attempts)
        assertEquals(listOf(Role.ASSISTANT, Role.USER), outcome.appended)
        val success = outcome.result as FixtureResult.Success
        assertTrue(jsonEqual(state.fixture!!.expect.result["value"]!!, success.value))
        assertTrue(outcome.trace.first().issues.isNotEmpty())
        assertTrue(outcome.trace.last().issues.isEmpty())
        assertNull(state.providerFailure)
    }

    @Test
    fun noJsonExposesTheExtractionAndAnInvalidOutputError() {
        val vm = viewModel()
        vm.select("extract-no-json")
        val outcome = vm.uiState.value.outcome!!
        assertEquals(Extraction.Failed(IssueCode.NO_JSON), outcome.trace.single().extracted)
        val failure = outcome.result as FixtureResult.Failure
        assertEquals(ErrorKind.INVALID_OUTPUT, failure.kind)
        assertEquals(listOf(Issue("", IssueCode.NO_JSON)), failure.issues)
        assertNull(vm.uiState.value.providerFailure)
    }

    @Test
    fun providerErrorMidLoopKeepsOneTraceEntryAndShowsAProviderFailure() {
        val vm = viewModel()
        vm.select("loop-provider-error-mid")
        val state = vm.uiState.value
        val outcome = state.outcome!!
        assertEquals(1, outcome.trace.size)
        assertEquals(2, outcome.attempts)
        val failure = outcome.result as FixtureResult.Failure
        assertEquals(ErrorKind.RATE_LIMIT, failure.kind)
        assertNull(failure.issues)
        assertEquals(ProviderFailure(attempt = 2, kind = ErrorKind.RATE_LIMIT), state.providerFailure)
    }

    @Test
    fun selectionSurvivesRecreationThroughSavedStateHandle() {
        val handle = SavedStateHandle()
        viewModel(handle).select("validate-enum")
        val recreated = viewModel(handle)
        assertEquals("validate-enum", recreated.uiState.value.selected)
        assertNotNull(recreated.uiState.value.outcome)
    }

    @Test
    fun unknownSavedSelectionFallsBackToTheDefaultFixture() {
        val vm = viewModel(SavedStateHandle(mapOf("selected" to "does-not-exist")))
        assertEquals("loop-repair-second", vm.uiState.value.selected)
    }

    @Test
    fun malformedFixtureAmongValidOnesKeepsTheValidOnesAndReportsPartialError() {
        val docs = loader.load() + "{ not json"
        val state = StructuredViewModel({ docs }, SavedStateHandle()).uiState.value
        assertEquals(fixtureCount, state.names.size)
        assertTrue(state.loadError!!.contains("#${fixtureCount + 1}"))
        assertNotNull(state.outcome)
    }

    @Test
    fun allBadOrLoaderFailureGivesAnEmptyListAndAnError() {
        val bad = StructuredViewModel({ listOf("x", "{}") }, SavedStateHandle()).uiState.value
        assertEquals(emptyList<String>(), bad.names)
        assertNull(bad.selected)
        assertNotNull(bad.loadError)
        val failed = StructuredViewModel({ error("assets missing") }, SavedStateHandle()).uiState.value
        assertTrue(failed.loadError!!.contains("assets missing"))
    }

    @Test
    fun runnerFailureEndsInErrorStateWithoutCrashing() {
        val vm = StructuredViewModel(loader, SavedStateHandle(), runner = { throw RuntimeException("boom") })
        val state = vm.uiState.value
        assertNull(state.outcome)
        assertTrue(state.runError!!.contains("boom"))
        assertEquals(fixtureCount, state.names.size)
    }

    @Test
    fun runnerCancellationPropagatesAndIsNotAnError() {
        val vm = StructuredViewModel(
            loader, SavedStateHandle(), runner = { throw kotlinx.coroutines.CancellationException("cancelled") },
        )
        assertNull(vm.uiState.value.runError)
    }

    @Test
    fun everyFixtureMatchesItsContractExpectations() {
        val vm = viewModel()
        for (name in vm.uiState.value.names) {
            vm.select(name)
            val state = vm.uiState.value
            assertNull(name, state.runError)
            val expect = state.fixture!!.expect
            val outcome = state.outcome!!
            assertEquals(name, expect.attempts, outcome.attempts)
            assertEquals(name, expect.appended, outcome.appended)
            val expectedError = expect.result["error"] as? JsonObject
            val actual = outcome.result
            if (expectedError == null) {
                val value = (actual as FixtureResult.Success).value
                assertTrue("$name: ${expect.result["value"]} vs $value", jsonEqual(expect.result["value"]!!, value))
            } else {
                val failure = actual as FixtureResult.Failure
                assertEquals(name, (expectedError.getValue("kind") as JsonPrimitive).content, failure.kind.wire)
                val issues = expectedError["issues"] as? JsonArray
                if (issues != null) {
                    val expectedIssues = issues.map {
                        val o = it as JsonObject
                        (o["path"] as JsonPrimitive).content to (o["code"] as JsonPrimitive).content
                    }
                    assertEquals(name, expectedIssues, failure.issues!!.map { it.path to it.code.wire })
                } else {
                    assertNull(name, failure.issues)
                }
            }
        }
    }

    /** Structural equality that compares numbers by value (2.0 equals 2), like the contract does. */
    private fun jsonEqual(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject ->
            a.keys == b.keys && a.keys.all { jsonEqual(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEqual(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString ->
            a.doubleOrNull?.let { x -> b.doubleOrNull?.let { y -> x == y } } ?: (a == b)
        else -> a == b
    }

    @Test
    fun prettyJsonIndentsObjects() {
        assertEquals("{\n  \"a\": 1\n}", prettyJson(buildJsonObject { put("a", 1) }))
    }
}
