import { describe, expect, it, vi } from "vitest";
import { extractJson } from "./extract.js";

const nested = (n: number) => "[".repeat(n) + "]".repeat(n);

describe("extractJson", () => {
  it("empty and whitespace-only text is no_json", () => {
    expect(extractJson("")).toEqual({ ok: false, code: "no_json" });
    expect(extractJson(" \t\r\n ")).toEqual({ ok: false, code: "no_json" });
  });

  it("does not call JSON.parse for empty candidates", () => {
    const spy = vi.spyOn(JSON, "parse");
    try {
      const text = "```\n```\n".repeat(1000) + "```json\n \t\r\n```\n".repeat(1000);
      expect(extractJson(text)).toEqual({ ok: false, code: "invalid_json" });
      const emptyCalls = spy.mock.calls.filter(([arg]) => /^[ \t\r\n]*$/.test(String(arg)));
      expect(emptyCalls).toHaveLength(0);
      expect(spy.mock.calls.length).toBeLessThanOrEqual(1);
    } finally {
      spy.mockRestore();
    }
  });

  it("parses the whole trimmed text", () => {
    expect(extractJson(' \n{"a": 1}\t')).toEqual({ ok: true, value: { a: 1 } });
    expect(extractJson("42")).toEqual({ ok: true, value: 42 });
  });

  it("only JSON whitespace is trimmed", () => {
    expect(extractJson(" 42")).toEqual({ ok: false, code: "no_json" });
    expect(extractJson("﻿42")).toEqual({ ok: false, code: "no_json" });
  });

  it("fence variants", () => {
    expect(extractJson('x\n```json\n{"a":1}\n```\ny')).toEqual({ ok: true, value: { a: 1 } });
    expect(extractJson("x\n```\n[1]\n```")).toEqual({ ok: true, value: [1] });
    expect(extractJson('```JSON extra tag\r\n{"a":1}\r\n```   \r\n')).toEqual({ ok: true, value: { a: 1 } });
    expect(extractJson("```\nnope\n```\n```\n[2]\n```")).toEqual({ ok: true, value: [2] });
  });

  it("indented fences are not fences", () => {
    expect(extractJson("  ```\n42\n  ```")).toEqual({ ok: false, code: "invalid_json" });
  });

  it("unterminated fence gives no fenced candidate", () => {
    expect(extractJson("```json\n42")).toEqual({ ok: false, code: "invalid_json" });
  });

  it("string values with backticks, braces and brackets", () => {
    const text = 'Here: {"s": "```} ]{ [", "t": "\\"}"} thanks';
    expect(extractJson(text)).toEqual({ ok: true, value: { s: "```} ]{ [", t: '"}' } });
  });

  it("only the first region is tried", () => {
    expect(extractJson('{oops} {"a":1}')).toEqual({ ok: false, code: "invalid_json" });
  });

  it("unbalanced region is invalid_json; bare prose is no_json", () => {
    expect(extractJson('{"a": [1, 2}')).toEqual({ ok: false, code: "invalid_json" });
    expect(extractJson('{"a": 1')).toEqual({ ok: false, code: "invalid_json" });
    expect(extractJson("Sorry, I cannot")).toEqual({ ok: false, code: "no_json" });
  });

  it("strict JSON only", () => {
    for (const t of ["{'a':1}", '{"a":1,}', "[01]", "[NaN]", '["a\nb"]']) {
      expect(extractJson(t).ok, t).toBe(false);
    }
  });

  it("depth limit is 128, checked outside strings", () => {
    expect(extractJson(nested(128)).ok).toBe(true);
    expect(extractJson(nested(129))).toEqual({ ok: false, code: "invalid_json" });
    expect(extractJson(nested(200))).toEqual({ ok: false, code: "invalid_json" });
    expect(extractJson(`prose ${nested(129)}`)).toEqual({ ok: false, code: "invalid_json" });
    expect(extractJson('["' + "[".repeat(500) + '"]').ok).toBe(true);
  });

  it("very deep input does not overflow the stack", () => {
    expect(extractJson("[".repeat(200_000) + "]".repeat(200_000))).toEqual({ ok: false, code: "invalid_json" });
  });
});
