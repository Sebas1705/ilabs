package es.sebas1705.ilabs.providers

import kotlinx.serialization.Serializable

@Serializable
data class ScriptError(val kind: ErrorKind, val retryAfterMs: Long? = null)

/** One scripted call: either a `response` text or an `error`. */
@Serializable
data class ScriptEntry(val response: String? = null, val error: ScriptError? = null)

/** Deterministic provider: call N consumes script[N-1]; afterwards fails with `unavailable`. */
class MockProvider(override val id: String, private val script: List<ScriptEntry>) : Provider {
    private var calls = 0

    override suspend fun complete(req: CompletionRequest): CompletionResponse {
        val entry = script.getOrNull(calls++) ?: throw ProviderException(ErrorKind.UNAVAILABLE)
        entry.error?.let { throw ProviderException(it.kind, it.retryAfterMs) }
        return CompletionResponse(entry.response ?: "", id)
    }
}
