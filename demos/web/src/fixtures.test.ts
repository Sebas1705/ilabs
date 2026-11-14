import { fixtures, loadErrors, parseProviderFixtures } from "./fixtures";

const good = (name: string) =>
  JSON.stringify({ name, setup: { kind: "retry" }, providers: [], request: { messages: [] }, expect: {} });

describe("parseProviderFixtures", () => {
  it("skips malformed provider fixtures and names them, keeping the valid ones", () => {
    const { fixtures: ok, errors } = parseProviderFixtures({
      "../p/b.json": good("b"),
      "../p/a.json": good("a"),
      "../p/broken.json": "{",
      "../p/null.json": "null",
      "../p/no-setup.json": JSON.stringify({ name: "x", providers: [], request: { messages: [] }, expect: {} }),
      "../p/bad-kind.json": JSON.stringify({ name: "y", setup: { kind: "other" }, providers: [], request: { messages: [] }, expect: {} }),
      "../p/no-messages.json": JSON.stringify({ name: "z", setup: { kind: "fallback" }, providers: [], request: {}, expect: {} }),
    });
    expect(ok.map((f) => f.name)).toEqual(["a", "b"]);
    expect(errors).toHaveLength(5);
  });

  const base = { setup: { kind: "retry" }, providers: [{ id: "p", script: [{ response: "ok" }] }], request: { messages: [{ role: "user", content: "hi" }] }, expect: {} };
  const variants: Record<string, unknown> = {
    "provider-not-object": { ...base, providers: [null] },
    "provider-string": { ...base, providers: ["p"] },
    "provider-no-id": { ...base, providers: [{ script: [] }] },
    "provider-id-number": { ...base, providers: [{ id: 1, script: [] }] },
    "script-missing": { ...base, providers: [{ id: "p" }] },
    "script-not-array": { ...base, providers: [{ id: "p", script: {} }] },
    "entry-null": { ...base, providers: [{ id: "p", script: [null] }] },
    "entry-string": { ...base, providers: [{ id: "p", script: ["x"] }] },
    "entry-neither": { ...base, providers: [{ id: "p", script: [{}] }] },
    "entry-response-number": { ...base, providers: [{ id: "p", script: [{ response: 1 }] }] },
    "entry-error-string": { ...base, providers: [{ id: "p", script: [{ error: "boom" }] }] },
    "entry-error-null": { ...base, providers: [{ id: "p", script: [{ error: null }] }] },
    "entry-error-no-kind": { ...base, providers: [{ id: "p", script: [{ error: {} }] }] },
    "entry-retry-after-object": { ...base, providers: [{ id: "p", script: [{ error: { kind: "timeout", retryAfterMs: {} } }] }] },
    "message-null": { ...base, request: { messages: [null] } },
    "message-no-content": { ...base, request: { messages: [{ role: "user" }] } },
    "message-content-object": { ...base, request: { messages: [{ role: "user", content: {} }] } },
    "request-array": { ...base, request: [] },
    "setup-array": { ...base, setup: [] },
    "setup-max-attempts-string": { ...base, setup: { kind: "retry", maxAttempts: "3" } },
    "expect-array": { ...base, expect: [] },
  };

  it.each(Object.entries(variants))("reports %s by file name and never throws", (label, body) => {
    const file = { [`../p/${label}.json`]: JSON.stringify({ name: label, ...(body as object) }), "../p/good.json": good("good") };
    expect(() => parseProviderFixtures(file)).not.toThrow();
    const r = parseProviderFixtures(file);
    expect(r.fixtures.map((f) => f.name)).toEqual(["good"]);
    expect(r.errors).toHaveLength(1);
    expect(r.errors[0]).toMatch(new RegExp(`^${label}\\.json: `));
  });

  it("accepts every script entry flavour the page can print", () => {
    const entries = [{ response: "r" }, { error: { kind: "timeout" } }, { error: { kind: "rate_limit", retryAfterMs: 5 } }, { error: { kind: "x", retryAfterMs: null } }, { response: "r", error: { kind: "x" } }];
    const r = parseProviderFixtures({ "../p/ok.json": JSON.stringify({ name: "ok", ...base, providers: [{ id: "p", script: entries }] }) });
    expect(r.errors).toEqual([]);
  });

  it("loads every real provider contract with no errors", () => {
    expect(loadErrors).toEqual([]);
    expect(fixtures.length).toBeGreaterThan(0);
  });
});
