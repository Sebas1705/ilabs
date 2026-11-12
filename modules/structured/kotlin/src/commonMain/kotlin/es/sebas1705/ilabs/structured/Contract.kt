package es.sebas1705.ilabs.structured

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.CompletionResponse
import es.sebas1705.ilabs.providers.ErrorKind
import es.sebas1705.ilabs.providers.MockProvider
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.ProviderException
import es.sebas1705.ilabs.providers.Role
import es.sebas1705.ilabs.providers.ScriptEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class FixtureSetup(val maxRepairs: Int)

@Serializable
data class FixtureProvider(val id: String, val script: List<ScriptEntry> = emptyList())

/** `result` is `{"value": <json>}` or `{"error": {"kind": ..., "issues"?: [...]}}`; kept raw so a `null` value is distinguishable. */
@Serializable
data class Expectation(val result: JsonObject, val attempts: Int, val appended: List<Role>)

@Serializable
data class Fixture(
    val name: String,
    val schema: JsonObject,
    val request: CompletionRequest,
    val setup: FixtureSetup,
    val provider: FixtureProvider,
    val expect: Expectation,
)

sealed interface FixtureResult {
    data class Success(val value: JsonElement) : FixtureResult

    /** `issues` is null for provider errors, which carry none. */
    data class Failure(val kind: ErrorKind, val issues: List<Issue>? = null) : FixtureResult
}

data class FixtureOutcome(
    val result: FixtureResult,
    val attempts: Int,
    val appended: List<Role>,
    val trace: List<AttemptTrace>,
)

private val fixtureJson = Json { ignoreUnknownKeys = true }

fun parseFixture(json: String): Fixture = fixtureJson.decodeFromString(Fixture.serializer(), json)

private fun invalid(reason: String): Nothing = error("invalid fixture: $reason")

private fun validateFixture(fixture: Fixture) {
    fixture.provider.script.forEachIndexed { i, e ->
        if (e.response == null && e.error == null) invalid("script entry ${i + 1} has neither response nor error")
    }
}

private fun deriveTrace(reply: String, schema: JsonObject): AttemptTrace {
    val extracted = extractJson(reply)
    val issues = when (extracted) {
        is Extraction.Found -> validate(schema, extracted.value)
        is Extraction.Failed -> listOf(Issue("", extracted.code))
    }
    return AttemptTrace(reply, extracted, issues)
}

/** Runs a contract fixture against `structured`. Browser/Android-safe (no java.*). */
suspend fun runFixture(fixture: Fixture): FixtureOutcome {
    validateFixture(fixture)
    val mock = MockProvider(fixture.provider.id, fixture.provider.script)
    val original = fixture.request.messages.size
    var attempts = 0
    var last: List<Role> = emptyList()
    val replies = mutableListOf<String>()
    val provider = object : Provider {
        override val id = mock.id
        override suspend fun complete(req: CompletionRequest): CompletionResponse {
            attempts++
            last = req.messages.drop(original).map { it.role }
            return mock.complete(req).also { replies += it.text }
        }
    }

    return try {
        val r = structured(provider, fixture.request, fixture.schema, StructuredOptions(fixture.setup.maxRepairs))
        FixtureOutcome(FixtureResult.Success(r.value), attempts, last, r.trace)
    } catch (e: StructuredException) {
        FixtureOutcome(FixtureResult.Failure(ErrorKind.INVALID_OUTPUT, e.issues), attempts, last, e.trace)
    } catch (e: ProviderException) {
        // A provider error propagates unchanged from structured(), so rebuild the trace from the replies received before it.
        FixtureOutcome(FixtureResult.Failure(e.kind), attempts, last, replies.map { deriveTrace(it, fixture.schema) })
    }
}
