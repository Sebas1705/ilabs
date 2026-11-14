import { MockProvider, type CompletionRequest, type Provider, type ScriptEntry } from "@ilabs/providers";
import { runSuite } from "./run.js";
import { InvalidSuiteError, parseSuite } from "./suite.js";
import type { RunOutput } from "./types.js";

export interface Fixture {
  name: string;
  /** Passed to `parseSuite` as is, so it can be malformed on purpose. */
  suite: unknown;
  targets: { id: string; script: ScriptEntry[] }[];
  judge?: { script: ScriptEntry[] };
  expect:
    | { invalidSuite: true }
    | {
        results: Array<{
          caseId: string;
          targetId: string;
          status: string;
          reply?: string;
          error?: string;
          graders?: Array<{ kind: string; passed: boolean; issues?: Array<{ path: string; code: string }>; error?: string }>;
        }>;
        summary: Array<{ targetId: string; total: number; passed: number; failed: number; errored: number; passRate: number }>;
        calls: Record<string, number>;
        judgeContains?: string[];
      };
}

export interface FixtureRun {
  invalidSuite: boolean;
  /** `null` when the suite was invalid. */
  output: RunOutput | null;
  /** Every request the judge received, in order (also those of failing calls). */
  judgeRequests: CompletionRequest[];
  /** Every request a target received, in call order. */
  targetRequests: Array<{ targetId: string; request: CompletionRequest }>;
  /** Calls the harness saw per provider (every target id, and `judge` when the fixture has one). */
  recordedCalls: Record<string, number>;
}

const TOP_LEVEL_KEYS = ["name", "suite", "targets", "judge", "expect"];

function invalid(reason: string): never {
  throw new Error(`invalid fixture: ${reason}`);
}

function checkScript(id: string, script: unknown): ScriptEntry[] {
  if (!Array.isArray(script)) invalid(`provider ${id} needs a script array`);
  (script as unknown[]).forEach((entry, i) => {
    const e = entry as { response?: unknown; error?: unknown } | null;
    if (typeof e !== "object" || e === null || (e.response === undefined && e.error === undefined)) {
      invalid(`script entry ${i + 1} of provider ${id} has neither response nor error`);
    }
  });
  return script as ScriptEntry[];
}

/** Runs a contract fixture through `parseSuite` and `runSuite`, recording what the providers received. Browser-safe. */
export async function runFixture(fixture: Fixture): Promise<FixtureRun> {
  for (const k of Object.keys(fixture)) {
    if (!TOP_LEVEL_KEYS.includes(k)) invalid(`unknown top-level key: ${k}`);
  }
  if (!Array.isArray(fixture.targets)) invalid("targets must be an array");
  for (const t of fixture.targets) {
    if (typeof t !== "object" || t === null || typeof t.id !== "string") invalid("every target needs a string id");
    checkScript(t.id, t.script);
  }
  if (fixture.judge !== undefined) {
    if (typeof fixture.judge !== "object" || fixture.judge === null) invalid("judge must be an object");
    checkScript("judge", fixture.judge.script);
  }
  const exp = fixture.expect as Record<string, unknown> | undefined;
  const expectsInvalid = typeof exp === "object" && exp !== null && exp["invalidSuite"] === true;
  if (typeof exp !== "object" || exp === null || !(expectsInvalid || ("results" in exp && "summary" in exp && "calls" in exp))) {
    invalid('expect must be {"invalidSuite": true} or have results, summary and calls');
  }

  const recordedCalls: Record<string, number> = {};
  const judgeRequests: CompletionRequest[] = [];
  const targetRequests: FixtureRun["targetRequests"] = [];
  const record = (id: string, inner: MockProvider, sink: (req: CompletionRequest) => void): Provider => {
    recordedCalls[id] = 0;
    return {
      id,
      async complete(req) {
        recordedCalls[id] = (recordedCalls[id] ?? 0) + 1;
        sink(req);
        return inner.complete(req);
      },
    };
  };
  const targets = fixture.targets.map((t) =>
    record(t.id, new MockProvider(t.id, t.script), (request) => targetRequests.push({ targetId: t.id, request })),
  );
  const judge = fixture.judge
    ? record("judge", new MockProvider("judge", fixture.judge.script), (req) => judgeRequests.push(req))
    : undefined;

  try {
    const suite = parseSuite(fixture.suite);
    const output = await runSuite(suite, judge ? { targets, judge } : { targets });
    return { invalidSuite: false, output, judgeRequests, targetRequests, recordedCalls };
  } catch (e) {
    if (e instanceof InvalidSuiteError) return { invalidSuite: true, output: null, judgeRequests, targetRequests, recordedCalls };
    throw e;
  }
}
