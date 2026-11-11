package es.sebas1705.ilabs.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

@Serializable
enum class ErrorKind(val wire: String) {
    @SerialName("rate_limit") RATE_LIMIT("rate_limit"),
    @SerialName("timeout") TIMEOUT("timeout"),
    @SerialName("invalid_output") INVALID_OUTPUT("invalid_output"),
    @SerialName("unavailable") UNAVAILABLE("unavailable"),
}

@Serializable
data class Message(val role: Role, val content: String)

@Serializable
data class CompletionRequest(val messages: List<Message>, val maxTokens: Int? = null)

@Serializable
data class CompletionResponse(val text: String, val provider: String)

interface Provider {
    val id: String
    suspend fun complete(req: CompletionRequest): CompletionResponse
}

/** Failure reported by a provider. `retryAfterMs == null` means absent; 0 or negative values count as present. */
class ProviderException(val kind: ErrorKind, val retryAfterMs: Long? = null) : Exception(kind.wire)
