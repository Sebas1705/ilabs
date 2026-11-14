package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role

/**
 * Token cost of one message. Applies to every cost computed by this module. Must return a NON-NEGATIVE integer (garbage in, garbage out);
 * the sums are `Int` and can overflow with extreme custom counters (the default counter cannot reach that in practice).
 */
typealias TokenCounter = (Message) -> Int

/** Prefix of a summary message: 37 code points, ending with LF. */
const val SUMMARY_PREFIX = "Summary of the earlier conversation:\n"

/** Number of code points: a high surrogate followed by a low one is a single code point; any other surrogate counts as one. */
private fun codePointCount(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** `ceil(codePoints / 4)`. */
fun estimateTokens(text: String): Int {
    val cp = codePointCount(text)
    return cp / 4 + if (cp % 4 == 0) 0 else 1
}

/** Default counter: 4 tokens of framing overhead plus [estimateTokens] of the content. */
val defaultCounter: TokenCounter = { message -> 4 + estimateTokens(message.content) }

/** True for a `system` message whose content starts with [SUMMARY_PREFIX] (exact, case-sensitive). */
fun isSummaryMessage(message: Message): Boolean =
    message.role == Role.SYSTEM && message.content.startsWith(SUMMARY_PREFIX)
