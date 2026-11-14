package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.Role

/**
 * Default number of most recent non-system messages that [summarizeContext] never summarizes
 * ([SummarizeOptions.keepRecent]); the cross-language contract fixes it at 4.
 */
const val DEFAULT_KEEP_RECENT = 4

data class SummarizeOptions(
    val maxTokens: Int,
    val keepRecent: Int = DEFAULT_KEEP_RECENT,
    val counter: TokenCounter = defaultCounter,
) {
    init {
        require(maxTokens >= 1) { "maxTokens must be >= 1, got $maxTokens" }
        require(keepRecent >= 1) { "keepRecent must be >= 1, got $keepRecent" }
    }
}

data class SummarizeResult(
    val messages: List<Message>,
    val droppedCount: Int,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val summarized: Boolean,
    val summaryCalls: Int,
    /** The request sent to the provider, or null when no call was made. */
    val summarizerRequest: CompletionRequest?,
)

private const val SUMMARIZER_INSTRUCTION =
    "You compress conversations. Summarize the conversation transcript you receive into a concise " +
        "summary that keeps the facts, decisions, names, numbers and open questions needed to continue it. " +
        "Reply with the summary only."

private fun label(role: Role): String = when (role) {
    Role.SYSTEM -> "system"
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
}

/**
 * Keeps the conversation inside [SummarizeOptions.maxTokens]: when it does not fit, the older non-system messages and
 * every existing summary are replaced by ONE new summary (one [provider] call), then [slidingWindow] runs if it
 * still does not fit. Provider failures, other exceptions and cancellation propagate unchanged.
 * Throws [BudgetTooSmallException] when the budget cannot hold the system messages plus the last non-system message.
 */
suspend fun summarizeContext(provider: Provider, messages: List<Message>, options: SummarizeOptions): SummarizeResult {
    val max = options.maxTokens
    val windowOptions = WindowOptions(max, options.counter)
    val costs = messages.map(options.counter)
    val total = costs.sum()
    if (total <= max) return SummarizeResult(messages.toList(), 0, total, total, false, 0, null)

    val nonSystem = messages.indices.filter { messages[it].role != Role.SYSTEM }
    val protectedCount = minOf(options.keepRecent, nonSystem.size)
    val older = nonSystem.subList(0, nonSystem.size - protectedCount)
    if (older.isEmpty()) {
        val w = slidingWindow(messages, windowOptions)
        return SummarizeResult(w.messages, w.droppedCount, total, w.tokensAfter, false, 0, null)
    }

    val removed = BooleanArray(messages.size)
    for (i in older) removed[i] = true
    messages.forEachIndexed { i, m -> if (isSummaryMessage(m)) removed[i] = true }

    val transcript = messages.filterIndexed { i, _ -> removed[i] }
        .joinToString("\n\n") { "${label(it.role)}: ${it.content}" }
    val request = CompletionRequest(
        listOf(Message(Role.SYSTEM, SUMMARIZER_INSTRUCTION), Message(Role.USER, transcript)),
    )
    val reply = provider.complete(request)

    val summary = Message(Role.SYSTEM, SUMMARY_PREFIX + reply.text)
    val rebuilt = ArrayList<Message>(messages.size)
    var inserted = false
    messages.forEachIndexed { i, m ->
        if (!removed[i]) {
            rebuilt += m
        } else if (!inserted) {
            rebuilt += summary
            inserted = true
        }
    }

    val rebuiltTotal = rebuilt.sumOf(options.counter)
    val finalMessages: List<Message>
    val tokensAfter: Int
    if (rebuiltTotal <= max) {
        finalMessages = rebuilt
        tokensAfter = rebuiltTotal
    } else {
        val w = slidingWindow(rebuilt, windowOptions)
        finalMessages = w.messages
        tokensAfter = w.tokensAfter
    }
    // Every message of the final output except the new summary came from the input.
    val dropped = messages.size - (finalMessages.size - 1)
    return SummarizeResult(finalMessages, dropped, total, tokensAfter, true, 1, request)
}
