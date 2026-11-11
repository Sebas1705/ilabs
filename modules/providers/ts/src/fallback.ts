import { ProviderError, type CompletionRequest, type CompletionResponse, type Provider } from "./types.js";

/** Tries providers in order; `invalid_output` stops the chain immediately. */
export class FallbackProvider implements Provider {
  readonly id = "fallback";

  constructor(private readonly providers: Provider[]) {}

  async complete(req: CompletionRequest): Promise<CompletionResponse> {
    let last: ProviderError = new ProviderError("unavailable");
    for (const p of this.providers) {
      try {
        return await p.complete(req);
      } catch (e) {
        if (!(e instanceof ProviderError) || e.kind === "invalid_output") throw e;
        last = e;
      }
    }
    throw last;
  }
}
