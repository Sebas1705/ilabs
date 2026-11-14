package es.sebas1705.ilabs.demo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import es.sebas1705.ilabs.evals.CaseResult
import es.sebas1705.ilabs.evals.EvalCase
import es.sebas1705.ilabs.evals.GraderResult
import es.sebas1705.ilabs.evals.PureGrader
import es.sebas1705.ilabs.evals.TargetSummary
import es.sebas1705.ilabs.evals.contract.Fixture
import es.sebas1705.ilabs.evals.contract.FixtureOutcome
import es.sebas1705.ilabs.evals.contract.parseFixture
import es.sebas1705.ilabs.evals.contract.runFixture
import es.sebas1705.ilabs.evals.evaluateGrader
import es.sebas1705.ilabs.evals.parseSuite
import es.sebas1705.ilabs.structured.parseStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixture groups, in display order; the group is derived from the start of the fixture name. */
enum class EvalsGroup(val prefixes: List<String>) {
    RUN(listOf("status-", "provider-", "summary-", "multi-", "calls-", "no-", "empty-")),
    STRING(listOf("equals-", "contains-", "notcontains-", "startswith-", "endswith-", "oneof-", "string-")),
    LENGTH(listOf("length-")),
    JSON(listOf("json-", "jsonequals-")),
    JUDGE(listOf("judge-")),
    INVALID(listOf("invalid-")),
    OTHER(emptyList()),
    ;

    companion object {
        fun of(name: String): EvalsGroup = entries.firstOrNull { g -> g.prefixes.any(name::startsWith) } ?: OTHER
    }
}

/** The parameters of every grader of a (valid) suite as one-line JSON, read from the raw suite like the web page does. */
internal fun graderParams(suite: JsonElement): Map<String, List<String>> {
    val cases = (suite as? JsonObject)?.get("cases") as? JsonArray ?: return emptyMap()
    return cases.mapNotNull { case ->
        val o = case as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val graders = (o["graders"] as? JsonArray).orEmpty().map { g ->
            val rest = JsonObject((g as? JsonObject).orEmpty().filterKeys { it != "kind" })
            if (rest.isEmpty()) "" else rest.toString()
        }
        id to graders
    }.toMap()
}

/** One cell of the matrix: a (case, target) pair. */
data class CellRef(val caseId: String, val targetId: String)

/** A finished run laid out for the matrix: cases are rows, targets are columns (in run order). */
data class EvalsRunView(
    val cases: List<EvalCase>,
    val targets: List<String>,
    val results: List<CaseResult>,
    val summary: List<TargetSummary>,
    val calls: Map<String, Int>,
    /** Per case id, each grader's parameters (its JSON without `kind`, e.g. `{"value":"hello"}`; empty when it has none). */
    val graderParams: Map<String, List<String>> = emptyMap(),
) {
    /** Judge calls, or null when the run had no judge (the contract lists the key `judge` only then). */
    val judgeCalls: Int? get() = calls[JUDGE_KEY]

    fun result(caseId: String, targetId: String): CaseResult? =
        results.firstOrNull { it.caseId == caseId && it.targetId == targetId }

    private companion object {
        const val JUDGE_KEY = "judge"
    }
}

/** The pure graders the playground offers (everything except `judge`), in display order. */
enum class PlaygroundKind(val wire: String) {
    EQUALS("equals"),
    CONTAINS("contains"),
    NOT_CONTAINS("notContains"),
    STARTS_WITH("startsWith"),
    ENDS_WITH("endsWith"),
    ONE_OF("oneOf"),
    LENGTH("length"),
    JSON("json"),
    JSON_EQUALS("jsonEquals"),
    ;

    /** Whether the kind takes the `trim` and `ignoreCase` options. */
    val hasTextOptions: Boolean get() = this != LENGTH && this != JSON && this != JSON_EQUALS

