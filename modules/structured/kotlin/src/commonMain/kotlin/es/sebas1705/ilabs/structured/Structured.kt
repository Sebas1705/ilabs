package es.sebas1705.ilabs.structured

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.ErrorKind
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.Role
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class AttemptTrace(val reply: String, val extracted: Extraction, val issues: List<Issue>)

data class StructuredOptions(
    /** Maximum number of repair calls after the first one. Default 2; must be >= 0. */
    val maxRepairs: Int = 2,
) {
    init {
        require(maxRepairs >= 0) { "maxRepairs must be >= 0, got $maxRepairs" }
    }
}

data class StructuredResult(val value: JsonElement, val attempts: Int, val trace: List<AttemptTrace>)

/** The reply never became valid within the allowed attempts (`invalid_output`). */
class StructuredException(
    val issues: List<Issue>,
    val attempts: Int,
    val trace: List<AttemptTrace>,
) : Exception("invalid_output after $attempts attempt(s)") {
    val kind: ErrorKind = ErrorKind.INVALID_OUTPUT
}

private fun repairMessage(issues: List<Issue>): String {
    val list = issues.joinToString("; ") { "${it.path.ifEmpty { "(root)" }}: ${it.code.wire}" }
    return "Your previous reply was not valid. Problems: $list. Reply again with only the corrected JSON."
}

/** Asks the provider for JSON matching the schema, repairing up to `options.maxRepairs` times. */
suspend fun structured(
    provider: Provider,
    request: CompletionRequest,
    schema: JsonObject,
    options: StructuredOptions = StructuredOptions(),
): StructuredResult {
    checkSchema(schema)
    val maxRepairs = options.maxRepairs

    val messages = request.messages.toMutableList()
    val trace = mutableListOf<AttemptTrace>()
    var attempts = 0
    while (true) {
        attempts++
        val text = provider.complete(request.copy(messages = messages.toList())).text
        val extracted = extractJson(text)
        val issues = when (extracted) {
            is Extraction.Found -> validate(schema, extracted.value)
            is Extraction.Failed -> listOf(Issue("", extracted.code))
        }
        trace += AttemptTrace(text, extracted, issues)
        if (extracted is Extraction.Found && issues.isEmpty()) return StructuredResult(extracted.value, attempts, trace.toList())
        if (attempts > maxRepairs) throw StructuredException(issues, attempts, trace.toList())
        messages += Message(Role.ASSISTANT, text)
        messages += Message(Role.USER, repairMessage(issues))
    }
}
