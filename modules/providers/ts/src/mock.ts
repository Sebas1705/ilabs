import { ProviderError, type CompletionRequest, type CompletionResponse, type ErrorKind, type Provider } from "./types.js";

export type ScriptEntry =
  | { response: string; error?: undefined }
  | { error: { kind: ErrorKind; retryAfterMs?: number | null }; response?: undefined };

/** Deterministic provider: call N consumes script[N-1]; afterwards fails with `unavailable`. */
export class MockProvider implements Provider {
  private calls = 0;

  constructor(
    readonly id: string,
    private readonly script: ScriptEntry[],
  ) {}

  async complete(_req: CompletionRequest): Promise<CompletionResponse> {
    const entry = this.script[this.calls++];
    if (entry === undefined) throw new ProviderError("unavailable");
    if (entry.error) throw new ProviderError(entry.error.kind, entry.error.retryAfterMs ?? undefined);
    return { text: entry.response as string, provider: this.id };
  }
}
