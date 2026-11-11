package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import dev.ilabs.providers.Fixture
import dev.ilabs.providers.FixtureOutcome
import dev.ilabs.providers.parseFixture
import dev.ilabs.providers.runFixture
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
    val loadError: String? = null,
)

class ProvidersViewModel(
    private val savedState: SavedStateHandle,
    loader: FixtureLoader,
) : ViewModel() {
    private val fixtures: Map<String, Fixture>
    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    init {
        val parsed = try {
            loader.load().map(::parseFixture).sortedBy { it.name }
        } catch (e: Exception) {
            _uiState.value = ProvidersUiState(loadError = e.message ?: e::class.simpleName.orEmpty())
            emptyList()
        }
        fixtures = parsed.associateBy { it.name }
        if (parsed.isNotEmpty()) {
            _uiState.update { it.copy(names = parsed.map(Fixture::name)) }
            val saved = savedState.get<String>(KEY_SELECTED)
            select(saved?.takeIf(fixtures::containsKey) ?: parsed.first().name)
        }
    }

    fun select(name: String) {
        val fixture = fixtures[name] ?: return
        savedState[KEY_SELECTED] = name
        _uiState.update { it.copy(selected = name, fixture = fixture, outcome = null) }
        viewModelScope.launch {
            val outcome = runFixture(fixture)
            // Ignore a run that finished after the user already picked another fixture.
            _uiState.update { if (it.selected == name) it.copy(outcome = outcome) else it }
        }
    }

    companion object {
        private const val KEY_SELECTED = "selected"

        fun factory(loader: FixtureLoader): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                ProvidersViewModel(extras.createSavedStateHandle(), loader) as T
        }
    }
}
