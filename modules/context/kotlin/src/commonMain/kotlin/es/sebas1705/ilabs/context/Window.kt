package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role

/** The budget cannot hold the system messages plus the last non-system message; [required] is the smallest budget that would. */
class BudgetTooSmallException(val required: Int) : Exception("budget too small: needs $required tokens")

data class WindowOptions(val maxTokens: Int, val counter: TokenCounter = defaultCounter) {
    init {
        require(maxTokens >= 1) { "maxTokens must be >= 1, got $maxTokens" }
    }
}

data class WindowResult(
    val messages: List<Message>,
    val droppedCount: Int,
    val tokensBefore: Int,
    val tokensAfter: Int,
)

/**
 * Drops the oldest non-system messages until the conversation fits. System messages are never dropped or moved.
 * Throws [BudgetTooSmallException] when even the system messages plus the last non-system message do not fit.
 */
fun slidingWindow(messages: List<Message>, options: WindowOptions): WindowResult {
    val max = options.maxTokens
    val costs = messages.map(options.counter)
    val total = costs.sum()
    if (total <= max) return WindowResult(messages.toList(), 0, total, total)

    var systemCost = 0
    var last = -1
    messages.forEachIndexed { i, m ->
        if (m.role == Role.SYSTEM) systemCost += costs[i] else last = i
    }
    if (last < 0) throw BudgetTooSmallException(systemCost)
    val required = systemCost + costs[last]
    if (required > max) throw BudgetTooSmallException(required)

    // Longest contiguous suffix of the non-system messages that fits, scanning from the end; stops at the first miss.
    val keep = BooleanArray(messages.size)
    var used = required
    keep[last] = true
    var i = last - 1
    while (i >= 0) {
        if (messages[i].role != Role.SYSTEM) {
            if (used + costs[i] > max) break
            used += costs[i]
            keep[i] = true
        }
        i--
    }
    val out = messages.filterIndexed { idx, m -> m.role == Role.SYSTEM || keep[idx] }
    return WindowResult(out, messages.size - out.size, total, used)
}
