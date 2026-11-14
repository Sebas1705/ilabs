import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { runFixture, type Fixture } from "./contract.js";
import type { CaseResult, GraderResult, TargetSummary } from "./types.js";

const dir = join(dirname(fileURLToPath(import.meta.url)), "../../../../contracts/evals");
const files = readdirSync(dir)
  .filter((f) => f.endsWith(".json"))
  .sort();
const raw = new Map(files.map((f) => [f, readFileSync(join(dir, f), "utf8")]));
const fixtures: Fixture[] = files.map((f) => JSON.parse(raw.get(f)!) as Fixture);

/** True when every part appears in order as a non-overlapping substring (each search starts after the previous match). */
function containsInOrder(text: string, parts: string[]): boolean {
  let from = 0;
  for (const part of parts) {
    const at = text.indexOf(part, from);
    if (at < 0) return false;
    from = at + part.length;
  }
  return true;
}

const normalizeIssues = (g: { issues?: unknown[] }): unknown[] => g.issues ?? [];

describe("evals contract fixtures", () => {
  it("loads all fixtures", () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(162);
  });

  for (const fx of fixtures) {
    it(fx.name, async () => {
      const out = await runFixture(fx);
      const exp = fx.expect as Record<string, any>;

      if (exp["invalidSuite"] === true) {
        expect(out.invalidSuite, `${fx.name}: the suite must be rejected with the invalid suite prefix`).toBe(true);
        expect(out.output).toBeNull();
        const ids = [...fx.targets.map((t) => t.id), ...(fx.judge ? ["judge"] : [])];
        for (const id of ids) expect(out.recordedCalls[id], `${fx.name}: no call to ${id}`).toBe(0);
        expect(out.judgeRequests).toEqual([]);
        expect(out.targetRequests).toEqual([]);
        return;
      }

      expect(out.invalidSuite, `${fx.name}: the suite must be valid`).toBe(false);
      const output = out.output!;
      expect(output).not.toBeNull();

      // results
      const expResults = exp["results"] as Array<Record<string, any>>;
      expect(output.results).toHaveLength(expResults.length);
      expResults.forEach((er, i) => {
        const ar: CaseResult = output.results[i]!;
        const at = `${fx.name} result ${i}`;
        expect(ar.caseId, at).toBe(er["caseId"]);
        expect(ar.targetId, at).toBe(er["targetId"]);
        expect(ar.status, at).toBe(er["status"]);
        if ("reply" in er) expect(ar.reply, at).toBe(er["reply"]);
        if ("error" in er) {
          expect(ar.error, at).toBe(er["error"]);
          expect(Object.hasOwn(ar, "reply"), `${at}: an errored result has no reply`).toBe(false);
        } else {
          expect(Object.hasOwn(ar, "error"), `${at}: no unexpected error`).toBe(false);
        }
        if ("graders" in er) {
          const eg = er["graders"] as Array<Record<string, any>>;
          expect(ar.graders, at).toHaveLength(eg.length);
          eg.forEach((g, j) => {
            const ag: GraderResult = ar.graders[j]!;
            const gat = `${at} grader ${j}`;
            expect(ag.kind, gat).toBe(g["kind"]);
            expect(ag.passed, gat).toBe(g["passed"]);
            expect(ag.error, gat).toBe(g["error"]);
            expect(Object.hasOwn(ag, "error"), gat).toBe("error" in g);
            expect(normalizeIssues(ag), gat).toEqual(normalizeIssues(g));
          });
        }
      });

      // summary
      const expSummary = exp["summary"] as TargetSummary[];
      expect(output.summary).toHaveLength(expSummary.length);
      expSummary.forEach((es, i) => {
        const as = output.summary[i]!;
        expect(as.targetId).toBe(es.targetId);
        expect(as.total).toBe(es.total);
        expect(as.passed).toBe(es.passed);
        expect(as.failed).toBe(es.failed);
        expect(as.errored).toBe(es.errored);
        expect(Number.isInteger(as.total) && Number.isInteger(as.passed)).toBe(true);
        expect(as.passRate, `${fx.name}: passRate by double value`).toBe(es.passRate);
      });

      // calls: exact map equality, and what the harness recorded agrees
      expect(exp["calls"], `${fx.name}: fixtures must declare expect.calls`).toBeDefined();
      expect(output.calls).toEqual(exp["calls"]);
      expect(out.recordedCalls).toEqual(exp["calls"]);

      // target requests: exactly `messages`, equal to the case being run (case-major, then target-minor)
      const suiteCases = (fx.suite as { cases: Array<{ id: string; messages: unknown[] }> }).cases;
      const order = suiteCases.flatMap((c) => fx.targets.map((t) => ({ c, t })));
      expect(out.targetRequests).toHaveLength(order.length);
      order.forEach(({ c, t }, i) => {
        const rec = out.targetRequests[i]!;
        expect(rec.targetId, `${fx.name} request ${i}`).toBe(t.id);
        expect(Object.keys(rec.request), `${fx.name}: a target request has exactly messages`).toEqual(["messages"]);
        expect(rec.request.messages, `${fx.name}: messages of case ${c.id}`).toEqual(c.messages);
      });

      // judge requests: every one, 2 messages system then user, non-empty, no other fields
      expect(out.judgeRequests).toHaveLength(output.calls["judge"] ?? 0);
      for (const req of out.judgeRequests) {
        expect(Object.keys(req)).toEqual(["messages"]);
        expect(req.messages).toHaveLength(2);
        const [system, user] = req.messages;
        expect(system!.role).toBe("system");
        expect(user!.role).toBe("user");
        expect(Object.keys(system!).sort()).toEqual(["content", "role"]);
        expect(Object.keys(user!).sort()).toEqual(["content", "role"]);
        expect(system!.content.length).toBeGreaterThan(0);
        expect(user!.content.length).toBeGreaterThan(0);
      }

      // judgeContains: first judge request only
      const parts = exp["judgeContains"] as string[] | undefined;
      if (parts !== undefined) {
        expect(out.judgeRequests.length, `${fx.name}: judgeContains needs a judge call`).toBeGreaterThan(0);
        const user = out.judgeRequests[0]!.messages[1]!.content;
        expect(containsInOrder(user, parts), `${fx.name}: judgeContains ${JSON.stringify(parts)}`).toBe(true);
      }
    });
  }
});

