import { describe, expect, it } from "vitest";
import { parseSuite } from "./suite.js";

const msg = { role: "user", content: "hi" };
const one = (grader: unknown) => ({ cases: [{ id: "c1", messages: [msg], graders: [grader] }] });
const bad = (suite: unknown) => expect(() => parseSuite(suite)).toThrow(/^invalid suite: /);

describe("parseSuite", () => {
  it("returns the typed suite for a valid one", () => {
    const json = {
      cases: [
        {
          id: "c1",
          messages: [msg],
          graders: [
            { kind: "equals", value: "x", trim: true, ignoreCase: false },
            { kind: "oneOf", values: ["a", "b"] },
            { kind: "length", min: 1, max: 2.0 },
            { kind: "json", schema: { type: "object", unknownKeyword: 1 } },
            { kind: "jsonEquals", value: { anything: [1, { z: null }], extra: true } },
            { kind: "judge", rubric: " " },
          ],
        },
      ],
    };
    expect(parseSuite(json)).toEqual(json);
  });

  it("parses a string as strict JSON", () => {
    expect(parseSuite(JSON.stringify(one({ kind: "contains", value: "x" })))).toEqual(one({ kind: "contains", value: "x" }));
    // duplicate keys: the last one wins
    const dup = '{"cases":[{"id":"a","id":"c1","messages":[],"graders":[{"kind":"contains","value":"x"}]}]}';
    expect(parseSuite(dup).cases[0]!.id).toBe("c1");
  });

  for (const text of ["", "{", "{'cases': []}", '{"cases": [],}', "// c\n{}", '{"cases": NaN}', "[1,]"]) {
    it(`rejects the string ${JSON.stringify(text)} with the invalid suite prefix`, () => bad(text));
  }

  it("rejects a string that is valid JSON but not a suite", () => {
    bad("null");
    bad("[]");
    bad('"cases"');
  });

  it("applies the unknown-key rule only for a known kind and not inside schema or jsonEquals value", () => {
    bad(one({ kind: "length", min: 1, trim: true }));
    bad(one({ kind: "judge", rubric: "r", ignoreCase: false }));
    bad(one({ kind: "json", schema: {}, value: 1 }));
    bad(one({ kind: "equals", value: "x", schema: {} }));
    // unknown kind: reported as the kind (a throw either way), never accepted
    bad(one({ kind: "Contains", value: "x", extra: 1 }));
    expect(() => parseSuite(one({ kind: "jsonEquals", value: { trim: true } }))).not.toThrow();
    expect(() => parseSuite(one({ kind: "json", schema: { format: "email", pattern: "x" } }))).not.toThrow();
  });

  it("does not treat null as absent and requires booleans", () => {
    bad(one({ kind: "equals", value: "x", trim: null }));
    bad(one({ kind: "equals", value: "x", ignoreCase: "true" }));
    bad(one({ kind: "oneOf", values: ["a"], trim: 1 }));
    bad(one({ kind: "length", min: null, max: 3 }));
    bad(one({ kind: "equals", value: null }));
  });

  it("validates length bounds by value", () => {
    expect(() => parseSuite(one({ kind: "length", max: 1e2 }))).not.toThrow();
    expect(() => parseSuite(one({ kind: "length", min: 0 }))).not.toThrow();
    expect(() => parseSuite(one({ kind: "length", min: 2, max: 2 }))).not.toThrow();
    bad(one({ kind: "length" }));
    bad(one({ kind: "length", min: 1.5 }));
    bad(one({ kind: "length", min: -1 }));
    bad(one({ kind: "length", max: "3" }));
    bad(one({ kind: "length", max: true }));
    bad(one({ kind: "length", min: 3, max: 2 }));
    bad(one({ kind: "length", min: Number.NaN }));
    bad(one({ kind: "length", max: Infinity }));
  });

  it("validates oneOf, json, jsonEquals and judge parameters", () => {
    bad(one({ kind: "oneOf" }));
    bad(one({ kind: "oneOf", values: [] }));
    bad(one({ kind: "oneOf", values: "a" }));
    bad(one({ kind: "oneOf", values: ["a", 1] }));
    bad(one({ kind: "json" }));
    bad(one({ kind: "json", schema: null }));
    bad(one({ kind: "json", schema: [] }));
    bad(one({ kind: "json", schema: { type: "nope" } }));
    bad(one({ kind: "jsonEquals" }));
    expect(() => parseSuite(one({ kind: "jsonEquals", value: null }))).not.toThrow();
    bad(one({ kind: "judge" }));
    bad(one({ kind: "judge", rubric: "" }));
    bad(one({ kind: "judge", rubric: 5 }));
  });

  it("validates the suite, case and message shapes", () => {
    for (const s of [null, [], 5, "x", {}, { cases: {} }, { cases: [] }, { cases: [], extra: 1 }]) bad(s);
    const good = { id: "c1", messages: [msg], graders: [{ kind: "contains", value: "x" }] };
    bad({ cases: [null] });
    bad({ cases: [{ ...good, id: 1 }] });
    bad({ cases: [{ ...good, id: "" }] });
    bad({ cases: [good, good] });
    bad({ cases: [{ ...good, extra: 1 }] });
    bad({ cases: [{ id: "c1", graders: good.graders }] });
    bad({ cases: [{ ...good, messages: {} }] });
    bad({ cases: [{ ...good, graders: [] }] });
    bad({ cases: [{ ...good, graders: {} }] });
    bad({ cases: [{ ...good, graders: [5] }] });
    bad({ cases: [{ ...good, graders: [{ value: "x" }] }] });
    bad({ cases: [{ ...good, graders: [{ kind: 5 }] }] });
    bad({ cases: [{ ...good, messages: [5] }] });
    bad({ cases: [{ ...good, messages: [{ role: "tool", content: "x" }] }] });
    bad({ cases: [{ ...good, messages: [{ role: null, content: "x" }] }] });
    bad({ cases: [{ ...good, messages: [{ role: "user" }] }] });
    bad({ cases: [{ ...good, messages: [{ content: "x" }] }] });
    bad({ cases: [{ ...good, messages: [{ role: "user", content: 1 }] }] });
    bad({ cases: [{ ...good, messages: [{ role: "user", content: "x", name: "n" }] }] });
    expect(() => parseSuite({ cases: [{ ...good, messages: [] }] })).not.toThrow();
    for (const role of ["system", "user", "assistant"]) {
      expect(() => parseSuite({ cases: [{ ...good, messages: [{ role, content: "" }] }] })).not.toThrow();
    }
  });

  it("does not mutate its input", () => {
    const json = one({ kind: "oneOf", values: ["a"], trim: true });
    const copy = structuredClone(json);
    parseSuite(json);
    expect(json).toEqual(copy);
  });
});
