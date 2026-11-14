package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.structured.Extraction
import es.sebas1705.ilabs.structured.Issue
import es.sebas1705.ilabs.structured.extractJson
import es.sebas1705.ilabs.structured.jsonEqual
import es.sebas1705.ilabs.structured.validate

private fun isJsonWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

/** Removes leading space, tab, LF and CR only (not Kotlin's `trimStart`, which also strips no-break space and more). */
internal fun trimStartJson(s: String): String {
    var start = 0
    while (start < s.length && isJsonWhitespace(s[start])) start++
    return s.substring(start)
}

/** Removes space, tab, LF and CR from both ends only (not Kotlin's `trim`). */
private fun trimJson(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isJsonWhitespace(s[start])) start++
    while (end > start && isJsonWhitespace(s[end - 1])) end--
    return s.substring(start, end)
}

/** Folds A-Z to a-z and leaves every other character alone (not `ignoreCase = true`, which is Unicode-aware). */
private fun foldAscii(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
    return sb.toString()
}

private fun normalize(s: String, trim: Boolean, ignoreCase: Boolean): String {
    var r = s
    if (trim) r = trimJson(r)
    if (ignoreCase) r = foldAscii(r)
    return r
}

/** Number of Unicode code points; a high surrogate followed by a low surrogate is one, any other surrogate is one on its own. */
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

/** The pure, exported form of one non-judge grader. */
fun evaluateGrader(grader: PureGrader, reply: String): GraderResult {
    fun text(value: String, trim: Boolean, ignoreCase: Boolean, test: (String, String) -> Boolean): GraderResult =
        GraderResult(grader.kind, test(normalize(reply, trim, ignoreCase), normalize(value, trim, ignoreCase)))

    return when (grader) {
        is Equals -> text(grader.value, grader.trim, grader.ignoreCase) { r, v -> r == v }
        is Contains -> text(grader.value, grader.trim, grader.ignoreCase) { r, v -> r.contains(v) }
        is NotContains -> text(grader.value, grader.trim, grader.ignoreCase) { r, v -> !r.contains(v) }
        is StartsWith -> text(grader.value, grader.trim, grader.ignoreCase) { r, v -> r.startsWith(v) }
        is EndsWith -> text(grader.value, grader.trim, grader.ignoreCase) { r, v -> r.endsWith(v) }
        is OneOf -> {
            val r = normalize(reply, grader.trim, grader.ignoreCase)
            GraderResult(grader.kind, grader.values.any { normalize(it, grader.trim, grader.ignoreCase) == r })
        }
        is Length -> {
            val n = codePointCount(reply)
            GraderResult(grader.kind, (grader.min?.let { n >= it } ?: true) && (grader.max?.let { n <= it } ?: true))
        }
        is Json -> when (val e = extractJson(reply)) {
            is Extraction.Found -> {
                val issues = validate(grader.schema, e.value)
                GraderResult(grader.kind, issues.isEmpty(), issues)
            }
            is Extraction.Failed -> GraderResult(grader.kind, false, listOf(Issue("", e.code)))
        }
        is JsonEquals -> when (val e = extractJson(reply)) {
            is Extraction.Found -> GraderResult(grader.kind, jsonEqual(grader.value, e.value), emptyList())
            is Extraction.Failed -> GraderResult(grader.kind, false, listOf(Issue("", e.code)))
        }
    }
}
