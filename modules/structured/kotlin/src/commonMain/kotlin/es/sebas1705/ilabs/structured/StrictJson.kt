package es.sebas1705.ilabs.structured

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun isJsonWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

private class Malformed : Exception() {
    // No stack trace: failing candidates are routine.
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Strict RFC 8259 parser. `Json.parseToJsonElement` is lenient (bare words, raw control characters,
 * loose number syntax), so candidates are parsed here instead. Duplicate keys: the last one wins.
 * Returns null when [text] is not exactly one JSON value; nesting beyond [maxDepth] also yields null.
 */
internal fun parseStrictJson(text: String, maxDepth: Int): JsonElement? =
    try {
        StrictParser(text, maxDepth).parseDocument()
    } catch (_: Malformed) {
        null
    }

private const val MAX_STRICT_DEPTH = 128

/**
 * RFC 8259 strict parse of [text] as exactly one JSON value: nesting depth limit 128, duplicate keys: last wins.
 * Returns null when [text] is not strict JSON.
 */
fun parseStrict(text: String): JsonElement? = parseStrictJson(text, MAX_STRICT_DEPTH)

private class StrictParser(private val s: String, private val maxDepth: Int) {
    private var i = 0

    fun parseDocument(): JsonElement {
        skipWs()
        val v = parseValue(0)
        skipWs()
        if (i != s.length) fail()
        return v
    }

    private fun fail(): Nothing = throw Malformed()

    private fun skipWs() {
        while (i < s.length && isJsonWhitespace(s[i])) i++
    }

    private fun parseValue(depth: Int): JsonElement {
        if (i >= s.length) fail()
        return when (val c = s[i]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonPrimitive(parseString())
            't' -> literal("true", JsonPrimitive(true))
            'f' -> literal("false", JsonPrimitive(false))
            'n' -> literal("null", JsonNull)
            else -> if (c == '-' || c in '0'..'9') parseNumber() else fail()
        }
    }

    private fun literal(word: String, value: JsonElement): JsonElement {
        if (!s.startsWith(word, i)) fail()
        i += word.length
        return value
    }

    private fun parseObject(depth: Int): JsonElement {
        if (depth > maxDepth) fail()
        i++ // {
        val map = LinkedHashMap<String, JsonElement>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return JsonObject(map)
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') fail()
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') fail()
            i++
            skipWs()
            map[key] = parseValue(depth)
            skipWs()
            if (i >= s.length) fail()
            when (s[i++]) {
                ',' -> continue
                '}' -> return JsonObject(map)
                else -> fail()
            }
        }
    }

    private fun parseArray(depth: Int): JsonElement {
        if (depth > maxDepth) fail()
        i++ // [
        val list = ArrayList<JsonElement>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return JsonArray(list)
        }
        while (true) {
            skipWs()
            list += parseValue(depth)
            skipWs()
            if (i >= s.length) fail()
            when (s[i++]) {
                ',' -> continue
                ']' -> return JsonArray(list)
                else -> fail()
            }
        }
    }

    private fun parseString(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail()
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c < ' ' -> fail()
                c == '\\' -> {
                    if (i >= s.length) fail()
                    when (s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail()
                            var code = 0
                            repeat(4) {
                                code = code * 16 + hexValue(s[i++])
                            }
                            sb.append(code.toChar())
                        }
                        else -> fail()
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    /** ASCII hex digit only: Char.digitToIntOrNull also accepts other Unicode digits and fullwidth letters. */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> fail()
    }

    private fun digitsFrom(): Int {
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        return i - start
    }

    private fun parseNumber(): JsonElement {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail()
        if (s[i] == '0') i++ else if (s[i] in '1'..'9') digitsFrom() else fail()
        var integral = true
        if (i < s.length && s[i] == '.') {
            i++
            if (digitsFrom() == 0) fail()
            integral = false
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (digitsFrom() == 0) fail()
            integral = false
        }
        val raw = s.substring(start, i)
        if (integral) raw.toLongOrNull()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(raw.toDouble())
    }
}
