import { describe, expect, it } from "vitest";
import { FallbackProvider, MockProvider, RetryingProvider, runFixture, type Fixture } from "./index.js";

const empty = { messages: [] };
const ok = new MockProvider("a", [{ response: "ok" }]);
const sleep = async () => {};

describe("non-provider exceptions", () => {
  const boom = new Error("boom");
  const bad = {
    id: "bad",
    complete(): Promise<never> {
      return Promise.reject(boom);
    },
  };

  it("are rethrown unchanged by RetryingProvider", async () => {
    const retrying = new RetryingProvider(bad, { maxAttempts: 3, baseDelayMs: 1, maxDelayMs: 1, sleep });
    await expect(retrying.complete(empty)).rejects.toBe(boom);
  });

  it("are rethrown unchanged by FallbackProvider", async () => {
    await expect(new FallbackProvider([bad, ok]).complete(empty)).rejects.toBe(boom);
  });
});

describe("RetryingProvider option validation", () => {
  const opts = { maxAttempts: 1, baseDelayMs: 0, maxDelayMs: 0, sleep };

  it.each([
    ["maxAttempts", 0],
    ["maxAttempts", -1],
    ["baseDelayMs", -1],
    ["maxDelayMs", -1],
  ])("rejects %s = %d with RangeError", (key, value) => {
    expect(() => new RetryingProvider(ok, { ...opts, [key]: value })).toThrow(RangeError);
  });

  it("accepts the boundary values", () => {
    expect(() => new RetryingProvider(ok, opts)).not.toThrow();
  });
});

describe("runFixture rejects malformed fixtures", () => {
  const base: Fixture = {
    name: "x",
    setup: { kind: "retry", maxAttempts: 1, baseDelayMs: 0, maxDelayMs: 0 },
    providers: [{ id: "a", script: [{ response: "ok" }] }],
    request: { messages: [] },
    expect: { result: { text: "ok", provider: "a" }, sleeps: [], calls: { a: 1 } },
  };

  it("retry with no providers", async () => {
    await expect(runFixture({ ...base, providers: [] })).rejects.toThrow("invalid fixture: retry setup needs a provider");
  });

  it("unknown setup kind", async () => {
    const fx = { ...base, setup: { kind: "bogus" } } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow("invalid fixture: unknown setup kind: bogus");
  });

  it("script entry with neither response nor error", async () => {
    const fx = { ...base, providers: [{ id: "a", script: [{}] }] } as unknown as Fixture;
    await expect(runFixture(fx)).rejects.toThrow("invalid fixture: script entry 1 of provider a has neither response nor error");
  });
});
