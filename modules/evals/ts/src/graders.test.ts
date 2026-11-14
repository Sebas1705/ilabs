import { describe, expect, it } from "vitest";
import type { Schema } from "@ilabs/structured";
import { evaluateGrader, foldAscii } from "./graders.js";

describe("evaluateGrader: string graders", () => {
  it("compares exactly by default", () => {
    expect(evaluateGrader({ kind: "equals", value: "Paris" }, "Paris")).toEqual({ kind: "equals", passed: true });
    expect(evaluateGrader({ kind: "equals", value: "Paris" }, " Paris").passed).toBe(false);
    expect(evaluateGrader({ kind: "equals", value: "Paris" }, "paris").passed).toBe(false);
  });

  it("trims only space, tab, LF and CR on both sides", () => {
    expect(evaluateGrader({ kind: "equals", value: "\tyes ", trim: true }, "  yes\r\n").passed).toBe(true);
    expect(evaluateGrader({ kind: "equals", value: "ok", trim: true }, " ok ").passed).toBe(false);
    expect(evaluateGrader({ kind: "equals", value: "ok", trim: true }, "\u000bok").passed).toBe(false);
    expect(evaluateGrader({ kind: "equals", value: "ok", trim: true }, "﻿ok").passed).toBe(false);
  });

  it("folds ASCII only", () => {
    const eq = (value: string, reply: string) => evaluateGrader({ kind: "equals", value, ignoreCase: true }, reply).passed;
    expect(eq("Hello World-1", "hELLO wORLD-1")).toBe(true);
    expect(eq("é", "É")).toBe(false);
    expect(eq("k", "K")).toBe(false);
    expect(eq("ß", "SS")).toBe(false);
    expect(eq("i", "İ")).toBe(false);
    expect(eq("I", "ı")).toBe(false);
  });

  it("applies trim then ignoreCase, then the empty-string rules to the processed value", () => {
    expect(evaluateGrader({ kind: "contains", value: " \t", trim: true }, "abc").passed).toBe(true);
    expect(evaluateGrader({ kind: "notContains", value: " ", trim: true }, "abc").passed).toBe(false);
    expect(evaluateGrader({ kind: "contains", value: "", }, "").passed).toBe(true);
    expect(evaluateGrader({ kind: "startsWith", value: "" }, "").passed).toBe(true);
    expect(evaluateGrader({ kind: "endsWith", value: "" }, "x").passed).toBe(true);
    expect(evaluateGrader({ kind: "notContains", value: "" }, "").passed).toBe(false);
    expect(evaluateGrader({ kind: "notContains", value: "x" }, "").passed).toBe(true);
  });

  it("covers contains, notContains, startsWith, endsWith and oneOf", () => {
    expect(evaluateGrader({ kind: "contains", value: "ell" }, "hello").passed).toBe(true);
    expect(evaluateGrader({ kind: "contains", value: "ELL" }, "hello").passed).toBe(false);
    expect(evaluateGrader({ kind: "contains", value: "ELL", ignoreCase: true }, "hello").passed).toBe(true);
    expect(evaluateGrader({ kind: "startsWith", value: "he" }, "hello").passed).toBe(true);
    expect(evaluateGrader({ kind: "startsWith", value: "lo" }, "hello").passed).toBe(false);
    expect(evaluateGrader({ kind: "endsWith", value: "lo" }, "hello").passed).toBe(true);
    expect(evaluateGrader({ kind: "endsWith", value: " hello ", trim: true }, "hello\n").passed).toBe(true);
    expect(evaluateGrader({ kind: "oneOf", values: ["a", " b "], trim: true }, "\nb\n").passed).toBe(true);
    expect(evaluateGrader({ kind: "oneOf", values: ["a", " b "] }, "b").passed).toBe(false);
    expect(evaluateGrader({ kind: "oneOf", values: ["A", "B"], ignoreCase: true }, "b").passed).toBe(true);
    expect(evaluateGrader({ kind: "oneOf", values: ["A", "B"], ignoreCase: true }, "c").passed).toBe(false);
  });

  it("never reports issues or errors for string graders", () => {
    const r = evaluateGrader({ kind: "equals", value: "x" }, "y");
    expect(Object.keys(r)).toEqual(["kind", "passed"]);
  });
});

describe("evaluateGrader: length", () => {
  it("counts code points, inclusive bounds", () => {
    expect(evaluateGrader({ kind: "length", min: 2, max: 2 }, "\u{1F600}\u{1F600}").passed).toBe(true);
    expect(evaluateGrader({ kind: "length", min: 2 }, "\u{1F600}").passed).toBe(false);
    expect(evaluateGrader({ kind: "length", max: 3 }, "x\ud83dy").passed).toBe(true);
    expect(evaluateGrader({ kind: "length", min: 3, max: 3 }, "\ude00\ud83dA").passed).toBe(true);
    expect(evaluateGrader({ kind: "length", max: 0 }, "").passed).toBe(true);
    expect(evaluateGrader({ kind: "length", min: 1 }, "").passed).toBe(false);
    expect(evaluateGrader({ kind: "length", max: 5 }, "").passed).toBe(true);
    expect(evaluateGrader({ kind: "length", min: 2, max: 3 }, "abcd").passed).toBe(false);
  });
});

describe("evaluateGrader: json and jsonEquals", () => {
  const schema: Schema = { type: "object", required: ["name"], properties: { age: { type: "integer", minimum: 0 } } };

  it("json always reports issues", () => {
    expect(evaluateGrader({ kind: "json", schema }, '{"name":"a"}')).toEqual({ kind: "json", passed: true, issues: [] });
    expect(evaluateGrader({ kind: "json", schema }, '{"age":-1}')).toEqual({
      kind: "json",
      passed: false,
      issues: [
        { path: "/name", code: "required" },
        { path: "/age", code: "minimum" },
      ],
    });
    expect(evaluateGrader({ kind: "json", schema }, "plain")).toEqual({ kind: "json", passed: false, issues: [{ path: "", code: "no_json" }] });
    expect(evaluateGrader({ kind: "json", schema }, "{'a': 1}")).toEqual({ kind: "json", passed: false, issues: [{ path: "", code: "invalid_json" }] });
  });

  it("jsonEquals compares by value and reports extraction failures only", () => {
    expect(evaluateGrader({ kind: "jsonEquals", value: { a: [1, 2], b: null } }, 'x {"b": null, "a": [1.0, 2]} y')).toEqual({
      kind: "jsonEquals",
      passed: true,
      issues: [],
    });
    expect(evaluateGrader({ kind: "jsonEquals", value: [1, 2] }, "[2, 1]")).toEqual({ kind: "jsonEquals", passed: false, issues: [] });
    expect(evaluateGrader({ kind: "jsonEquals", value: null }, "null").passed).toBe(true);
    expect(evaluateGrader({ kind: "jsonEquals", value: 1 }, '"1"').passed).toBe(false);
    expect(evaluateGrader({ kind: "jsonEquals", value: 1 }, "nothing")).toEqual({ kind: "jsonEquals", passed: false, issues: [{ path: "", code: "no_json" }] });
  });
});

describe("foldAscii", () => {
  it("maps only A-Z", () => {
    expect(foldAscii("AZaz@[`{09 ÉKİ")).toBe("azaz@[`{09 ÉKİ");
    expect(foldAscii("")).toBe("");
  });
});
