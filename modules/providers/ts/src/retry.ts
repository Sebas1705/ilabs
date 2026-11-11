import { ProviderError, type CompletionRequest, type CompletionResponse, type Provider } from "./types.js";

export interface RetryOptions {
  maxAttempts: number;
  baseDelayMs: number;
  maxDelayMs: number;
  sleep: (ms: number) => Promise<void>;
}

/** Retries `rate_limit` and `timeout` with exponential backoff; sleeps only between attempts. */
export class RetryingProvider implements Provider {
  constructor(
    private readonly inner: Provider,
    private readonly opts: RetryOptions,
  ) {}

  get id(): string {
    return this.inner.id;
  }

  async complete(req: CompletionRequest): Promise<CompletionResponse> {
    const { maxAttempts, baseDelayMs, maxDelayMs, sleep } = this.opts;
    for (let attempt = 1; ; attempt++) {
      try {
        return await this.inner.complete(req);
      } catch (e) {
        const retryable = e instanceof ProviderError && (e.kind === "rate_limit" || e.kind === "timeout");
        if (!retryable || attempt >= maxAttempts) throw e;
        const raw = e.retryAfterMs !== undefined && e.retryAfterMs !== null ? e.retryAfterMs : baseDelayMs * 2 ** (attempt - 1);
        await sleep(Math.min(Math.max(raw, 0), maxDelayMs));
      }
    }
  }
}