const keys = (o: unknown): string[] => Object.keys(o as object).sort();
const LITERAL_NUMBER_FILES = ["length-integral-float-bound.json", "length-exponent-bound.json", "jsonequals-float-value.json"];

/** Contract rule for fixture files: `suite` is never a string and is an object, except in the two null/array fixtures. */
function suiteShapeProblem(name: string, suite: unknown): string | null {
  if (typeof suite === "string") return `${name}: a string-valued suite is not allowed`;
  const isObject = typeof suite === "object" && suite !== null && !Array.isArray(suite);
  if (!isObject && name !== "invalid-suite-null" && name !== "invalid-suite-array") return `${name}: suite is not an object`;
  return null;
}

describe("fixture files and shape", () => {
  it("rejects a string-valued or non-object suite in a fixture file", () => {
    expect(suiteShapeProblem("x", '{"cases": []}')).toBe("x: a string-valued suite is not allowed");
    expect(suiteShapeProblem("invalid-suite-null", "null")).toBe("invalid-suite-null: a string-valued suite is not allowed");
    expect(suiteShapeProblem("x", [])).toBe("x: suite is not an object");
    expect(suiteShapeProblem("x", null)).toBe("x: suite is not an object");
    expect(suiteShapeProblem("invalid-suite-null", null)).toBeNull();
    expect(suiteShapeProblem("invalid-suite-array", [])).toBeNull();
    expect(suiteShapeProblem("x", { cases: [] })).toBeNull();
  });

  const known = (name: string, actual: unknown, allowed: string[], where: string) => {
    expect(typeof actual === "object" && actual !== null && !Array.isArray(actual), `${name}: ${where} is an object`).toBe(true);
    const extra = keys(actual).filter((k) => !allowed.includes(k));
    expect(extra, `${name}: unknown key(s) in ${where}`).toEqual([]);
  };

  for (const file of files) {
    const fx = JSON.parse(raw.get(file)!) as Record<string, any>;
    it(`${fx["name"]} is well formed`, () => {
      const name = fx["name"] as string;
      expect(`${name}.json`).toBe(file);
      if (!LITERAL_NUMBER_FILES.includes(file)) {
        // A Windows checkout may convert LF to CRLF (core.autocrlf); the committed bytes are what the contract pins.
        expect(raw.get(file)!.replaceAll("\r\n", "\n"),`${name}: exactly JSON.stringify(x, null, 2) + LF`).toBe(JSON.stringify(fx, null, 2) + "\n");
      }
      known(name, fx, ["name", "suite", "targets", "judge", "expect"], "fixture");
      expect(suiteShapeProblem(name, fx["suite"])).toBeNull();
      expect(Array.isArray(fx["targets"])).toBe(true);
      for (const t of fx["targets"]) {
        known(name, t, ["id", "script"], "target");
        expect(typeof t.id).toBe("string");
        for (const entry of t.script) {
          expect(["response", "error"].filter((k) => k in entry)).toHaveLength(1);
          known(name, entry, ["response", "error"], "target script entry");
          if ("error" in entry) known(name, entry.error, ["kind"], "script error");
        }
      }
      if ("judge" in fx) {
        known(name, fx["judge"], ["script"], "judge");
        for (const entry of fx["judge"].script) {
          expect(["response", "error"].filter((k) => k in entry)).toHaveLength(1);
          known(name, entry, ["response", "error"], "judge script entry");
          if ("error" in entry) known(name, entry.error, ["kind"], "script error");
        }
      }

      const exp = fx["expect"];
      if (exp["invalidSuite"] === true) {
        known(name, exp, ["invalidSuite"], "expect");
        return;
      }
      known(name, exp, ["results", "summary", "calls", "judgeContains"], "expect");
      expect(exp["calls"], `${name}: calls are declared`).toBeDefined();
      for (const r of exp["results"]) {
        known(name, r, ["caseId", "targetId", "status", "reply", "error", "graders"], "result");
        for (const g of r["graders"] ?? []) {
          known(name, g, ["kind", "passed", "issues", "error"], "grader entry");
          for (const issue of g["issues"] ?? []) known(name, issue, ["path", "code"], "issue");
        }
      }
      for (const s of exp["summary"]) known(name, s, ["targetId", "total", "passed", "failed", "errored", "passRate"], "summary entry");
    });
  }
});

