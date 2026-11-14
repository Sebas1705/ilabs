import { MockProvider, ProviderError, type CompletionRequest, type Message, type Provider, type ScriptEntry } from "@ilabs/providers";
import { summarizeContext } from "./summarize.js";
import { BudgetTooSmallError, slidingWindow } from "./window.js";

export type FixtureResult =
  | { messages: Message[]; droppedCount: number; tokensBefore: number; tokensAfter: number; summarized?: boolean; summaryCalls?: number }
  | { error: { kind: string; required?: number } };

export interface Fixture {
  name: string;
  setup: { kind: "window" | "summarize"; maxTokens: number; keepRecent?: number };
  messages: Message[];
  provider?: { id: string; script: ScriptEntry[] };
  expect: {
    result: FixtureResult;
    calls?: number;
    transcriptContains?: string[];
    requestExcludes?: string[];
  };
}

export interface FixtureRun {
  result: FixtureResult;
  calls: number;
  summarizerRequest: CompletionRequest | null;
}

function invalid(reason: string): never {
  throw new Error(`invalid fixture: ${reason}`);
}

/** Runs a contract fixture against `slidingWindow` / `summarizeContext`. Browser-safe. */
export async function runFixture(fixture: Fixture): Promise<FixtureRun> {
  const kind: string = fixture.setup?.kind;
  if (kind !== "window" && kind !== "summarize") invalid(`unknown setup kind: ${kind}`);
  if (kind === "summarize" && fixture.provider === undefined) invalid("summarize setup needs a provider");
  fixture.provider?.script.forEach((entry, i) => {
    if (entry.response === undefined && entry.error === undefined) {
      invalid(`script entry ${i + 1} of provider ${fixture.provider!.id} has neither response nor error`);
    }
  });

  const requests: CompletionRequest[] = [];
  const mock = fixture.provider ? new MockProvider(fixture.provider.id, fixture.provider.script) : undefined;
  const provider: Provider = {
    id: mock?.id ?? "none",
    async complete(req) {
      requests.push(req);
      if (mock === undefined) throw new ProviderError("unavailable");
      return mock.complete(req);
    },
  };
  const finish = (result: FixtureResult): FixtureRun => ({ result, calls: requests.length, summarizerRequest: requests[0] ?? null });

  try {
    const { maxTokens, keepRecent } = fixture.setup;
    if (kind === "window") {
      const r = slidingWindow(fixture.messages, { maxTokens });
      return finish({ messages: r.messages, droppedCount: r.droppedCount, tokensBefore: r.tokensBefore, tokensAfter: r.tokensAfter });
    }
    const r = await summarizeContext(provider, fixture.messages, keepRecent === undefined ? { maxTokens } : { maxTokens, keepRecent });
    return finish({
      messages: r.messages,
      droppedCount: r.droppedCount,
      tokensBefore: r.tokensBefore,
      tokensAfter: r.tokensAfter,
      summarized: r.summarized,
      summaryCalls: r.summaryCalls,
    });
  } catch (e) {
    if (e instanceof BudgetTooSmallError) return finish({ error: { kind: e.kind, required: e.required } });
    if (e instanceof ProviderError) return finish({ error: { kind: e.kind } });
    throw e;
  }
}
