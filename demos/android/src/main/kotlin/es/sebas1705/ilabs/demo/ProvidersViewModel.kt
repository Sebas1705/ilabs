package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import es.sebas1705.ilabs.providers.Fixture
import es.sebas1705.ilabs.providers.FixtureOutcome
import es.sebas1705.ilabs.providers.parseFixture
import es.sebas1705.ilabs.providers.runFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Supplies the raw fixture JSON documents (Android assets in the app, plain files in unit tests). */
fun interface FixtureLoader {
    fun load(): List<String>
}

/** What the screen renders. `fixture` carries the setup and the provider scripts; `outcome` the replayed run. */
data class ProvidersUiState(
    val names: List<String> = emptyList(),
    val selected: String? = null,
    val fixture: Fixture? = null,
    val outcome: FixtureOutcome? = null,
    /** Set when some or all fixture documents could not be loaded; the valid ones are still listed. */
    val loadError: String? = null,
    /** Set when replaying the selected fixture failed. */
    val runError: String? = null,
)

class ProvidersViewModel(
    private val savedState: SavedStateHandle,
    loader: FixtureLoader,
    private val runner: suspend (Fixture) -> FixtureOutcome = ::runFixture,
) : ViewModel() {
    private val fixtures: Map<String, Fixture>
    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    init {
        val problems = mutableListOf<String>()
        val parsed = try {
            loader.load().mapIndexedNotNull { index, doc ->
                try {
                    parseFixture(doc)
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
        _uiState.value = ProvidersUiState(
            names = parsed.map(Fixture::name),
            loadError = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        if (parsed.isNotEmpty()) {
            val saved = savedState.get<String>(KEY_SELECTED)
            select(saved?.takeIf(fixtures::containsKey) ?: parsed.first().name)
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
        private val NAME_FIELD = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

        fun factory(loader: FixtureLoader): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                ProvidersViewModel(extras.createSavedStateHandle(), loader) as T
        }
    }
}
