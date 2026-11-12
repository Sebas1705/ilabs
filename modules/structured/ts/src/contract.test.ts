import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { runFixture, type Fixture } from "./contract.js";
import { MockProvider } from "@ilabs/providers";
import { extractJson } from "./extract.js";
import { jsonEqual, validate } from "./schema.js";
import { structured, StructuredError } from "./structured.js";

const dir = join(dirname(fileURLToPath(import.meta.url)), "../../../../contracts/structured");
const fixtures: Fixture[] = readdirSync(dir)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .map((f) => JSON.parse(readFileSync(join(dir, f), "utf8")) as Fixture);

describe("structured contract fixtures", () => {
  it("loads all fixtures", () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(70);
  });

  for (const fx of fixtures) {
    it(fx.name, async () => {
      const out = await runFixture(fx);
      const want = fx.expect.result;
      if ("error" in want) {
        expect("error" in out.result, `${fx.name}: expected an error`).toBe(true);
        const got = (out.result as { error: { kind: string; issues?: unknown[] } }).error;
        expect(got.kind).toBe(want.error.kind);
        if (want.error.issues !== undefined) expect(got.issues).toEqual(want.error.issues);
      } else {
        expect("value" in out.result, `${fx.name}: expected a value`).toBe(true);
        const got = (out.result as { value: unknown }).value;
        expect(jsonEqual(got, want.value), `${fx.name}: ${JSON.stringify(got)} vs ${JSON.stringify(want.value)}`).toBe(true);
      }
      expect(out.attempts).toBe(fx.expect.attempts);
      expect(out.appended).toEqual(fx.expect.appended);
    });
  }
});

describe("runFixture trace when the provider fails", () => {
  const byName = (n: string) => fixtures.find((f) => f.name === n)!;

  it("keeps the trace of the replies received before a provider error (loop-provider-error-mid)", async () => {
    const fx = byName("loop-provider-error-mid");
    const out = await runFixture(fx);
    expect(out.attempts).toBe(2);
    expect(out.trace).toHaveLength(1);
    const reply = fx.provider.script[0]!.response!;
    const extracted = extractJson(reply);
    expect(extracted.ok).toBe(true);
    const want = extracted.ok ? validate(fx.schema, extracted.value) : [];
    expect(want.length).toBeGreaterThan(0);
    expect(out.trace[0]).toEqual({ reply, extracted, issues: want });
  });

  it("derives a no_json issue for an unparseable reply (loop-script-exhausted)", async () => {
    const out = await runFixture(byName("loop-script-exhausted"));
    expect(out.attempts).toBe(2);
    expect(out.trace).toHaveLength(1);
    expect(out.trace[0]!.reply).toBe("no json here");
    expect(out.trace[0]!.extracted).toEqual({ ok: false, code: "no_json" });
    expect(out.trace[0]!.issues).toEqual([{ path: "", code: "no_json" }]);
  });

  it("has an empty trace when the first call fails (loop-provider-error-first)", async () => {
    const out = await runFixture(byName("loop-provider-error-first"));
    expect(out.attempts).toBe(1);
    expect(out.trace).toEqual([]);
  });

  it("leaves a normal run's trace equal to structured()'s own (loop-repair-second)", async () => {
    const fx = byName("loop-repair-second");
    const out = await runFixture(fx);
    const own = await structured(new MockProvider(fx.provider.id, fx.provider.script), fx.request, fx.schema, {
      maxRepairs: fx.setup.maxRepairs,
    });
    expect(out.trace).toEqual(own.trace);
    expect(out.trace).toHaveLength(2);
  });

  it("trace.length equals attempts for every success or invalid_output-with-issues fixture", async () => {
    let checked = 0;
    for (const fx of fixtures) {
      const want = fx.expect.result;
      const structuredFailure = "error" in want && want.error.issues !== undefined && want.error.issues.length > 0;
      if ("error" in want && !structuredFailure) continue;
      const out = await runFixture(fx);
      expect(out.trace.length, fx.name).toBe(out.attempts);
      checked++;
    }
    expect(checked).toBeGreaterThan(50);
  });

  it("still rethrows non-provider errors unchanged", async () => {
    const fx = { ...byName("loop-first-try"), schema: { type: "bogus" } as never };
    await expect(runFixture(fx)).rejects.not.toBeInstanceOf(StructuredError);
  });
});
