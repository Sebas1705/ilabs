import { MockProvider, ProviderError, type CompletionRequest, type Provider } from "@ilabs/providers";
import { describe, expect, it } from "vitest";
import { runSuite } from "./run.js";
import { parseSuite } from "./suite.js";
import { summarize } from "./summary.js";
import type { CaseResult, Suite } from "./types.js";

const suite = (...graders: Suite["cases"][number]["graders"]): Suite => ({
  cases: [{ id: "c1", messages: [{ role: "user", content: "Q" }], graders }],
});
const bad = (p: Promise<unknown>) => expect(p).rejects.toThrow(/^invalid suite: /);

describe("runSuite", () => {
  it("runs case-major then target-minor and counts every call", async () => {
    const s: Suite = {
      cases: [
        { id: "c1", messages: [{ role: "user", content: "1" }], graders: [{ kind: "contains", value: "x" }] },
        { id: "c2", messages: [{ role: "user", content: "2" }], graders: [{ kind: "contains", value: "x" }] },
      ],
    };
    const a = new MockProvider("a", [{ response: "x" }, { response: "y" }]);
    const b = new MockProvider("b", [{ error: { kind: "timeout" } }, { response: "x" }]);
    const out = await runSuite(s, { targets: [a, b] });
    expect(out.results.map((r) => `${r.caseId}/${r.targetId}/${r.status}`)).toEqual(["c1/a/pass", "c1/b/error", "c2/a/fail", "c2/b/pass"]);
    expect(out.results[1]).toEqual({ caseId: "c1", targetId: "b", status: "error", error: "timeout", graders: [] });
    expect(out.calls).toEqual({ a: 2, b: 2 });
    expect(out.summary).toEqual([
      { targetId: "a", total: 2, passed: 1, failed: 1, errored: 0, passRate: 0.5 },
      { targetId: "b", total: 2, passed: 1, failed: 0, errored: 1, passRate: 0.5 },
    ]);
  });

  it("sends exactly the case messages to a target", async () => {
    const seen: CompletionRequest[] = [];
    const p: Provider = {
      id: "a",
      async complete(req) {
        seen.push(req);
        return { text: "ok", provider: "a" };
      },
    };
    await runSuite(suite({ kind: "contains", value: "o" }), { targets: [p] });
    expect(seen).toEqual([{ messages: [{ role: "user", content: "Q" }] }]);
  });

  it("lists every target in calls and the judge key only when a judge is supplied", async () => {
    const a = new MockProvider("a", []);
    const withJudge = await runSuite(suite({ kind: "contains", value: "x" }), { targets: [a], judge: new MockProvider("whatever", []) });
    expect(withJudge.calls).toEqual({ a: 1, judge: 0 });
    const without = await runSuite(suite({ kind: "contains", value: "x" }), { targets: [new MockProvider("a", [])] });
    expect(Object.keys(without.calls)).toEqual(["a"]);
    const none = await runSuite(suite({ kind: "contains", value: "x" }), { targets: [] });
    expect(none).toEqual({ results: [], summary: [], calls: {} });
  });

  it("a provider error skips the graders and the judge call, and the run continues", async () => {
    const judge = new MockProvider("judge", [{ response: "PASS" }]);
    const a = new MockProvider("a", [{ error: { kind: "rate_limit" } }, { response: "ok" }]);
    const s: Suite = {
      cases: [
        { id: "c1", messages: [], graders: [{ kind: "judge", rubric: "r" }] },
        { id: "c2", messages: [], graders: [{ kind: "judge", rubric: "r" }] },
      ],
    };
    const out = await runSuite(s, { targets: [a], judge });
    expect(out.calls).toEqual({ a: 2, judge: 1 });
    expect(out.results[0]).toEqual({ caseId: "c1", targetId: "a", status: "error", error: "rate_limit", graders: [] });
    expect(out.results[1]!.status).toBe("pass");
  });

  it("judge: no_judge, bad_verdict and provider errors are grader errors; every call is counted", async () => {
    const s = suite({ kind: "judge", rubric: "r" }, { kind: "judge", rubric: "r" }, { kind: "judge", rubric: "r" }, { kind: "contains", value: "ok" });
    const judge = new MockProvider("judge", [{ response: "maybe" }, { error: { kind: "timeout" } }]);
    const out = await runSuite(s, { targets: [new MockProvider("a", [{ response: "ok" }])], judge });
    expect(out.calls).toEqual({ a: 1, judge: 3 });
    expect(out.results[0]!.graders).toEqual([
      { kind: "judge", passed: false, error: "bad_verdict" },
      { kind: "judge", passed: false, error: "timeout" },
      { kind: "judge", passed: false, error: "unavailable" },
      { kind: "contains", passed: true },
    ]);
    expect(out.results[0]!.status).toBe("error");

    const noJudge = await runSuite(suite({ kind: "judge", rubric: "r" }), { targets: [new MockProvider("a", [{ response: "ok" }])] });
    expect(noJudge.results[0]!.graders).toEqual([{ kind: "judge", passed: false, error: "no_judge" }]);
    expect(noJudge.calls).toEqual({ a: 1 });
  });

  it("sends the judge the empty string when the case has no user message", async () => {
    const seen: CompletionRequest[] = [];
    const judge: Provider = {
      id: "j",
      async complete(req) {
        seen.push(req);
        return { text: "PASS", provider: "j" };
      },
    };
    const s: Suite = { cases: [{ id: "c1", messages: [{ role: "system", content: "SYS-ONLY" }], graders: [{ kind: "judge", rubric: "RUBRIC" }] }] };
    const out = await runSuite(s, { targets: [new MockProvider("a", [{ response: "REPLY" }])], judge });
    expect(out.results[0]!.status).toBe("pass");
    expect(seen).toHaveLength(1);
    expect(seen[0]!.messages).toHaveLength(2);
    expect(seen[0]!.messages[1]!.content).not.toContain("SYS-ONLY");
    expect(seen[0]!.messages[1]!.content).toContain("RUBRIC");
    expect(seen[0]!.messages[1]!.content).toContain("REPLY");
  });

  it("aborts on a target exception that is not a provider error and rethrows it unchanged", async () => {
    const boom = new TypeError("boom");
    let later = 0;
    const throwing: Provider = {
      id: "a",
      async complete() {
        throw boom;
      },
    };
    const after: Provider = {
      id: "b",
      async complete() {
        later++;
        return { text: "x", provider: "b" };
      },
    };
    await expect(runSuite(suite({ kind: "contains", value: "x" }), { targets: [throwing, after] })).rejects.toBe(boom);
    expect(later).toBe(0);
  });

  it("aborts on a judge exception that is not a provider error", async () => {
    const boom = new RangeError("judge boom");
    const judge: Provider = {
      id: "judge",
      async complete() {
        throw boom;
      },
    };
    await expect(runSuite(suite({ kind: "judge", rubric: "r" }), { targets: [new MockProvider("a", [{ response: "x" }])], judge })).rejects.toBe(boom);
  });

  it("treats a ProviderError from a custom provider as an error result", async () => {
    const p: Provider = {
      id: "a",
      async complete() {
        throw new ProviderError("invalid_output");
      },
    };
    const out = await runSuite(suite({ kind: "contains", value: "x" }), { targets: [p] });
    expect(out.results[0]).toEqual({ caseId: "c1", targetId: "a", status: "error", error: "invalid_output", graders: [] });
  });

  it("validates the targets before any call", async () => {
    const calls: string[] = [];
    const mk = (id: string): Provider => ({
      id,
      async complete() {
        calls.push(id);
        return { text: "x", provider: id };
      },
    });
    const s = suite({ kind: "contains", value: "x" });
    await bad(runSuite(s, { targets: [mk("a"), mk("a")] }));
    await bad(runSuite(s, { targets: [mk("a"), mk("")] }));
    await bad(runSuite(s, { targets: [mk("a"), mk("judge")] }));
    await bad(runSuite(s, { targets: [mk("a"), mk("judge")], judge: mk("judge") }));
    expect(calls).toEqual([]);
  });

  it("validates a hand-built suite, with zero calls, and ignores extra properties", async () => {
    const calls: string[] = [];
    const p: Provider = {
      id: "a",
      async complete() {
        calls.push("a");
        return { text: "x", provider: "a" };
      },
    };
    const first = { id: "c1", messages: [], graders: [{ kind: "contains", value: "x" }] };
    await bad(runSuite({ cases: [] } as Suite, { targets: [p] }));
    await bad(runSuite({ cases: [first, { id: "c1", messages: [], graders: [{ kind: "contains", value: "x" }] }] } as Suite, { targets: [p] }));
    await bad(runSuite({ cases: [first, { id: "c2", messages: [], graders: [{ kind: "length" }] }] } as unknown as Suite, { targets: [p] }));
    await bad(runSuite({ cases: [first, { id: "c2", messages: [], graders: [{ kind: "nope" }] }] } as unknown as Suite, { targets: [p] }));
    expect(calls).toEqual([]);

    const extra = {
      extra: 1,
      cases: [{ id: "c1", extra: 2, messages: [{ role: "user", content: "q", extra: 3 }], graders: [{ kind: "length", min: 1, trim: true, extra: 4 }] }],
    } as unknown as Suite;
    const out = await runSuite(extra, { targets: [p] });
    expect(out.results[0]!.status).toBe("pass");
    expect(out.results[0]!.graders).toEqual([{ kind: "length", passed: true }]);
  });

  it("does not mutate its inputs", async () => {
    const s: Suite = { cases: [{ id: "c1", messages: [{ role: "user", content: "Q" }], graders: [{ kind: "oneOf", values: ["x"], trim: true }, { kind: "judge", rubric: "r" }] }] };
    const copy = structuredClone(s);
    const targets = [new MockProvider("a", [{ response: "x" }])];
    const opts = { targets, judge: new MockProvider("judge", [{ response: "PASS" }]) };
    await runSuite(s, opts);
    expect(s).toEqual(copy);
    expect(opts.targets).toBe(targets);
    expect(targets).toHaveLength(1);
  });

  it("runs a suite produced by parseSuite", async () => {
    const parsed = parseSuite('{"cases":[{"id":"c1","messages":[],"graders":[{"kind":"equals","value":"hi"}]}]}');
    const out = await runSuite(parsed, { targets: [new MockProvider("a", [{ response: "hi" }])] });
    expect(out.results[0]!.status).toBe("pass");
  });
});