    companion object {
        fun ofWire(wire: String?): PlaygroundKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why the parameter text of the playground cannot be turned into a grader; the screen translates the code. */
enum class ParamErrorCode {
    /** The text of a `json` or `jsonEquals` parameter is not JSON. */
    INVALID_JSON,

    /** The library rejected the grader (`invalid suite: ...`); the reason is the detail. */
    INVALID_PARAMS,

    /** Same, for a `json` grader: the schema is not valid. */
    INVALID_SCHEMA,
}

/** `detail` is the library's own reason (`invalid suite: ...`), shown next to the translated message. */
data class ParamError(val code: ParamErrorCode, val detail: String? = null)

/**
 * The grader playground: the raw text the user typed plus the verdict derived from it. Exactly one of `verdict`
 * and `error` is set. `value` (text graders), `values` (`oneOf`, one per line), `schema` (`json`), `jsonValue`
 * (`jsonEquals`) and `min`/`max` (`length`) are raw text, one field per kind.
 */
data class PlaygroundState(
    val kind: PlaygroundKind = PlaygroundKind.EQUALS,
    /** One text per field, so switching kind never shows another kind's text (same defaults as the web page). */
    val value: String = "hello",
    val values: String = "yes\nno",
    val schema: String = "{\n  \"type\": \"object\",\n  \"required\": [\"name\"],\n  \"properties\": { \"name\": { \"type\": \"string\" } }\n}",
    val jsonValue: String = "{ \"ok\": true }",
    val min: String = "",
    val max: String = "",
    val trim: Boolean = false,
    val ignoreCase: Boolean = false,
    val reply: String = "Hello",
    val verdict: GraderResult? = null,
    val error: ParamError? = null,
) {
    /** The text the parameter field of the current kind shows. */
    val param: String
        get() = when (kind) {
            PlaygroundKind.ONE_OF -> values
            PlaygroundKind.JSON -> schema
            PlaygroundKind.JSON_EQUALS -> jsonValue
            else -> value
        }
}

/** What the screen renders. `fixture` and `outcome` are the loaded fixture and its replay; `run` or `invalidSuite` the result. */
data class EvalsUiState(
    val names: List<String> = emptyList(),
    val selected: String? = null,
    val fixture: Fixture? = null,
    val outcome: FixtureOutcome? = null,
    /** The matrix, when the suite was valid and the run finished. */
    val run: EvalsRunView? = null,
    /** The `invalid suite: ...` message, when the fixture's suite was rejected (no provider was called). */
    val invalidSuite: String? = null,
    val cell: CellRef? = null,
    val playground: PlaygroundState = PlaygroundState(),
    /** Set when some or all fixture documents could not be loaded; the valid ones are still listed. */
    val loadError: String? = null,
    /** Set when replaying the selected fixture failed. */
    val runError: String? = null,
) {
    /** Names grouped by prefix, in group order, empty groups left out. */
    val groups: List<Pair<EvalsGroup, List<String>>>
        get() = names.groupBy(EvalsGroup::of).toList().sortedBy { it.first.ordinal }

    val cellResult: CaseResult? get() = cell?.let { run?.result(it.caseId, it.targetId) }

    val cellCase: EvalCase? get() = cell?.let { c -> run?.cases?.firstOrNull { it.id == c.caseId } }
}

private val JS_NUMBER = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

/** Blank text leaves the bound out; decimal text (`2`, `+3`, `2.0`, `1e2`) becomes that number for the library to judge; anything else, `0x10` and `Infinity` included, becomes NaN, which the library rejects (stricter than JavaScript's `Number`). */
private fun bound(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return if (JS_NUMBER.matches(t)) t.toDouble() else Double.NaN
}

/**
 * Turns the typed text into a grader and evaluates it on the reply, or names what is wrong with the parameter.
 * Only text-to-value conversion happens here: the raw grader object goes through a one-case [parseSuite], the same
 * code path the fixtures use, so every rule about a valid grader comes from the library. Text that is not JSON
 * (`json` schema, `jsonEquals` value, empty text included) never reaches it. Pasted text is not capped: the
 * playground is a local demo and the graders are linear in the reply.
 */
internal fun evaluatePlayground(p: PlaygroundState): PlaygroundState {
    fun fail(code: ParamErrorCode, detail: String? = null) = p.copy(verdict = null, error = ParamError(code, detail))
    val raw = buildJsonObject {
        put("kind", p.kind.wire)
        when (p.kind) {
            PlaygroundKind.EQUALS, PlaygroundKind.CONTAINS, PlaygroundKind.NOT_CONTAINS,
            PlaygroundKind.STARTS_WITH, PlaygroundKind.ENDS_WITH,
            -> {
                put("value", p.value)
                put("trim", p.trim)
                put("ignoreCase", p.ignoreCase)
            }
            PlaygroundKind.ONE_OF -> {
                // Every line is a value, so empty text is the single value "" (the library accepts it).
                put("values", JsonArray(p.values.split(Regex("\r?\n")).map(::JsonPrimitive)))
                put("trim", p.trim)
                put("ignoreCase", p.ignoreCase)
            }
            PlaygroundKind.LENGTH -> {
                bound(p.min)?.let { put("min", JsonPrimitive(it)) }
                bound(p.max)?.let { put("max", JsonPrimitive(it)) }
            }
            PlaygroundKind.JSON -> put("schema", parseStrict(p.schema) ?: return fail(ParamErrorCode.INVALID_JSON))
            PlaygroundKind.JSON_EQUALS -> put("value", parseStrict(p.jsonValue) ?: return fail(ParamErrorCode.INVALID_JSON))
        }
    }
    val suite = buildJsonObject {
        put("cases", buildJsonArray {
            addJsonObject {
                put("id", "playground")
                put("messages", JsonArray(emptyList()))
                put("graders", buildJsonArray { add(raw) })
            }
        })
    }
    val grader = try {
        parseSuite(suite).cases.single().graders.single() as? PureGrader
    } catch (e: IllegalArgumentException) {
        val reason = e.message
        if (reason == null || !reason.startsWith("invalid suite:")) throw e
        return fail(if (p.kind == PlaygroundKind.JSON) ParamErrorCode.INVALID_SCHEMA else ParamErrorCode.INVALID_PARAMS, reason)
    } ?: return fail(ParamErrorCode.INVALID_PARAMS)
    return p.copy(verdict = evaluateGrader(grader, p.reply), error = null)
}

class EvalsViewModel(
    loader: FixtureLoader,
    private val savedState: SavedStateHandle,
    private val runner: suspend (Fixture) -> FixtureOutcome = ::runFixture,
) : ViewModel() {
    private val fixtures: Map<String, Fixture>
    private val _uiState = MutableStateFlow(EvalsUiState())
    val uiState: StateFlow<EvalsUiState> = _uiState.asStateFlow()
    private var runJob: Job? = null

    init {
        val problems = mutableListOf<String>()
        val parsed = try {
            loader.load().mapIndexedNotNull { index, doc ->
                try {
                    parseFixture(doc).also { check(it.name.isNotEmpty()) { "invalid fixture: missing name" } }
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
        _uiState.value = EvalsUiState(
            names = parsed.map(Fixture::name),
            playground = restorePlayground(),
            loadError = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        if (parsed.isNotEmpty()) {
            val saved = savedState.get<String>(KEY_SELECTED)
            val default = DEFAULT_FIXTURE.takeIf(fixtures::containsKey) ?: parsed.first().name
            val name = saved?.takeIf(fixtures::containsKey) ?: default
            // A saved cell belongs to the saved selection only; after a fallback no cell is selected.
            val cell = if (name == saved) {
                val c = savedState.get<String>(KEY_CELL_CASE)
                val t = savedState.get<String>(KEY_CELL_TARGET)
                if (c != null && t != null) CellRef(c, t) else null
            } else {
                null
            }
            open(name, cell)
        }
    }

    fun select(name: String) {
        if (name in fixtures) open(name, null)
    }

    /** Selects a cell of the matrix for the detail view; a pair without a result is ignored. */
    fun selectCell(caseId: String, targetId: String) {
        if (_uiState.value.run?.result(caseId, targetId) == null) return
        val cell = CellRef(caseId, targetId)
        saveCell(cell)
        _uiState.update { it.copy(cell = cell) }
    }

    fun clearCell() {
        saveCell(null)
        _uiState.update { it.copy(cell = null) }
    }

    fun setGraderKind(kind: PlaygroundKind) = edit { it.copy(kind = kind) }
    fun setParam(text: String) = edit {
        when (it.kind) {
            PlaygroundKind.ONE_OF -> it.copy(values = text)
            PlaygroundKind.JSON -> it.copy(schema = text)
            PlaygroundKind.JSON_EQUALS -> it.copy(jsonValue = text)
            else -> it.copy(value = text)
        }
    }
    fun setMin(text: String) = edit { it.copy(min = text) }
    fun setMax(text: String) = edit { it.copy(max = text) }
    fun setTrim(value: Boolean) = edit { it.copy(trim = value) }
    fun setIgnoreCase(value: Boolean) = edit { it.copy(ignoreCase = value) }
    fun setReply(text: String) = edit { it.copy(reply = text) }

    private fun edit(change: (PlaygroundState) -> PlaygroundState) {
        val next = evaluatePlayground(change(_uiState.value.playground))
        savedState[KEY_KIND] = next.kind.wire
        savedState[KEY_VALUE] = next.value
        savedState[KEY_VALUES] = next.values
        savedState[KEY_SCHEMA] = next.schema
        savedState[KEY_JSON_VALUE] = next.jsonValue
        savedState[KEY_MIN] = next.min
        savedState[KEY_MAX] = next.max
        savedState[KEY_TRIM] = next.trim
        savedState[KEY_IGNORE_CASE] = next.ignoreCase
        savedState[KEY_REPLY] = next.reply
        _uiState.update { it.copy(playground = next) }
    }

    private fun restorePlayground(): PlaygroundState {
        val d = PlaygroundState()
        return evaluatePlayground(
            d.copy(
                kind = PlaygroundKind.ofWire(savedState.get<String>(KEY_KIND)) ?: d.kind,
                value = savedState.get<String>(KEY_VALUE) ?: d.value,
                values = savedState.get<String>(KEY_VALUES) ?: d.values,
                schema = savedState.get<String>(KEY_SCHEMA) ?: d.schema,
                jsonValue = savedState.get<String>(KEY_JSON_VALUE) ?: d.jsonValue,
                min = savedState.get<String>(KEY_MIN) ?: d.min,
                max = savedState.get<String>(KEY_MAX) ?: d.max,
                trim = savedState.get<Boolean>(KEY_TRIM) ?: d.trim,
                ignoreCase = savedState.get<Boolean>(KEY_IGNORE_CASE) ?: d.ignoreCase,
                reply = savedState.get<String>(KEY_REPLY) ?: d.reply,
            ),
        )
    }

    private fun saveCell(cell: CellRef?) {
        savedState[KEY_CELL_CASE] = cell?.caseId
        savedState[KEY_CELL_TARGET] = cell?.targetId
    }

    private fun open(name: String, restoredCell: CellRef?) {
        val fixture = fixtures.getValue(name)
        savedState[KEY_SELECTED] = name
        saveCell(null)
        _uiState.update {
            it.copy(selected = name, fixture = fixture, outcome = null, run = null, invalidSuite = null, cell = null, runError = null)
        }
        // A slow run of the previous fixture is cancelled; the `selected` check below still guards a runner that ignores cancellation.
        runJob?.cancel()
        runJob = viewModelScope.launch {
            try {
                val outcome = runner(fixture)
                val run = outcome.output?.let { output ->
                    val cases = parseSuite(fixture.root.getValue("suite")).cases
                    EvalsRunView(
                        cases, output.calls.keys.filter { it != "judge" }, output.results, output.summary, output.calls,
                        graderParams(fixture.root.getValue("suite")),
                    )
                }
                val cell = restoredCell?.takeIf { run?.result(it.caseId, it.targetId) != null }
                if (_uiState.value.selected != name) return@launch
                saveCell(cell)
                _uiState.update {
                    if (it.selected != name) it else it.copy(outcome = outcome, run = run, invalidSuite = outcome.invalidSuiteMessage, cell = cell)
                }
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
        private const val KEY_CELL_CASE = "cellCase"
        private const val KEY_CELL_TARGET = "cellTarget"
        private const val KEY_KIND = "pgKind"
        private const val KEY_VALUE = "pgValue"
        private const val KEY_VALUES = "pgValues"
        private const val KEY_SCHEMA = "pgSchema"
        private const val KEY_JSON_VALUE = "pgJsonValue"
        private const val KEY_MIN = "pgMin"
        private const val KEY_MAX = "pgMax"
        private const val KEY_TRIM = "pgTrim"
        private const val KEY_IGNORE_CASE = "pgIgnoreCase"
        private const val KEY_REPLY = "pgReply"
        private const val DEFAULT_FIXTURE = "status-fail-beats-pass"
        private val NAME_FIELD = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

        fun factory(loader: FixtureLoader): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                EvalsViewModel(loader, extras.createSavedStateHandle()) as T
        }
    }
}
