import { MockProvider, ProviderError, type CompletionRequest, type Provider, type Role, type ScriptEntry } from "@ilabs/providers";
import { extractJson } from "./extract.js";
import { validate, type Issue, type Schema } from "./schema.js";
import { structured, StructuredError, type AttemptTrace } from "./structured.js";

export type FixtureResult = { value: unknown } | { error: { kind: string; issues?: Issue[] } };

export interface Fixture {
  name: string;
  schema: Schema;
  request: CompletionRequest;
  setup: { maxRepairs: number };
  provider: { id: string; script: ScriptEntry[] };
  expect: { result: FixtureResult; attempts: number; appended: Role[] };
}

export interface FixtureRun {
  result: FixtureResult;
  attempts: number;
  appended: Role[];
  trace: AttemptTrace[];
}

function invalid(reason: string): never {
  throw new Error(`invalid fixture: ${reason}`);
}

function validateFixture(fx: Fixture): void {
  if (typeof fx.setup?.maxRepairs !== "number") invalid("setup.maxRepairs must be a number");
  if (!Array.isArray(fx.request?.messages)) invalid("request.messages must be an array");
  if (typeof fx.provider?.id !== "string" || !Array.isArray(fx.provider.script)) invalid("provider needs id and script");
  fx.provider.script.forEach((e, i) => {
    if (e.response === undefined && e.error === undefined) invalid(`script entry ${i + 1} has neither response nor error`);
  });
}

function derive(reply: string, schema: Schema): AttemptTrace {
  const extracted = extractJson(reply);
  const issues: Issue[] = extracted.ok ? validate(schema, extracted.value) : [{ path: "", code: extracted.code }];
  return { reply, extracted, issues };
}

/** Runs a contract fixture against `structured`. Browser-safe. */
export async function runFixture(fixture: Fixture): Promise<FixtureRun> {
  validateFixture(fixture);
  const mock = new MockProvider(fixture.provider.id, fixture.provider.script);
  const original = fixture.request.messages.length;
  let attempts = 0;
  let last: Role[] = [];
  const replies: string[] = [];
  const provider: Provider = {
    id: mock.id,
    async complete(req) {
      attempts++;
      last = req.messages.slice(original).map((m) => m.role);
      const res = await mock.complete(req);
      replies.push(res.text);
      return res;
    },
  };

  try {
    const r = await structured(provider, fixture.request, fixture.schema, { maxRepairs: fixture.setup.maxRepairs });
    return { result: { value: r.value }, attempts, appended: last, trace: r.trace };
  } catch (e) {
    if (e instanceof StructuredError) {
      return { result: { error: { kind: e.kind, issues: e.issues } }, attempts, appended: last, trace: e.trace };
    }
    // A provider error propagates unchanged from structured(), so rebuild the trace from the replies received before it.
    if (e instanceof ProviderError) {
      return { result: { error: { kind: e.kind } }, attempts, appended: last, trace: replies.map((reply) => derive(reply, fixture.schema)) };
    }
    throw e;
  }
}
