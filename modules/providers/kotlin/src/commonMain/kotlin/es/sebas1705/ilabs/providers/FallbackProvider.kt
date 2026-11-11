package es.sebas1705.ilabs.providers

/** Tries providers in order; `invalid_output` stops the chain immediately. */
class FallbackProvider(private val providers: List<Provider>) : Provider {
    override val id: String = "fallback"

    override suspend fun complete(req: CompletionRequest): CompletionResponse {
        var last = ProviderException(ErrorKind.UNAVAILABLE)
        for (p in providers) {
            try {
                return p.complete(req)
            } catch (e: ProviderException) {
                if (e.kind == ErrorKind.INVALID_OUTPUT) throw e
                last = e
            }
        }
        throw last
    }
}
