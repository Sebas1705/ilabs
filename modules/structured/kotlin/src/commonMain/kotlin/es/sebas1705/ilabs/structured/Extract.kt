package es.sebas1705.ilabs.structured

import kotlinx.serialization.json.JsonElement

sealed interface Extraction {
    data class Found(val value: JsonElement) : Extraction
    data class Failed(val code: IssueCode) : Extraction
}

private const val MAX_DEPTH = 128
private const val FENCE = "```"

/** Trims JSON whitespace only (not Kotlin's `trim`, which also strips no-break space and more). */
private fun trimJson(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isJsonWhitespace(s[start])) start++
    while (end > start && isJsonWhitespace(s[end - 1])) end--
    return s.substring(start, end)
}

/** Maximum number of simultaneously open arrays/objects outside string literals. Iterative. */
private fun maxDepth(text: String): Int {
    var depth = 0
    var max = 0
    var inString = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else if (c == '"') {
            inString = true
        } else if (c == '{' || c == '[') {
            depth++
            if (depth > max) max = depth
        } else if (c == '}' || c == ']') {
            depth--
        }
        i++
    }
    return max
}

private fun tryParse(candidate: String): Extraction.Found? {
    if (maxDepth(candidate) > MAX_DEPTH) return null
    return parseStrictJson(candidate, MAX_DEPTH)?.let { Extraction.Found(it) }
}

private fun fencedBlocks(text: String): List<String> {
    val lines = text.split("\n")
    val blocks = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].startsWith(FENCE)) {
            i++
            continue
        }
        var close = -1
        for (j in i + 1 until lines.size) {
            val line = lines[j]
            var end = line.length
            while (end > 0 && isJsonWhitespace(line[end - 1])) end--
            if (end == FENCE.length && line.startsWith(FENCE)) {
                close = j
                break
            }
        }
        if (close < 0) break
        blocks += lines.subList(i + 1, close).joinToString("\n")
        i = close + 1
    }
    return blocks
}

/** From the first `{` or `[` to its balanced close, string-literal aware; null when unbalanced. */
private fun balancedRegion(text: String): String? {
    val start = text.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    var depth = 0
    var inString = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else if (c == '"') {
            inString = true
        } else if (c == '{' || c == '[') {
            depth++
        } else if (c == '}' || c == ']') {
            depth--
            if (depth == 0) return text.substring(start, i + 1)
        }
        i++
    }
    return null
}

/** Finds the first strict-JSON candidate: whole text, fenced blocks, then the first balanced region. */
fun extractJson(text: String): Extraction {
    tryParse(trimJson(text))?.let { return it }
    for (block in fencedBlocks(text)) tryParse(block)?.let { return it }
    balancedRegion(text)?.let { region -> tryParse(region)?.let { return it } }
    val hasMarker = text.contains('{') || text.contains('[') || text.contains(FENCE)
    return Extraction.Failed(if (hasMarker) IssueCode.INVALID_JSON else IssueCode.NO_JSON)
}
