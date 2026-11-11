import { FallbackProvider } from "./fallback.js";
import { MockProvider, type ScriptEntry } from "./mock.js";
import { RetryingProvider } from "./retry.js";
import {
  ProviderError,
  type CompletionRequest,
  type CompletionResponse,
  type ErrorKind,
  type Provider,
} from "./types.js";

export type FixtureOutcome = CompletionResponse | { error: { kind: ErrorKind; retryAfterMs?: number | null } };

export interface Fixture {
  name: string;
  setup: { kind: "retry" | "fallback"; maxAttempts?: number; baseDelayMs?: number; maxDelayMs?: number };
  providers: { id: string; script: ScriptEntry[] }[];
  request: CompletionRequest;
  expect: { result: FixtureOutcome; sleeps: number[]; calls: Record<string, number> };
}

export type FixtureEvent =
  | { type: "call"; provider: string; outcome: "ok" | ErrorKind }
  | { type: "wait"; ms: number };

export interface FixtureRun {
  result: FixtureOutcome;
  sleeps: number[];
  calls: Record<string, number>;
  events: FixtureEvent[];
}

function invalid(reason: string): never {
  throw new Error(`invalid fixture: ${reason}`);
}

function validateFixture(fixture: Fixture): void {
  const kind: string = fixture.setup.kind;
  if (kind !== "retry" && kind !== "fallback") invalid(`unknown setup kind: ${kind}`);
  if (kind === "retry" && fixture.providers.length === 0) invalid("retry setup needs a provider");
  for (const p of fixture.providers) {
    p.script.forEach((e, i) => {
      if (e.response === undefined && e.error === undefined) {
        invalid(`script entry ${i + 1} of provider ${p.id} has neither response nor error`);
      }
    });
  }
}

/** Runs a contract fixture with a recording sleep (no real waiting). Browser-safe. */
export async function runFixture(fixture: Fixture): Promise<FixtureRun> {
  validateFixture(fixture);
  const events: FixtureEvent[] = [];
  const sleeps: number[] = [];
  const calls: Record<string, number> = {};

  const providers: Provider[] = fixture.providers.map((p) => {
    calls[p.id] = 0;
    const mock = new MockProvider(p.id, p.script);
    return {
      id: p.id,
      async complete(req) {
        calls[p.id] = (calls[p.id] ?? 0) + 1;
        try {
          const res = await mock.complete(req);
          events.push({ type: "call", provider: p.id, outcome: "ok" });
          return res;
        } catch (e) {
          if (e instanceof ProviderError) events.push({ type: "call", provider: p.id, outcome: e.kind });
          throw e;
        }
      },
    };
  });

  const sleep = async (ms: number) => {
    sleeps.push(ms);
    events.push({ type: "wait", ms });
  };

  const { setup } = fixture;
  const top: Provider =
    setup.kind === "retry"
      ? new RetryingProvider(providers[0] as Provider, {
          maxAttempts: setup.maxAttempts ?? 1,
          baseDelayMs: setup.baseDelayMs ?? 0,
          maxDelayMs: setup.maxDelayMs ?? 0,
          sleep,
        })
      : new FallbackProvider(providers);

  let result: FixtureOutcome;
  try {
    result = await top.complete(fixture.request);
  } catch (e) {
    if (!(e instanceof ProviderError)) throw e;
    result = { error: e.retryAfterMs !== undefined ? { kind: e.kind, retryAfterMs: e.retryAfterMs } : { kind: e.kind } };
  }
  return { result, sleeps, calls, events };
}
