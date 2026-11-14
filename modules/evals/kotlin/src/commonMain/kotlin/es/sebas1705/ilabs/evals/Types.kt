package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.structured.Issue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A deterministic check of a reply. `kind` is the wire name used in suites and reports. */
sealed interface Grader {
    val kind: String
}

/** A grader that needs only the reply text: everything except [Judge]. */
sealed interface PureGrader : Grader

/** `trim` removes space, tab, LF and CR from both sides, then `ignoreCase` folds ASCII letters only. */
data class Equals(val value: String, val trim: Boolean = false, val ignoreCase: Boolean = false) : PureGrader {
    override val kind: String get() = "equals"
}

data class Contains(val value: String, val trim: Boolean = false, val ignoreCase: Boolean = false) : PureGrader {
    override val kind: String get() = "contains"
}

data class NotContains(val value: String, val trim: Boolean = false, val ignoreCase: Boolean = false) : PureGrader {
    override val kind: String get() = "notContains"
}

data class StartsWith(val value: String, val trim: Boolean = false, val ignoreCase: Boolean = false) : PureGrader {
    override val kind: String get() = "startsWith"
}

data class EndsWith(val value: String, val trim: Boolean = false, val ignoreCase: Boolean = false) : PureGrader {
    override val kind: String get() = "endsWith"
}

data class OneOf(val values: List<String>, val trim: Boolean = false, val ignoreCase: Boolean = false) : PureGrader {
    override val kind: String get() = "oneOf"
}

/** Reply length in Unicode code points, inclusive bounds; at least one bound, integers `>= 0`, `min <= max`. */
data class Length(val min: Int? = null, val max: Int? = null) : PureGrader {
    override val kind: String get() = "length"
}

/**
 * The reply must contain JSON (see `structured`'s `extractJson`) that validates against [schema].
 *
 * Name clash: this class collides with `kotlinx.serialization.json.Json` when both packages are star-imported;
 * import it explicitly or alias it (`import es.sebas1705.ilabs.evals.Json as JsonGrader`).
 */
data class Json(val schema: JsonObject) : PureGrader {
    override val kind: String get() = "json"
}

/** The reply must contain JSON that deep-equals [value] (`null` is a valid value). */
data class JsonEquals(val value: JsonElement) : PureGrader {
    override val kind: String get() = "jsonEquals"
}

/** Asks the judge provider to rate the reply against [rubric] (a non-empty string). */
data class Judge(val rubric: String) : Grader {
    override val kind: String get() = "judge"
}

data class EvalCase(val id: String, val messages: List<Message>, val graders: List<Grader>)

data class Suite(val cases: List<EvalCase>)

/** `issues` is present (possibly empty) for `json` and `jsonEquals` only; `error` is set when the grader could not decide. */
data class GraderResult(
    val kind: String,
    val passed: Boolean,
    val issues: List<Issue>? = null,
    val error: String? = null,
)

enum class Status(val wire: String) {
    PASS("pass"),
    FAIL("fail"),
    ERROR("error"),
}

/** `reply` is null when the provider call failed; then `error` is the provider error kind and `graders` is empty. */
data class CaseResult(
    val caseId: String,
    val targetId: String,
    val status: Status,
    val reply: String?,
    val error: String?,
    val graders: List<GraderResult>,
)

data class TargetSummary(
    val targetId: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val errored: Int,
    val passRate: Double,
)

/** `calls` lists every target id and the key `judge` only when a judge was supplied. */
data class RunOutput(
    val results: List<CaseResult>,
    val summary: List<TargetSummary>,
    val calls: Map<String, Int>,
)
