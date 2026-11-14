package es.sebas1705.ilabs.evals

/** One entry per target in order of first appearance; `passRate = passed / total`. */
fun summarize(results: List<CaseResult>): List<TargetSummary> {
    val counts = LinkedHashMap<String, IntArray>() // total, passed, failed, errored
    for (r in results) {
        val c = counts.getOrPut(r.targetId) { IntArray(4) }
        c[0]++
        when (r.status) {
            Status.PASS -> c[1]++
            Status.FAIL -> c[2]++
            Status.ERROR -> c[3]++
        }
    }
    return counts.map { (id, c) -> TargetSummary(id, c[0], c[1], c[2], c[3], c[1].toDouble() / c[0].toDouble()) }
}
