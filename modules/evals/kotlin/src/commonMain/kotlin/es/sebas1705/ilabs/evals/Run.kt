package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.ProviderException

private const val JUDGE_KEY = "judge"

private fun validateTargets(targets: List<Provider>) {
    val seen = HashSet<String>()
    for (t in targets) {
        if (t.id.isEmpty()) invalidSuite("target ids must be non-empty")
        if (t.id == JUDGE_KEY) invalidSuite("the target id \"judge\" is reserved")
        if (!seen.add(t.id)) invalidSuite("duplicate target id \"${t.id}\"")
    }
}

private fun statusOf(graders: List<GraderResult>): Status = when {
    graders.any { it.error != null } -> Status.ERROR
    graders.any { !it.passed } -> Status.FAIL
    else -> Status.PASS
}

/**
 * Runs every case against every target, sequentially (case order, then target order), grades each reply and
 * summarizes. The suite and the targets are validated completely before any call (`invalid suite: ...`).
 * Only a [ProviderException] is recorded as a result; any other failure (including cancellation) propagates.
 */
suspend fun runSuite(suite: Suite, targets: List<Provider>, judge: Provider? = null): RunOutput {
    validateTargets(targets)
    validateSuite(suite)

    val calls = LinkedHashMap<String, Int>()
    for (t in targets) calls[t.id] = 0
    if (judge != null) calls[JUDGE_KEY] = 0

    val results = ArrayList<CaseResult>()
    for (case in suite.cases) {
        for (target in targets) {
            calls[target.id] = calls.getValue(target.id) + 1
            val reply = try {
                target.complete(CompletionRequest(case.messages)).text
            } catch (e: ProviderException) {
                results += CaseResult(case.id, target.id, Status.ERROR, null, e.kind.wire, emptyList())
                continue
            }
            val graders = case.graders.map { grader ->
                when (grader) {
                    is PureGrader -> evaluateGrader(grader, reply)
                    is Judge -> runJudge(grader, case.messages, reply, judge) { calls[JUDGE_KEY] = calls.getValue(JUDGE_KEY) + 1 }
                }
            }
            results += CaseResult(case.id, target.id, statusOf(graders), reply, null, graders)
        }
    }
    return RunOutput(results, summarize(results), calls)
}
