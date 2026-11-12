import { MockProvider, ProviderError, type CompletionRequest, type Message, type Provider } from "@ilabs/providers";
import { describe, expect, it } from "vitest";
import type { Schema } from "./schema.js";
import { structured, StructuredError } from "./structured.js";

const schema: Schema = { type: "object", required: ["n"], properties: { n: { type: "integer" } } };

function recording(script: ConstructorParameters<typeof MockProvider>[1]) {
  const mock = new MockProvider("m", script);
  const seen: Message[][] = [];
  const provider: Provider = {
    id: "m",
    complete(req) {
      seen.push(req.messages.map((m) => ({ ...m })));
      return mock.complete(req);
    },
  };
  return { provider, seen };
}

const request = (): CompletionRequest => ({ messages: [{ role: "user", content: "go" }] });

describe("structured", () => {
  it("returns the value on the first try", async () => {
    const { provider } = recording([{ response: '{"n": 1}' }]);
    const r = await structured(provider, request(), schema);
    expect(r.value).toEqual({ n: 1 });
    expect(r.attempts).toBe(1);
    expect(r.trace).toEqual([{ reply: '{"n": 1}', extracted: { ok: true, value: { n: 1 } }, issues: [] }]);
  });

  it("rejects an invalid maxRepairs", async () => {
    const { provider } = recording([{ response: '{"n": 1}' }]);
    await expect(structured(provider, request(), schema, { maxRepairs: -1 })).rejects.toThrow(RangeError);
    await expect(structured(provider, request(), schema, { maxRepairs: NaN })).rejects.toThrow(RangeError);
    await expect(structured(provider, request(), schema, { maxRepairs: Infinity })).rejects.toThrow(RangeError);
    await expect(structured(provider, request(), schema, { maxRepairs: 1.5 })).rejects.toThrow(RangeError);
  });

  it("accepts maxRepairs 0", async () => {
    const { provider } = recording([{ response: '{"n": 1}' }]);
    const r = await structured(provider, request(), schema, { maxRepairs: 0 });
    expect(r.attempts).toBe(1);
  });

  it("rejects a malformed schema before calling the provider", async () => {
    const { provider, seen } = recording([{ response: "{}" }]);
    await expect(structured(provider, request(), { type: "nope" } as never)).rejects.toThrow(/^invalid schema:/);
    expect(seen.length).toBe(0);
  });

  it("repairs with the verbatim reply and a non-empty user message", async () => {
    const { provider, seen } = recording([{ response: "not json" }, { response: '{"n": 2}' }]);
    const r = await structured(provider, request(), schema);
    expect(r.attempts).toBe(2);
    expect(seen[1]?.length).toBe(3);
    expect(seen[1]?.[1]).toEqual({ role: "assistant", content: "not json" });
    expect(seen[1]?.[2]?.role).toBe("user");
    expect(seen[1]?.[2]?.content.length).toBeGreaterThan(0);
  });

  it("accumulates messages over two repairs", async () => {
    const { provider, seen } = recording([{ response: "first" }, { response: '{"n": "x"}' }, { response: '{"n": 3}' }]);
    const r = await structured(provider, request(), schema, { maxRepairs: 2 });
    expect(r.attempts).toBe(3);
    expect(seen.map((m) => m.length)).toEqual([1, 3, 5]);
    expect(seen[2]?.[1]?.content).toBe("first");
    expect(seen[2]?.[3]?.content).toBe('{"n": "x"}');
    expect(seen[2]?.map((m) => m.role)).toEqual(["user", "assistant", "user", "assistant", "user"]);
  });

  it("does not mutate the original messages array", async () => {
    const req = request();
    const before = JSON.stringify(req);
    const { provider } = recording([{ response: "x" }, { response: '{"n": 1}' }]);
    await structured(provider, req, schema);
    expect(JSON.stringify(req)).toBe(before);
    expect(req.messages.length).toBe(1);
  });

  it("throws StructuredError with the issues of the last attempt", async () => {
    const { provider } = recording([{ response: "x" }, { response: '{"n": 1.5}' }]);
    const err = await structured(provider, request(), schema, { maxRepairs: 1 }).catch((e) => e);
    expect(err).toBeInstanceOf(StructuredError);
    expect(err.kind).toBe("invalid_output");
    expect(err.attempts).toBe(2);
    expect(err.issues).toEqual([{ path: "/n", code: "type" }]);
    expect(err.trace.length).toBe(2);
    expect(err.trace[0].extracted).toEqual({ ok: false, code: "no_json" });
  });

  it("defaults to two repairs", async () => {
    const { provider, seen } = recording([{ response: "a" }, { response: "b" }, { response: "c" }, { response: '{"n": 1}' }]);
    const err = await structured(provider, request(), schema).catch((e) => e);
    expect(err).toBeInstanceOf(StructuredError);
    expect(seen.length).toBe(3);
  });

  it("propagates provider errors unchanged", async () => {
    const { provider } = recording([{ response: "x" }, { error: { kind: "rate_limit" } }]);
    const err = await structured(provider, request(), schema).catch((e) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect(err.kind).toBe("rate_limit");
  });

  it("rethrows non-provider exceptions unchanged", async () => {
    const boom = new TypeError("boom");
    const provider: Provider = { id: "x", complete: () => Promise.reject(boom) };
    await expect(structured(provider, request(), schema)).rejects.toBe(boom);
  });
});
