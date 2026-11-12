import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { runFixture, type Fixture } from "./contract.js";

const dir = join(dirname(fileURLToPath(import.meta.url)), "../../../../contracts/providers");
const fixtures: Fixture[] = readdirSync(dir)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .map((f) => JSON.parse(readFileSync(join(dir, f), "utf8")) as Fixture);

describe("providers contract fixtures", () => {
  it("loads all fixtures", () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(13);
  });

  for (const fx of fixtures) {
    it(fx.name, async () => {
      const out = await runFixture(fx);
      const want = fx.expect.result;
      if ("error" in want) {
        expect("error" in out.result).toBe(true);
        const got = (out.result as { error: { kind: string; retryAfterMs?: number } }).error;
        expect(got.kind).toBe(want.error.kind);
        if (want.error.retryAfterMs !== undefined && want.error.retryAfterMs !== null) {
          expect(got.retryAfterMs).toBe(want.error.retryAfterMs);
        }
      } else {
        expect(out.result).toEqual(want);
      }
      expect(out.sleeps).toEqual(fx.expect.sleeps);
      expect(out.calls).toEqual(fx.expect.calls);
      const waits = out.events.flatMap((e) => (e.type === "wait" ? [e.ms] : []));
      expect(waits, `${fx.name}: wait events must equal sleeps`).toEqual(out.sleeps);
      if (fx.expect.events !== undefined) {
        expect(out.events, `${fx.name}: event trace`).toEqual(fx.expect.events);
      }
    });
  }
});
