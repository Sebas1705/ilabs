import { evaluateGrader } from "@ilabs/evals";
import { DEFAULT_PARAMS, buildGrader, type GraderParams } from "./graderParams";

const p = (over: Partial<GraderParams>): GraderParams => ({ ...DEFAULT_PARAMS, ...over });

describe("buildGrader", () => {
  it("builds a string grader with its options", () => {
    expect(buildGrader(p({ kind: "equals", value: "hi", trim: true }))).toEqual({
      ok: true,
      grader: { kind: "equals", value: "hi", trim: true, ignoreCase: false },
    });
  });

  it.each(["equals", "contains", "notContains", "startsWith", "endsWith"] as const)("accepts an empty value for %s", (kind) => {
    expect(buildGrader(p({ kind, value: "" })).ok).toBe(true);
  });

  it("splits oneOf into one value per line and keeps blank lines as empty values", () => {
    const r = buildGrader(p({ kind: "oneOf", values: "a\r\nb\n\nc" }));
    expect(r).toEqual({ ok: true, grader: { kind: "oneOf", values: ["a", "b", "", "c"], trim: false, ignoreCase: false } });
  });

  it("reads empty oneOf text as the single empty-string value, as the library accepts it", () => {
    expect(buildGrader(p({ kind: "oneOf", values: "" }))).toEqual({
      ok: true,
      grader: { kind: "oneOf", values: [""], trim: false, ignoreCase: false },
    });
  });

  it("builds length from one or both bounds", () => {
    expect(buildGrader(p({ kind: "length", min: "2", max: "" }))).toEqual({ ok: true, grader: { kind: "length", min: 2 } });
    expect(buildGrader(p({ kind: "length", min: "", max: "0" }))).toEqual({ ok: true, grader: { kind: "length", max: 0 } });
    expect(buildGrader(p({ kind: "length", min: " 1 ", max: "3" }))).toEqual({ ok: true, grader: { kind: "length", min: 1, max: 3 } });
  });

  it.each([
    ["", "", /needs min or max/],
    ["  ", "", /needs min or max/],
    ["-1", "", /min must be an integer >= 0/],
    ["1.5", "", /min must be an integer >= 0/],
    ["abc", "", /min must be an integer >= 0/],
    ["", "x", /max must be an integer >= 0/],
    ["5", "2", /min must be <= max/],
  ])("rejects length bounds %j / %j with the library reason", (min, max, reason) => {
    const r = buildGrader(p({ kind: "length", min, max }));
    expect(r).toMatchObject({ ok: false, error: "pErrParams" });
    expect(!r.ok && r.detail).toMatch(/^invalid suite:/);
    expect(!r.ok && r.detail).toMatch(reason);
  });

  it("passes numbers as typed: whatever the library accepts is accepted (no rule of our own)", () => {
    expect(buildGrader(p({ kind: "length", min: "1e3" }))).toEqual({ ok: true, grader: { kind: "length", min: 1000 } });
    expect(buildGrader(p({ kind: "length", max: "99999999999999999999" })).ok).toBe(true);
  });

  it("parses the schema text and rejects text that is not JSON", () => {
    expect(buildGrader(p({ kind: "json", schema: '{"type":"object"}' }))).toEqual({
      ok: true,
      grader: { kind: "json", schema: { type: "object" } },
    });
    expect(buildGrader(p({ kind: "json", schema: "{nope" }))).toEqual({ ok: false, error: "pErrJsonInvalid" });
    expect(buildGrader(p({ kind: "json", schema: "" }))).toEqual({ ok: false, error: "pErrJsonInvalid" });
  });

  it("rejects a JSON text that is not a valid schema, with the library reason as detail", () => {
    const r = buildGrader(p({ kind: "json", schema: '{"type":"nope"}' }));
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.error).toBe("pErrSchema");
      expect(r.detail).toMatch(/^invalid suite:/);
    }
  });

  it("accepts null as the jsonEquals value and rejects an empty text", () => {
    expect(buildGrader(p({ kind: "jsonEquals", jsonValue: "null" }))).toEqual({ ok: true, grader: { kind: "jsonEquals", value: null } });
    expect(buildGrader(p({ kind: "jsonEquals", jsonValue: "" }))).toEqual({ ok: false, error: "pErrJsonInvalid" });
    expect(buildGrader(p({ kind: "jsonEquals", jsonValue: "{x" }))).toEqual({ ok: false, error: "pErrJsonInvalid" });
  });

  it("produces graders that evaluateGrader accepts", () => {
    const r = buildGrader(p({ kind: "contains", value: "ell", ignoreCase: true }));
    expect(r.ok && evaluateGrader(r.grader, "HELLO").passed).toBe(true);
  });
});
