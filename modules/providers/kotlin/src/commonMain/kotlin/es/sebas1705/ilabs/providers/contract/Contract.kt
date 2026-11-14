package es.sebas1705.ilabs.providers.contract

import es.sebas1705.ilabs.providers.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FixtureSetup(
    val kind: String,
    val maxAttempts: Int? = null,
    val baseDelayMs: Long? = null,
    val maxDelayMs: Long? = null,
)

@Serializable
data class FixtureProvider(val id: String, val script: List<ScriptEntry> = emptyList())

/** Expected result: a response (`text` + `provider`) or an `error`. */
@Serializable
data class ExpectedResult(
    val text: String? = null,
    val provider: String? = null,
    val error: ScriptError? = null,
)

/** Expected trace entry: `type` is "call" (`provider`, `outcome`) or "wait" (`ms`). */
@Serializable
data class ExpectedEvent(
    val type: String,
    val provider: String? = null,
    val outcome: String? = null,
    val ms: Long? = null,
) {
    fun toEvent(): Event = when (type) {
        "call" -> Event.Call(
            provider ?: error("call event needs a provider"),
            outcome ?: error("call event needs an outcome"),
        )
        "wait" -> Event.Wait(ms ?: error("wait event needs ms"))
        else -> error("unknown event type: $type")
    }
}

@Serializable
data class Expectation(
    val result: ExpectedResult,
    val sleeps: List<Long>,
    val calls: Map<String, Int>,
    val events: List<ExpectedEvent>? = null,
)

@Serializable
data class Fixture(
    val name: String,
    val setup: FixtureSetup,
    val providers: List<FixtureProvider>,
    val request: CompletionRequest,
    val expect: Expectation,
)

sealed interface FixtureResult {
    data class Success(val text: String, val provider: String) : FixtureResult
    data class Failure(val kind: ErrorKind, val retryAfterMs: Long? = null) : FixtureResult
}

/** Ordered log entry: a provider call (`outcome` is "ok" or the snake_case error kind) or a wait. */
sealed interface Event {
    data class Call(val provider: String, val outcome: String) : Event
    data class Wait(val ms: Long) : Event
}

data class FixtureOutcome(
    val result: FixtureResult,
    val sleeps: List<Long>,
    val calls: Map<String, Int>,
    val events: List<Event>,
)

private val fixtureJson = Json { ignoreUnknownKeys = true }

fun parseFixture(json: String): Fixture = fixtureJson.decodeFromString(Fixture.serializer(), json)

private fun invalid(reason: String): Nothing = error("invalid fixture: $reason")

private fun validateFixture(fixture: Fixture) {
    val kind = fixture.setup.kind
    if (kind != "retry" && kind != "fallback") invalid("unknown setup kind: $kind")
    if (kind == "retry" && fixture.providers.isEmpty()) invalid("retry setup needs a provider")
    for (p in fixture.providers) {
        p.script.forEachIndexed { i, e ->
            if (e.response == null && e.error == null) {
                invalid("script entry ${i + 1} of provider ${p.id} has neither response nor error")
            }
        }
    }
}

/** Runs a contract fixture with a recording sleep (no real waiting). */
suspend fun runFixture(fixture: Fixture): FixtureOutcome {
    validateFixture(fixture)
    val events = mutableListOf<Event>()
    val sleeps = mutableListOf<Long>()
    val calls = LinkedHashMap<String, Int>()

    val providers: List<Provider> = fixture.providers.map { p ->
        calls[p.id] = 0
        val mock = MockProvider(p.id, p.script)
        object : Provider {
            override val id = p.id
            override suspend fun complete(req: CompletionRequest): CompletionResponse {
                calls[p.id] = (calls[p.id] ?: 0) + 1
                try {
                    val res = mock.complete(req)
                    events += Event.Call(p.id, "ok")
                    return res
                } catch (e: ProviderException) {
                    events += Event.Call(p.id, e.kind.wire)
                    throw e
                }
            }
        }
    }

    val sleep: suspend (Long) -> Unit = { ms ->
        sleeps += ms
        events += Event.Wait(ms)
    }

    val setup = fixture.setup
    val top: Provider = when (setup.kind) {
        "retry" -> RetryingProvider(
            providers[0],
            RetryOptions(setup.maxAttempts ?: 1, setup.baseDelayMs ?: 0, setup.maxDelayMs ?: 0, sleep),
        )
        "fallback" -> FallbackProvider(providers)
        else -> invalid("unknown setup kind: ${setup.kind}")
    }

    val result = try {
        val r = top.complete(fixture.request)
        FixtureResult.Success(r.text, r.provider)
    } catch (e: ProviderException) {
        FixtureResult.Failure(e.kind, e.retryAfterMs)
    }
    return FixtureOutcome(result, sleeps, calls, events)
}
