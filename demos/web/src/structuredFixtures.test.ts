import { loadErrors, parseStructuredFixtures, structuredFixtures } from "./structuredFixtures";

const good = (name: string) =>
  JSON.stringify({ name, schema: {}, request: { messages: [] }, setup: { maxRepairs: 0 }, provider: { id: "p", script: [] }, expect: {} });

describe("parseStructuredFixtures", () => {
  it("skips malformed structured fixtures and names them, keeping the valid ones", () => {
    const { fixtures, errors } = parseStructuredFixtures({
      "../s/b.json": good("b"),
      "../s/a.json": good("a"),
      "../s/broken.json": "{",
      "../s/array.json": "[]",
      "../s/no-schema.json": JSON.stringify({ name: "x", request: { messages: [] }, setup: {}, provider: {}, expect: {} }),
      "../s/no-provider.json": JSON.stringify({ name: "y", schema: {}, request: { messages: [] }, setup: {}, expect: {} }),
    });
    expect(fixtures.map((f) => f.name)).toEqual(["a", "b"]);
    expect(errors).toHaveLength(4);
  });

  const base = {
    schema: {},
    request: { messages: [{ role: "user", content: "hi" }] },
    setup: { maxRepairs: 1 },
    provider: { id: "p", script: [{ response: "{}" }] },
    expect: {},
  };
  const variants: Record<string, unknown> = {
    "script-missing": { ...base, provider: { id: "p" } },
    "script-not-array": { ...base, provider: { id: "p", script: {} } },
    "provider-no-id": { ...base, provider: { script: [] } },
    "provider-id-number": { ...base, provider: { id: 1, script: [] } },
    "entry-null": { ...base, provider: { id: "p", script: [null] } },
    "entry-string": { ...base, provider: { id: "p", script: ["x"] } },
    "entry-neither": { ...base, provider: { id: "p", script: [{}] } },
    "entry-response-number": { ...base, provider: { id: "p", script: [{ response: 1 }] } },
    "entry-error-string": { ...base, provider: { id: "p", script: [{ error: "boom" }] } },
    "entry-error-null": { ...base, provider: { id: "p", script: [{ error: null }] } },
    "entry-error-no-kind": { ...base, provider: { id: "p", script: [{ error: {} }] } },
    "entry-retry-after-object": { ...base, provider: { id: "p", script: [{ error: { kind: "timeout", retryAfterMs: {} } }] } },
    "message-null": { ...base, request: { messages: [null] } },
    "message-no-role": { ...base, request: { messages: [{ content: "x" }] } },
    "message-content-object": { ...base, request: { messages: [{ role: "user", content: {} }] } },
    "request-array": { ...base, request: [] },
    "setup-array": { ...base, setup: [] },
    "setup-no-max-repairs": { ...base, setup: {} },
    "setup-max-repairs-string": { ...base, setup: { maxRepairs: "1" } },
    "provider-array": { ...base, provider: [] },
    "expect-array": { ...base, expect: [] },
  };

  it.each(Object.entries(variants))("reports %s by file name and never throws", (label, body) => {
    const file = { [`../s/${label}.json`]: JSON.stringify({ name: label, ...(body as object) }), "../s/good.json": good("good") };
    expect(() => parseStructuredFixtures(file)).not.toThrow();
    const r = parseStructuredFixtures(file);
    expect(r.fixtures.map((f) => f.name)).toEqual(["good"]);
    expect(r.errors).toHaveLength(1);
    expect(r.errors[0]).toMatch(new RegExp(`^${label}\\.json: `));
  });

  it("accepts every script entry flavour the page can print", () => {
    const entries = [{ response: "r" }, { error: { kind: "timeout" } }, { error: { kind: "rate_limit", retryAfterMs: 5 } }, { error: { kind: "x", retryAfterMs: null } }];
    const r = parseStructuredFixtures({ "../s/ok.json": JSON.stringify({ name: "ok", ...base, provider: { id: "p", script: entries } }) });
    expect(r.errors).toEqual([]);
  });

  it("loads every real structured contract with no errors", () => {
    expect(loadErrors).toEqual([]);
    expect(structuredFixtures.length).toBeGreaterThan(0);
  });
});
