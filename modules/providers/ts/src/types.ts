export type Role = "system" | "user" | "assistant";
export type ErrorKind = "rate_limit" | "timeout" | "invalid_output" | "unavailable";

export interface Message {
  role: Role;
  content: string;
}

export interface CompletionRequest {
  messages: Message[];
  maxTokens?: number;
}

export interface CompletionResponse {
  text: string;
  provider: string;
}

export interface Provider {
  id: string;
  complete(req: CompletionRequest): Promise<CompletionResponse>;
}

export class ProviderError extends Error {
  readonly kind: ErrorKind;
  readonly retryAfterMs?: number;

  constructor(kind: ErrorKind, retryAfterMs?: number) {
    super(kind);
    this.name = "ProviderError";
    this.kind = kind;
    if (retryAfterMs !== undefined && retryAfterMs !== null) this.retryAfterMs = retryAfterMs;
  }
}