describe("summarize", () => {
  const r = (targetId: string, status: CaseResult["status"], caseId = "c"): CaseResult => ({ caseId, targetId, status, graders: [] });

  it("counts per status in order of first appearance, for results in arbitrary order", () => {
    const results = [r("z", "fail"), r("a", "pass"), r("z", "pass"), r("a", "error"), r("m", "pass"), r("z", "pass"), r("a", "pass")];
    expect(summarize(results)).toEqual([
      { targetId: "z", total: 3, passed: 2, failed: 1, errored: 0, passRate: 2 / 3 },
      { targetId: "a", total: 3, passed: 2, failed: 0, errored: 1, passRate: 2 / 3 },
      { targetId: "m", total: 1, passed: 1, failed: 0, errored: 0, passRate: 1 },
    ]);
  });

  it("returns an empty summary for no results and does not mutate", () => {
    expect(summarize([])).toEqual([]);
    const results = [r("a", "pass")];
    const copy = structuredClone(results);
    summarize(results);
    expect(results).toEqual(copy);
  });

  it("uses exact doubles for the pass rate", () => {
    expect(summarize([r("a", "pass"), r("a", "fail"), r("a", "error")])[0]!.passRate).toBe(0.3333333333333333);
    expect(summarize([r("a", "fail")])[0]!.passRate).toBe(0);
  });
});
