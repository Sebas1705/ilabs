package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import es.sebas1705.ilabs.context.BudgetTooSmallException
import es.sebas1705.ilabs.context.DEFAULT_KEEP_RECENT
import es.sebas1705.ilabs.context.WindowOptions
import es.sebas1705.ilabs.context.defaultCounter
import es.sebas1705.ilabs.context.isSummaryMessage
import es.sebas1705.ilabs.context.slidingWindow
import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import es.sebas1705.ilabs.context.contract.Fixture
import es.sebas1705.ilabs.context.contract.FixtureOutcome
import es.sebas1705.ilabs.context.contract.FixtureResult
import es.sebas1705.ilabs.context.contract.parseFixture
import es.sebas1705.ilabs.context.contract.runFixture

/** Fixture groups, in display order; the group is derived from the start of the fixture name. */
enum class ContextGroup(val prefix: String) {
    WINDOW("window-"),
    SUMMARIZE("summarize-"),
    OTHER(""),
    ;

    companion object {
        fun of(name: String): ContextGroup = entries.firstOrNull { it != OTHER && name.startsWith(it.prefix) } ?: OTHER
    }
}

/** What happened to one message of the conversation. */
enum class MessageState { KEPT, DROPPED, REPLACED, NEW_SUMMARY }

/** One row of the conversation: the message, its token cost and its state (null when there is no result to judge by). */
data class ContextRow(val message: Message, val cost: Int, val state: MessageState?)

/**
 * The result shown for the current budget. At most one of `requiredTokens` (budget too small) and `failureKind`
 * (provider error) is set; then no row has a state.
 */
data class ContextView(
    val rows: List<ContextRow>,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val droppedCount: Int,
    val requiredTokens: Int? = null,
    val failureKind: String? = null,
    val summarized: Boolean = false,
    val summaryCalls: Int = 0,
    val calls: Int = 0,
    val summarizerRequest: CompletionRequest? = null,
    /** The messages of the result, in order (the new summary included). Empty on failure. */
    val outputMessages: List<Message> = emptyList(),
) {
    /** The new summary message the provider call produced, when there is one. */
    val summary: Message? get() = rows.firstOrNull { it.state == MessageState.NEW_SUMMARY }?.message
}

/** What the screen renders. `fixture` carries the conversation and setup; `view` the result at the current budget. */
data class ContextUiState(
    val names: List<String> = emptyList(),
    val selected: String? = null,
    val fixture: Fixture? = null,
    /** Slider value in tokens; for a summarize fixture, the fixture's own fixed budget. */
    val budget: Int = 1,
    /** Slider maximum: the total tokens of the conversation (at least 1). */
    val budgetMax: Int = 1,
    val view: ContextView? = null,
    /** Set when some or all fixture documents could not be loaded; the valid ones are still listed. */
    val loadError: String? = null,
    /** Set when replaying the selected fixture failed. */
    val runError: String? = null,
) {
    val budgetEditable: Boolean get() = fixture?.setup?.kind == "window" && budgetMax > 1

    /** Names grouped by prefix, in group order, empty groups left out. */
    val groups: List<Pair<ContextGroup, List<String>>>
        get() = names.groupBy(ContextGroup::of).toList().sortedBy { it.first.ordinal }
}

/** Rows without states: no result to judge by. */
private fun plainRows(messages: List<Message>) = messages.map { ContextRow(it, defaultCounter(it), null) }

/**
 * Marks every input message. The non-system messages that survive are always the last k of them, where k is the
 * number of non-system messages in the output. With a summary call, older ones (before the protected tail) and
 * every earlier summary are replaced by the new summary, which is inserted where the first of them stood.
 */
private fun markedRows(input: List<Message>, output: List<Message>, summarized: Boolean, keepRecent: Int): List<ContextRow> {
    val nonSystemCount = input.count { it.role != Role.SYSTEM }
    val keptNonSystem = output.count { it.role != Role.SYSTEM }
    val protectedFrom = nonSystemCount - minOf(keepRecent, nonSystemCount)
    var ordinal = 0
    val rows = ArrayList<ContextRow>(input.size + 1)
    var summaryAt = -1
    input.forEach { m ->
        val state = if (m.role == Role.SYSTEM) {
            if (summarized && isSummaryMessage(m)) MessageState.REPLACED else MessageState.KEPT
        } else {
            val n = ordinal++
            when {
                summarized && n < protectedFrom -> MessageState.REPLACED
                n >= nonSystemCount - keptNonSystem -> MessageState.KEPT
                else -> MessageState.DROPPED
            }
        }
        if (state == MessageState.REPLACED && summaryAt < 0) summaryAt = rows.size
        rows += ContextRow(m, defaultCounter(m), state)
    }
    if (summarized) {
        val summary = output.first { isSummaryMessage(it) }
        rows.add(summaryAt, ContextRow(summary, defaultCounter(summary), MessageState.NEW_SUMMARY))
    }
    return rows
}

