package es.sebas1705.ilabs.structured

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

enum class IssueCode(val wire: String) {
    TYPE("type"),
    ENUM("enum"),
    MINIMUM("minimum"),
    MAXIMUM("maximum"),
    MIN_LENGTH("minLength"),
    MAX_LENGTH("maxLength"),
    MIN_ITEMS("minItems"),
    MAX_ITEMS("maxItems"),
    REQUIRED("required"),
    ADDITIONAL_PROPERTIES("additionalProperties"),
    NO_JSON("no_json"),
    INVALID_JSON("invalid_json"),
}

data class Issue(val path: String, val code: IssueCode)

class InvalidSchemaException(reason: String) : IllegalArgumentException("invalid schema: $reason")

private val TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
private val NUMERIC_KEYWORDS = listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")

private fun isString(v: JsonElement) = v is JsonPrimitive && v !is JsonNull && v.isString
private fun isBoolean(v: JsonElement) = v is JsonPrimitive && v !is JsonNull && !v.isString && v.booleanOrNull != null
private fun isNumber(v: JsonElement) =
    v is JsonPrimitive && v !is JsonNull && !v.isString && v.booleanOrNull == null && v.doubleOrNull != null

private fun check(schema: JsonElement, at: String) {
    if (schema !is JsonObject) throw InvalidSchemaException("${at.ifEmpty { "schema" }} must be an object")
    val where = if (at.isEmpty()) "" else " at $at"
    schema["type"]?.let { t ->
        if (!isString(t) || (t as JsonPrimitive).content !in TYPES) throw InvalidSchemaException("unknown type$where")
    }
    schema["enum"]?.let { e ->
        if (e !is JsonArray || e.isEmpty()) throw InvalidSchemaException("enum must be a non-empty array$where")
    }
    for (k in NUMERIC_KEYWORDS) {
        val v = schema[k] ?: continue
        if (!isNumber(v) || !(v as JsonPrimitive).doubleOrNull!!.isFinite()) {
            throw InvalidSchemaException("$k must be a number$where")
        }
    }
    schema["required"]?.let { r ->
        if (r !is JsonArray || !r.all { isString(it) }) {
            throw InvalidSchemaException("required must be an array of strings$where")
        }
    }
    schema["additionalProperties"]?.let { a ->
        if (!isBoolean(a)) throw InvalidSchemaException("additionalProperties must be a boolean$where")
    }
    schema["properties"]?.let { props ->
        if (props !is JsonObject) throw InvalidSchemaException("properties must be an object$where")
        for ((name, sub) in props) check(sub, "$at/properties/$name")
    }
    schema["items"]?.let { check(it, "$at/items") }
}

/** Throws [InvalidSchemaException] (`invalid schema: <reason>`) when the schema is malformed. Unknown keywords are ignored. */
fun checkSchema(schema: JsonObject) = check(schema, "")

private fun escapePointer(name: String) = name.replace("~", "~0").replace("/", "~1")

private fun numberOf(v: JsonElement): Double? =
    if (isNumber(v)) (v as JsonPrimitive).doubleOrNull else null

/** JSON deep equality: numbers by value, objects without key order, no coercion between kinds. */
internal fun jsonEqual(a: JsonElement, b: JsonElement): Boolean {
    if (a is JsonObject) {
        return b is JsonObject && a.size == b.size && a.all { (k, v) -> b[k]?.let { jsonEqual(v, it) } ?: false }
    }
    if (a is JsonArray) {
        return b is JsonArray && a.size == b.size && a.indices.all { jsonEqual(a[it], b[it]) }
    }
    val x = numberOf(a)
    if (x != null) {
        val y = numberOf(b)
        return y != null && x == y
    }
    if (a is JsonNull) return b is JsonNull
    if (isBoolean(a)) return isBoolean(b) && (a as JsonPrimitive).booleanOrNull == (b as JsonPrimitive).booleanOrNull
    if (isString(a)) return isString(b) && (a as JsonPrimitive).content == (b as JsonPrimitive).content
    return false
}

private fun typeMatches(type: String, v: JsonElement): Boolean = when (type) {
    "object" -> v is JsonObject
    "array" -> v is JsonArray
    "string" -> isString(v)
    "number" -> isNumber(v)
    "integer" -> numberOf(v)?.let { it.isFinite() && it == kotlin.math.floor(it) } ?: false
    "boolean" -> isBoolean(v)
    "null" -> v is JsonNull
    else -> true
}

/** Number of Unicode code points; a lone surrogate counts as one. */
private fun codePointCount(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i++
        i++
        n++
    }
    return n
}

private fun limit(schema: JsonObject, key: String): Double? = schema[key]?.let { numberOf(it) }

private fun walk(schema: JsonObject, value: JsonElement, path: String, out: MutableList<Issue>) {
    fun push(code: IssueCode, at: String = path) {
        out += Issue(at, code)
    }

    val type = schema["type"]
    if (type != null && isString(type) && !typeMatches((type as JsonPrimitive).content, value)) {
        push(IssueCode.TYPE)
        return
    }
    (schema["enum"] as? JsonArray)?.let { e -> if (e.none { jsonEqual(it, value) }) push(IssueCode.ENUM) }

    when {
        isNumber(value) -> {
            val n = numberOf(value)!!
            limit(schema, "minimum")?.let { if (n < it) push(IssueCode.MINIMUM) }
            limit(schema, "maximum")?.let { if (n > it) push(IssueCode.MAXIMUM) }
        }
        isString(value) -> {
            val len = codePointCount((value as JsonPrimitive).content)
            limit(schema, "minLength")?.let { if (len < it) push(IssueCode.MIN_LENGTH) }
            limit(schema, "maxLength")?.let { if (len > it) push(IssueCode.MAX_LENGTH) }
        }
        value is JsonArray -> {
            limit(schema, "minItems")?.let { if (value.size < it) push(IssueCode.MIN_ITEMS) }
            limit(schema, "maxItems")?.let { if (value.size > it) push(IssueCode.MAX_ITEMS) }
            (schema["items"] as? JsonObject)?.let { items ->
                value.forEachIndexed { i, item -> walk(items, item, "$path/$i", out) }
            }
        }
        value is JsonObject -> {
            (schema["required"] as? JsonArray)?.forEach { r ->
                val name = (r as? JsonPrimitive)?.takeIf { isString(it) }?.content ?: return@forEach
                if (name !in value) push(IssueCode.REQUIRED, "$path/${escapePointer(name)}")
            }
            val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val extra = schema["additionalProperties"]
            if (extra is JsonPrimitive && extra.booleanOrNull == false && !extra.isString) {
                for (name in value.keys.sorted()) {
                    if (name !in props) push(IssueCode.ADDITIONAL_PROPERTIES, "$path/${escapePointer(name)}")
                }
            }
            for (name in props.keys.sorted()) {
                val sub = props[name] as? JsonObject ?: continue
                val v = value[name] ?: continue
                walk(sub, v, "$path/${escapePointer(name)}", out)
            }
        }
    }
}

/** Validates a parsed JSON value; issues are in the contract order. */
fun validate(schema: JsonObject, value: JsonElement): List<Issue> {
    val out = mutableListOf<Issue>()
    walk(schema, value, "", out)
    return out
}