describe("runFixture", () => {
  const base = fixtures.find((f) => f.name === "status-all-pass")!;

  it("rejects an unknown top-level key", async () => {
    const fx = { ...base, extra: 1 } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow(/^invalid fixture: /);
  });

  it("rejects a target without a string id", async () => {
    const fx = { ...base, targets: [{ id: 5, script: [] }] } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow(/^invalid fixture: /);
  });

  it("rejects a target without an array script", async () => {
    const fx = { ...base, targets: [{ id: "a" }] } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow(/^invalid fixture: /);
  });

  it("rejects a script entry with neither response nor error", async () => {
    const fx = { ...base, targets: [{ id: "a", script: [{ response: "ok" }, {}] }] } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow("invalid fixture: script entry 2 of provider a has neither response nor error");
  });

  it("rejects a judge script entry with neither response nor error", async () => {
    const fx = { ...base, judge: { script: [{}] } } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow("invalid fixture: script entry 1 of provider judge has neither response nor error");
  });

  it("rejects an expect that is neither invalidSuite nor results, summary and calls", async () => {
    const fx = { ...base, expect: { results: [], summary: [] } } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow(/^invalid fixture: /);
    const fx2 = { ...base, expect: { invalidSuite: false } } as unknown as Fixture;
    await expect(runFixture(fx2)).rejects.toThrow(/^invalid fixture: /);
  });

  it("does not treat a malformed suite as a malformed fixture", async () => {
    const fx = { ...base, suite: { cases: [] }, expect: { invalidSuite: true } } as unknown as Fixture;
    const out = await runFixture(fx);
    expect(out.invalidSuite).toBe(true);
    expect(out.output).toBeNull();
  });

  it("records the requests of a valid run", async () => {
    const out = await runFixture(base);
    expect(out.invalidSuite).toBe(false);
    expect(out.recordedCalls).toEqual({ a: 1 });
    expect(out.targetRequests).toHaveLength(1);
  });
});
