package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.CompletionResponse
import es.sebas1705.ilabs.providers.MockProvider
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.ProviderException
import es.sebas1705.ilabs.providers.ScriptEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class FixtureSetup(val kind: String, val maxTokens: Int, val keepRecent: Int? = null)

@Serializable
data class FixtureProvider(val id: String, val script: List<ScriptEntry> = emptyList())

/** `result` is a result object or `{"error": {"kind": ..., "required"?: ...}}`; kept raw so each runner compares what is present. */
@Serializable
data class Expectation(
    val result: JsonObject,
    val calls: Int? = null,
    val transcriptContains: List<String>? = null,
    val requestExcludes: List<String>? = null,
)

@Serializable
data class Fixture(
    val name: String,
    val setup: FixtureSetup,
    val messages: List<Message>,
    val provider: FixtureProvider? = null,
    val expect: Expectation,
)

sealed interface FixtureResult {
    /** `summarized` and `summaryCalls` are null for a `window` fixture. */
    data class Success(
        val messages: List<Message>,
        val droppedCount: Int,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val summarized: Boolean? = null,
        val summaryCalls: Int? = null,
    ) : FixtureResult

    /** `kind` is `budget_too_small` or a provider error kind; `required` is set only for `budget_too_small`. */
    data class Failure(val kind: String, val required: Int? = null) : FixtureResult
}

/** `summarizerRequest` is the first request the provider received (null when no call was made). */
data class FixtureOutcome(val result: FixtureResult, val calls: Int, val summarizerRequest: CompletionRequest?)

private val fixtureJson = Json { ignoreUnknownKeys = true }

fun parseFixture(json: String): Fixture = fixtureJson.decodeFromString(Fixture.serializer(), json)

private fun invalid(reason: String): Nothing = error("invalid fixture: $reason")

private fun validateFixture(fixture: Fixture) {
    val kind = fixture.setup.kind
    if (kind != "window" && kind != "summarize") invalid("unknown setup kind: $kind")
    if (kind == "summarize" && fixture.provider == null) invalid("summarize setup needs a provider")
    val p = fixture.provider ?: return
    p.script.forEachIndexed { i, e ->
        if (e.response == null && e.error == null) {
            invalid("script entry ${i + 1} of provider ${p.id} has neither response nor error")
        }
    }
}

/** Runs a contract fixture against `context`, recording the provider calls. Browser/Android-safe (no java.*). */
suspend fun runFixture(fixture: Fixture): FixtureOutcome {
    validateFixture(fixture)
    val setup = fixture.setup
    if (setup.kind == "window") {
        val result = try {
            val w = slidingWindow(fixture.messages, WindowOptions(setup.maxTokens))
            FixtureResult.Success(w.messages, w.droppedCount, w.tokensBefore, w.tokensAfter)
        } catch (e: BudgetTooSmallException) {
            FixtureResult.Failure("budget_too_small", e.required)
        }
        return FixtureOutcome(result, 0, null)
    }

    val spec = fixture.provider!!
    val mock = MockProvider(spec.id, spec.script)
    var calls = 0
    var recorded: CompletionRequest? = null
    val provider = object : Provider {
        override val id = mock.id
        override suspend fun complete(req: CompletionRequest): CompletionResponse {
            calls++
            if (recorded == null) recorded = req
            return mock.complete(req)
        }
    }
    val options = SummarizeOptions(setup.maxTokens, setup.keepRecent ?: 4)
    val result = try {
        val r = summarizeContext(provider, fixture.messages, options)
        FixtureResult.Success(r.messages, r.droppedCount, r.tokensBefore, r.tokensAfter, r.summarized, r.summaryCalls)
    } catch (e: BudgetTooSmallException) {
        FixtureResult.Failure("budget_too_small", e.required)
    } catch (e: ProviderException) {
        FixtureResult.Failure(e.kind.wire)
    }
    return FixtureOutcome(result, calls, recorded)
}
