package es.sebas1705.ilabs.providers

class RetryOptions(
    val maxAttempts: Int,
    val baseDelayMs: Long,
    val maxDelayMs: Long,
    val sleep: suspend (Long) -> Unit,
)

/** Retries `rate_limit` and `timeout` with exponential backoff; sleeps only between attempts. */
class RetryingProvider(private val inner: Provider, private val opts: RetryOptions) : Provider {
    init {
        require(opts.maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(opts.baseDelayMs >= 0) { "baseDelayMs must be >= 0" }
        require(opts.maxDelayMs >= 0) { "maxDelayMs must be >= 0" }
    }

    override val id: String get() = inner.id

    override suspend fun complete(req: CompletionRequest): CompletionResponse {
        var attempt = 1
        while (true) {
            try {
                return inner.complete(req)
            } catch (e: ProviderException) {
                val retryable = e.kind == ErrorKind.RATE_LIMIT || e.kind == ErrorKind.TIMEOUT
                if (!retryable || attempt >= opts.maxAttempts) throw e
                val raw = e.retryAfterMs ?: backoff(attempt)
                opts.sleep(raw.coerceAtLeast(0L).coerceAtMost(opts.maxDelayMs))
            }
            attempt++
        }
    }

    /** `baseDelayMs * 2^(attempt-1)`, saturating instead of overflowing. */
    private fun backoff(attempt: Int): Long {
        var d = opts.baseDelayMs
        repeat(attempt - 1) {
            d = if (d > Long.MAX_VALUE / 2) Long.MAX_VALUE else d * 2
        }
        return d
    }
}
