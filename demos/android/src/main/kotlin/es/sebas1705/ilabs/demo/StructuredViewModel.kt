package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import es.sebas1705.ilabs.providers.ErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import es.sebas1705.ilabs.structured.Fixture as StructuredFixture
import es.sebas1705.ilabs.structured.FixtureOutcome as StructuredOutcome
import es.sebas1705.ilabs.structured.FixtureResult as StructuredResult
import es.sebas1705.ilabs.structured.parseFixture as parseStructuredFixture
import es.sebas1705.ilabs.structured.runFixture as runStructuredFixture

/** Fixture groups, in display order; the group is derived from the start of the fixture name. */
enum class FixtureGroup(val prefix: String) {
    EXTRACT("extract-"),
    VALIDATE("validate-"),
    LOOP("loop-"),
    OTHER(""),
    ;

    companion object {
        fun of(name: String): FixtureGroup = entries.firstOrNull { it != OTHER && name.startsWith(it.prefix) } ?: OTHER
    }
}

/** A provider error raised by call number `attempt`, which left no trace entry of its own. */
data class ProviderFailure(val attempt: Int, val kind: ErrorKind)

/** What the screen renders. `fixture` carries schema, request, setup and script; `outcome` the replayed run. */
data class StructuredUiState(
    val names: List<String> = emptyList(),
    val selected: String? = null,
    val fixture: StructuredFixture? = null,
    val outcome: StructuredOutcome? = null,
    /** Set when some or all fixture documents could not be loaded; the valid ones are still listed. */
    val loadError: String? = null,
    /** Set when replaying the selected fixture failed. */
    val runError: String? = null,
) {
    /** Names grouped by prefix, in group order, empty groups left out. */
    val groups: List<Pair<FixtureGroup, List<String>>>
        get() = names.groupBy(FixtureGroup::of).toList().sortedBy { it.first.ordinal }

    /** Set when the final result is a provider error whose call has no trace entry (attempts > trace size). */
    val providerFailure: ProviderFailure?
        get() {
            val run = outcome ?: return null
            val failure = run.result as? StructuredResult.Failure ?: return null
            return if (failure.issues == null && run.attempts > run.trace.size) ProviderFailure(run.attempts, failure.kind) else null
        }
}

private val prettyPrinter = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** JSON with two-space indentation, as the web demo shows it. */
fun prettyJson(element: JsonElement): String = prettyPrinter.encodeToString(JsonElement.serializer(), element)

class StructuredViewModel(
    loader: FixtureLoader,
    private val savedState: SavedStateHandle,
    private val runner: suspend (StructuredFixture) -> StructuredOutcome = ::runStructuredFixture,
) : ViewModel() {
    private val fixtures: Map<String, StructuredFixture>
    private val _uiState = MutableStateFlow(StructuredUiState())
    val uiState: StateFlow<StructuredUiState> = _uiState.asStateFlow()

    init {
        val problems = mutableListOf<String>()
        val parsed = try {
            loader.load().mapIndexedNotNull { index, doc ->
                try {
                    parseStructuredFixture(doc)
                } catch (e: Exception) {
                    problems += "${describe(index, doc)}: ${e.message ?: e::class.simpleName}"
                    null
                }
            }.sortedBy { it.name }
        } catch (e: Exception) {
            problems += e.message ?: e::class.simpleName.orEmpty()
            emptyList()
        }
        fixtures = parsed.associateBy { it.name }
        _uiState.value = StructuredUiState(
            names = parsed.map(StructuredFixture::name),
            loadError = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        if (parsed.isNotEmpty()) {
            val saved = savedState.get<String>(KEY_SELECTED)
            val default = DEFAULT_FIXTURE.takeIf(fixtures::containsKey) ?: parsed.first().name
            select(saved?.takeIf(fixtures::containsKey) ?: default)
        }
    }

    fun select(name: String) {
        val fixture = fixtures[name] ?: return
        savedState[KEY_SELECTED] = name
        _uiState.update { it.copy(selected = name, fixture = fixture, outcome = null, runError = null) }
        viewModelScope.launch {
            try {
                val outcome = runner(fixture)
                // Ignore a run that finished after the user already picked another fixture.
                _uiState.update { if (it.selected == name) it.copy(outcome = outcome) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "$name: ${e.message ?: e::class.simpleName}"
                _uiState.update { if (it.selected == name) it.copy(runError = message) else it }
            }
        }
    }

    /** Names a malformed document: its "name" field when one can be read, else its position (1-based). */
    private fun describe(index: Int, doc: String): String {
        val name = NAME_FIELD.find(doc)?.groupValues?.get(1)
        return if (name != null) "$name (#${index + 1})" else "#${index + 1}"
    }

    companion object {
        private const val KEY_SELECTED = "selected"
        private const val DEFAULT_FIXTURE = "loop-repair-second"
        private val NAME_FIELD = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

        fun factory(loader: FixtureLoader): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                StructuredViewModel(loader, extras.createSavedStateHandle()) as T
        }
    }
}
