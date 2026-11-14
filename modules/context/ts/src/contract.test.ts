import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { runFixture, type Fixture } from "./contract.js";

const dir = join(dirname(fileURLToPath(import.meta.url)), "../../../../contracts/context");
const fixtures: Fixture[] = readdirSync(dir)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .map((f) => JSON.parse(readFileSync(join(dir, f), "utf8")) as Fixture);

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

describe("context contract fixtures", () => {
  it("loads all fixtures", () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(48);
  });

  for (const fx of fixtures) {
    it(fx.name, async () => {
      const out = await runFixture(fx);
      expect(out.result).toEqual(fx.expect.result);
      if (fx.setup.kind === "summarize") {
        expect(fx.expect.calls, `${fx.name}: summarize fixtures must declare expect.calls`).toBeDefined();
        expect(out.calls).toBe(fx.expect.calls);
      } else {
        expect(out.calls, `${fx.name}: a window fixture never calls a provider`).toBe(0);
      }

      if (out.calls === 0) {
        expect(out.summarizerRequest).toBeNull();
        return;
      }
      // Harness rule: the recorded summarizer request has exactly system then user, both non-empty, no other fields.
      const req = out.summarizerRequest;
      expect(req, `${fx.name}: a call was made`).not.toBeNull();
      expect(Object.keys(req!)).toEqual(["messages"]);
      expect(req!.messages).toHaveLength(2);
      const [system, user] = req!.messages;
      expect(system!.role).toBe("system");
      expect(user!.role).toBe("user");
      expect(Object.keys(system!).sort()).toEqual(["content", "role"]);
      expect(Object.keys(user!).sort()).toEqual(["content", "role"]);
      expect(system!.content.length).toBeGreaterThan(0);
      expect(user!.content.length).toBeGreaterThan(0);

      const parts = fx.expect.transcriptContains ?? [];
      expect(containsInOrder(user!.content, parts), `${fx.name}: transcriptContains ${JSON.stringify(parts)}`).toBe(true);
      for (const banned of fx.expect.requestExcludes ?? []) {
        expect(system!.content.includes(banned), `${fx.name}: instruction contains ${JSON.stringify(banned)}`).toBe(false);
        expect(user!.content.includes(banned), `${fx.name}: transcript contains ${JSON.stringify(banned)}`).toBe(false);
      }
    });
  }
});

const keys = (o: unknown): string[] => Object.keys(o as object).sort();
const RESULT_KEYS = {
  window: ["droppedCount", "messages", "tokensAfter", "tokensBefore"],
  summarize: ["droppedCount", "messages", "summarized", "summaryCalls", "tokensAfter", "tokensBefore"],
};

describe("fixture shape", () => {
  for (const fx of fixtures) {
    it(`${fx.name} has only known keys and the fields its kind requires`, () => {
      const known = (actual: unknown, allowed: string[], where: string) => {
        const extra = keys(actual).filter((k) => !allowed.includes(k));
        expect(extra, `${fx.name}: unknown key(s) in ${where}`).toEqual([]);
      };
      known(fx, ["name", "setup", "messages", "provider", "expect"], "fixture");
      known(fx.setup, ["kind", "maxTokens", "keepRecent"], "setup");
      known(fx.expect, ["result", "calls", "transcriptContains", "requestExcludes"], "expect");

      const result = fx.expect.result as Record<string, unknown>;
      const error = result["error"] as Record<string, unknown> | undefined;
      if (error !== undefined) {
        expect(keys(result), `${fx.name}: an error result has only "error"`).toEqual(["error"]);
        known(error, ["kind", "required"], "result.error");
      } else {
        expect(keys(result), `${fx.name}: result keys`).toEqual(RESULT_KEYS[fx.setup.kind]);
      }

      if (fx.setup.kind === "summarize") {
        expect(fx.provider, `${fx.name}: summarize fixtures need a provider`).toBeDefined();
        expect(fx.expect.calls, `${fx.name}: summarize fixtures need expect.calls`).toBeDefined();
      } else {
        expect(fx.provider, `${fx.name}: window fixtures have no provider`).toBeUndefined();
        expect(fx.expect.calls, `${fx.name}: window fixtures have no calls`).toBeUndefined();
      }
    });
  }
});

describe("runFixture", () => {
  const base = fixtures.find((f) => f.name === "summarize-one-summary")!;

  it("rejects an unknown setup kind", async () => {
    const fx = { ...base, setup: { ...base.setup, kind: "bogus" } } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow("invalid fixture: unknown setup kind: bogus");
  });

  it("rejects a summarize setup without a provider", async () => {
    const { provider: _unused, ...rest } = base;
    await expect(runFixture(rest as Fixture)).rejects.toThrow("invalid fixture: summarize setup needs a provider");
  });

  it("rejects a script entry with neither response nor error", async () => {
    const fx = { ...base, provider: { id: "p", script: [{ response: "ok" }, {}] } } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow("invalid fixture: script entry 2 of provider p has neither response nor error");
  });

  it("rethrows exceptions that are not provider or budget errors unchanged", async () => {
    const fx = { ...base, messages: [{ role: "user", content: 5 }] } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toBeInstanceOf(TypeError);
  });
});
