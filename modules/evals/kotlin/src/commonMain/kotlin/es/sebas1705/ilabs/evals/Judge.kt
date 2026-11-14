package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.ProviderException
import es.sebas1705.ilabs.providers.Role

private const val JUDGE_INSTRUCTION =
    "You are a strict evaluator. The user message has three parts separated by lines of three dashes: " +
        "a rubric, the last user message of a conversation and the assistant reply to evaluate. " +
        "Decide whether the reply satisfies the rubric. Answer with PASS or FAIL as the first word, " +
        "optionally followed by a short reason."

/** The judge request: exactly two messages (system instruction, then rubric, last user message and reply), no other fields. */
internal fun judgeRequest(rubric: String, messages: List<Message>, reply: String): CompletionRequest {
    val lastUser = messages.lastOrNull { it.role == Role.USER }?.content ?: ""
    return CompletionRequest(
        listOf(
            Message(Role.SYSTEM, JUDGE_INSTRUCTION),
            Message(Role.USER, "$rubric\n---\n$lastUser\n---\n$reply"),
        ),
    )
}

private fun isAsciiWordChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

/** `true` for `PASS`, `false` for `FAIL`, null when the text is not a verdict. */
internal fun parseVerdict(text: String): Boolean? {
    val t = trimStartJson(text)
    for ((word, verdict) in listOf("PASS" to true, "FAIL" to false)) {
        if (t.startsWith(word) && (t.length == word.length || !isAsciiWordChar(t[word.length]))) return verdict
    }
    return null
}

/** Runs one judge grader. Only [ProviderException] is turned into an error entry; anything else propagates. */
internal suspend fun runJudge(
    grader: Judge,
    messages: List<Message>,
    reply: String,
    judge: Provider?,
    onCall: () -> Unit,
): GraderResult {
    if (judge == null) return GraderResult(grader.kind, false, error = "no_judge")
    onCall()
    val verdict = try {
        parseVerdict(judge.complete(judgeRequest(grader.rubric, messages, reply)).text)
    } catch (e: ProviderException) {
        return GraderResult(grader.kind, false, error = e.kind.wire)
    }
    return if (verdict == null) GraderResult(grader.kind, false, error = "bad_verdict") else GraderResult(grader.kind, verdict)
}
