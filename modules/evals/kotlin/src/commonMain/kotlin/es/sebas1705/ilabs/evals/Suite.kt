package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import es.sebas1705.ilabs.structured.InvalidSchemaException
import es.sebas1705.ilabs.structured.checkSchema
import es.sebas1705.ilabs.structured.parseStrict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal fun invalidSuite(reason: String): Nothing = throw IllegalArgumentException("invalid suite: $reason")

private val STRING_KINDS = setOf("equals", "contains", "notContains", "startsWith", "endsWith")
private val ALL_KINDS = STRING_KINDS + setOf("oneOf", "length", "json", "jsonEquals", "judge")

private fun JsonElement.isString() = this is JsonPrimitive && this !is JsonNull && isString

/** A JSON boolean literal: the string `"true"` is not a boolean. */
private fun JsonElement.asBoolean(): Boolean? {
    if (this !is JsonPrimitive || this is JsonNull || isString) return null
    return when (content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** A JSON number literal as a finite double (`2.0` and `1e2` included); strings, booleans and `null` are not numbers. */
private fun JsonElement.asNumber(): Double? {
    if (this !is JsonPrimitive || this is JsonNull || isString) return null
    return doubleOrNull?.takeIf { it.isFinite() }
}

private fun text(el: JsonElement) = (el as JsonPrimitive).content

private fun checkKeys(where: String, o: JsonObject, allowed: Set<String>) {
    val extra = o.keys.firstOrNull { it !in allowed }
    if (extra != null) invalidSuite("unknown key \"$extra\" in $where")
}

private fun requireObject(where: String, el: JsonElement?): JsonObject =
    el as? JsonObject ?: invalidSuite("$where must be an object")

private fun requireString(where: String, o: JsonObject, key: String): String {
    val v = o[key] ?: invalidSuite("$where needs \"$key\"")
    if (!v.isString()) invalidSuite("$where \"$key\" must be a string")
    return text(v)
}

private fun optionalBoolean(where: String, o: JsonObject, key: String): Boolean {
    val v = o[key] ?: return false
    return v.asBoolean() ?: invalidSuite("$where \"$key\" must be a boolean")
}

private fun optionalBound(where: String, o: JsonObject, key: String): Double? {
    val v = o[key] ?: return null
    val d = v.asNumber() ?: invalidSuite("$where \"$key\" must be an integer")
    if (d % 1.0 != 0.0) invalidSuite("$where \"$key\" must be an integer")
    return d
}

/** Bounds above 2^31 - 1 are out of scope; no reply is that long, so clamping keeps the outcome. */
private fun clampBound(d: Double?): Int? = when {
    d == null -> null
    d > Int.MAX_VALUE -> Int.MAX_VALUE
    d < Int.MIN_VALUE -> Int.MIN_VALUE
    else -> d.toInt()
}

private fun parseGrader(where: String, el: JsonElement): Grader {
    val o = requireObject(where, el)
    val kindEl = o["kind"] ?: invalidSuite("$where needs \"kind\"")
    if (!kindEl.isString()) invalidSuite("$where \"kind\" must be a string")
    val kind = text(kindEl)
    if (kind !in ALL_KINDS) invalidSuite("$where has an unknown kind \"$kind\"")
    val at = "$where ($kind)"
    return when (kind) {
        in STRING_KINDS -> {
            checkKeys(at, o, setOf("kind", "value", "trim", "ignoreCase"))
            val value = requireString(at, o, "value")
            val trim = optionalBoolean(at, o, "trim")
            val ignoreCase = optionalBoolean(at, o, "ignoreCase")
            when (kind) {
                "equals" -> Equals(value, trim, ignoreCase)
                "contains" -> Contains(value, trim, ignoreCase)
                "notContains" -> NotContains(value, trim, ignoreCase)
                "startsWith" -> StartsWith(value, trim, ignoreCase)
                else -> EndsWith(value, trim, ignoreCase)
            }
        }
        "oneOf" -> {
            checkKeys(at, o, setOf("kind", "values", "trim", "ignoreCase"))
            val values = o["values"] as? JsonArray ?: invalidSuite("$at needs \"values\" as an array")
            if (!values.all { it.isString() }) invalidSuite("$at \"values\" must be strings")
            OneOf(values.map { text(it) }, optionalBoolean(at, o, "trim"), optionalBoolean(at, o, "ignoreCase"))
        }
        "length" -> {
            checkKeys(at, o, setOf("kind", "min", "max"))
            val min = optionalBound(at, o, "min")
            val max = optionalBound(at, o, "max")
            // min <= max is checked on the unclamped values, before clamping to the Int range.
            if (min != null && max != null && min > max) invalidSuite("$at min must not exceed max")
            Length(clampBound(min), clampBound(max))
        }
        "json" -> {
            checkKeys(at, o, setOf("kind", "schema"))
            Json(requireObject("$at \"schema\"", o["schema"]))
        }
        "jsonEquals" -> {
            checkKeys(at, o, setOf("kind", "value"))
            JsonEquals(o["value"] ?: invalidSuite("$at needs \"value\""))
        }
        else -> {
            checkKeys(at, o, setOf("kind", "rubric"))
            Judge(requireString(at, o, "rubric"))
        }
    }
}

private fun parseMessage(where: String, el: JsonElement): Message {
    val o = requireObject(where, el)
    checkKeys(where, o, setOf("role", "content"))
    val roleEl = o["role"] ?: invalidSuite("$where needs \"role\"")
    if (!roleEl.isString()) invalidSuite("$where \"role\" must be a string")
    val role = when (text(roleEl)) {
        "system" -> Role.SYSTEM
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        else -> invalidSuite("$where has an unknown role \"${text(roleEl)}\"")
    }
    return Message(role, requireString(where, o, "content"))
}

private fun parseCase(index: Int, el: JsonElement): EvalCase {
    val where = "case ${index + 1}"
    val o = requireObject(where, el)
    checkKeys(where, o, setOf("id", "messages", "graders"))
    val id = requireString(where, o, "id")
    val messages = o["messages"] as? JsonArray ?: invalidSuite("$where needs \"messages\" as an array")
    val graders = o["graders"] as? JsonArray ?: invalidSuite("$where needs \"graders\" as an array")
    return EvalCase(
        id,
        messages.mapIndexed { i, m -> parseMessage("$where message ${i + 1}", m) },
        graders.mapIndexed { i, g -> parseGrader("$where grader ${i + 1}", g) },
    )
}

/**
 * Validates the semantic rules of a typed suite (everything except the targets); used by [parseSuite] and [runSuite],
 * so a hand-built suite gets the same checks. Throws `IllegalArgumentException("invalid suite: ...")`.
 */
internal fun validateSuite(suite: Suite) {
    if (suite.cases.isEmpty()) invalidSuite("cases must be non-empty")
    val ids = HashSet<String>()
    for (case in suite.cases) {
        if (case.id.isEmpty()) invalidSuite("case ids must be non-empty")
        if (!ids.add(case.id)) invalidSuite("duplicate case id \"${case.id}\"")
        if (case.graders.isEmpty()) invalidSuite("case \"${case.id}\" needs at least one grader")
        for (g in case.graders) validateGrader(case.id, g)
    }
}

private fun validateGrader(caseId: String, g: Grader) {
    val at = "case \"$caseId\" grader ${g.kind}"
    when (g) {
        is OneOf -> if (g.values.isEmpty()) invalidSuite("$at needs a non-empty values list")
        is Length -> {
            if (g.min == null && g.max == null) invalidSuite("$at needs min or max")
            if ((g.min ?: 0) < 0 || (g.max ?: 0) < 0) invalidSuite("$at bounds must be >= 0")
            if (g.min != null && g.max != null && g.min > g.max) invalidSuite("$at min must not exceed max")
        }
        is Json -> try {
            checkSchema(g.schema)
        } catch (e: InvalidSchemaException) {
            invalidSuite("$at has an ${e.message}")
        }
        is Judge -> if (g.rubric.isEmpty()) invalidSuite("$at needs a non-empty rubric")
        is Equals, is Contains, is NotContains, is StartsWith, is EndsWith, is JsonEquals -> Unit
    }
}

/**
 * Parses and validates a suite given as JSON, everything except the targets and the judge. Unknown keys are rejected
 * (not inside `json.schema` nor `jsonEquals.value`). Throws `IllegalArgumentException("invalid suite: ...")`.
 * The input must be a JSON element parsed strictly: lenient parser output (for example an unquoted `2d` literal)
 * is the caller's problem.
 */
fun parseSuite(json: JsonElement): Suite {
    val root = requireObject("the suite", json)
    checkKeys("the suite", root, setOf("cases"))
    val cases = root["cases"] as? JsonArray ?: invalidSuite("the suite needs \"cases\" as an array")
    val suite = Suite(cases.mapIndexed { i, c -> parseCase(i, c) })
    validateSuite(suite)
    return suite
}

/**
 * Parses [json] as strict JSON (`structured`'s rules) and then as [parseSuite]; a parse failure is `invalid suite: ...`.
 * Nesting deeper than 128 levels is rejected here (the strict parser's limit); the contract allows it and the
 * TypeScript implementation accepts it.
 */
fun parseSuite(json: String): Suite =
    parseSuite(parseStrict(json) ?: invalidSuite("the text is not valid strict JSON (or is nested deeper than 128 levels)"))