private fun windowView(messages: List<Message>, budget: Int): ContextView {
    val before = messages.sumOf(defaultCounter)
    return try {
        val w = slidingWindow(messages, WindowOptions(budget))
        ContextView(
            rows = markedRows(messages, w.messages, summarized = false, keepRecent = DEFAULT_KEEP_RECENT),
            tokensBefore = w.tokensBefore,
            tokensAfter = w.tokensAfter,
            droppedCount = w.droppedCount,
            outputMessages = w.messages,
        )
    } catch (e: BudgetTooSmallException) {
        ContextView(plainRows(messages), before, before, 0, requiredTokens = e.required)
    }
}

private fun summarizeView(fixture: Fixture, outcome: FixtureOutcome): ContextView {
    val messages = fixture.messages
    val before = messages.sumOf(defaultCounter)
    return when (val result = outcome.result) {
        is FixtureResult.Success -> ContextView(
            rows = markedRows(messages, result.messages, result.summarized == true, fixture.setup.keepRecent ?: DEFAULT_KEEP_RECENT),
            tokensBefore = result.tokensBefore,
            tokensAfter = result.tokensAfter,
            droppedCount = result.droppedCount,
            summarized = result.summarized == true,
            summaryCalls = result.summaryCalls ?: 0,
            calls = outcome.calls,
            summarizerRequest = outcome.summarizerRequest,
            outputMessages = result.messages,
        )
        is FixtureResult.Failure -> ContextView(
            rows = plainRows(messages),
            tokensBefore = before,
            tokensAfter = before,
            droppedCount = 0,
            requiredTokens = result.required.takeIf { result.kind == "budget_too_small" },
            failureKind = result.kind.takeIf { it != "budget_too_small" },
            calls = outcome.calls,
            summarizerRequest = outcome.summarizerRequest,
        )
    }
}

class ContextViewModel(
    loader: FixtureLoader,
    private val savedState: SavedStateHandle,
    private val runner: suspend (Fixture) -> FixtureOutcome = ::runFixture,
) : ViewModel() {
    private val fixtures: Map<String, Fixture>
    private val _uiState = MutableStateFlow(ContextUiState())
    val uiState: StateFlow<ContextUiState> = _uiState.asStateFlow()

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
        _uiState.value = ContextUiState(
            names = parsed.map(Fixture::name),
            loadError = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        if (parsed.isNotEmpty()) {
            val saved = savedState.get<String>(KEY_SELECTED)
            val default = DEFAULT_FIXTURE.takeIf(fixtures::containsKey) ?: parsed.first().name
            val name = saved?.takeIf(fixtures::containsKey) ?: default
            // A saved budget belongs to the saved selection only; after a fallback the fixture's own budget applies.
            val budget = if (name == saved) savedState.get<Int>(KEY_BUDGET) else null
            open(name, budget)
        }
    }

    fun select(name: String) {
        if (name in fixtures) open(name, null)
    }

    /** Sets the budget of a `window` fixture (clamped to 1..total) and recomputes the result; other fixtures ignore it. */
    fun setBudget(value: Int) {
        val state = _uiState.value
        val fixture = state.fixture ?: return
        if (!state.budgetEditable) return
        val budget = value.coerceIn(1, state.budgetMax)
        savedState[KEY_BUDGET] = budget
        _uiState.update { it.copy(budget = budget, view = windowView(fixture.messages, budget)) }
    }

    private fun open(name: String, savedBudget: Int?) {
        val fixture = fixtures.getValue(name)
        savedState[KEY_SELECTED] = name
        if (fixture.setup.kind == "window") {
            val max = maxOf(1, fixture.messages.sumOf(defaultCounter))
            val budget = (savedBudget ?: fixture.setup.maxTokens).coerceIn(1, max)
            savedState[KEY_BUDGET] = budget
            _uiState.update {
                it.copy(
                    selected = name, fixture = fixture, budget = budget, budgetMax = max,
                    view = windowView(fixture.messages, budget), runError = null,
                )
            }
            return
        }
        val budget = fixture.setup.maxTokens
        savedState[KEY_BUDGET] = budget
        _uiState.update {
            it.copy(
                selected = name, fixture = fixture, budget = budget,
                budgetMax = maxOf(budget, fixture.messages.sumOf(defaultCounter)), view = null, runError = null,
            )
        }
        viewModelScope.launch {
            try {
                val outcome = runner(fixture)
                val view = summarizeView(fixture, outcome)
                // Ignore a run that finished after the user already picked another fixture.
                _uiState.update { if (it.selected == name) it.copy(view = view) else it }
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
        private const val KEY_BUDGET = "budget"
        private const val DEFAULT_FIXTURE = "window-drops-oldest"
        private val NAME_FIELD = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

        fun factory(loader: FixtureLoader): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                ContextViewModel(loader, extras.createSavedStateHandle()) as T
        }
    }
}
