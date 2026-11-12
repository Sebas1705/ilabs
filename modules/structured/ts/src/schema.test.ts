import { describe, expect, it } from "vitest";
import { checkSchema, InvalidSchemaError, validate, type Schema } from "./schema.js";

const bad: [string, unknown][] = [
  ["unknown type", { type: "date" }],
  ["non-string type", { type: 3 }],
  ["non-array required", { required: "a" }],
  ["non-string required item", { required: [1] }],
  ["non-object properties", { properties: [] }],
  ["null properties", { properties: null }],
  ["empty enum", { enum: [] }],
  ["non-array enum", { enum: "a" }],
  ["non-numeric minimum", { minimum: "1" }],
  ["non-numeric maximum", { maximum: null }],
  ["non-numeric minLength", { minLength: "1" }],
  ["non-numeric maxLength", { maxLength: {} }],
  ["non-numeric minItems", { minItems: true }],
  ["non-numeric maxItems", { maxItems: [] }],
  ["non-boolean additionalProperties", { additionalProperties: {} }],
  ["non-object items", { items: 1 }],
  ["malformed nested property", { properties: { a: { type: "nope" } } }],
  ["malformed nested items", { items: { required: 1 } }],
  ["schema is an array", []],
  ["schema is null", null],
  ["schema is a string", "string"],
];

describe("checkSchema", () => {
  for (const [name, schema] of bad) {
    it(`rejects ${name}`, () => {
      expect(() => checkSchema(schema)).toThrow(InvalidSchemaError);
      expect(() => checkSchema(schema)).toThrow(/^invalid schema: .+/);
    });
  }

  it("accepts the empty schema and ignores unknown keywords", () => {
    expect(() => checkSchema({})).not.toThrow();
    expect(() => checkSchema({ pattern: 5, format: "x", type: "string" })).not.toThrow();
  });
});

const codes = (s: Schema, v: unknown) => validate(s, v).map((i) => `${i.path}:${i.code}`);

describe("validate", () => {
  it("type", () => {
    expect(codes({ type: "string" }, 1)).toEqual([":type"]);
    expect(codes({ type: "number" }, 1.5)).toEqual([]);
    expect(codes({ type: "integer" }, 2.5)).toEqual([":type"]);
    expect(codes({ type: "integer" }, 2)).toEqual([]);
    expect(codes({ type: "object" }, [])).toEqual([":type"]);
    expect(codes({ type: "array" }, {})).toEqual([":type"]);
    expect(codes({ type: "null" }, null)).toEqual([]);
    expect(codes({ type: "boolean" }, "true")).toEqual([":type"]);
  });

  it("enum uses deep equality", () => {
    expect(codes({ enum: [{ a: 1, b: [2] }] }, { b: [2.0], a: 1 })).toEqual([]);
    expect(codes({ enum: [1] }, true)).toEqual([":enum"]);
    expect(codes({ enum: ["1"] }, 1)).toEqual([":enum"]);
  });

  it("minimum and maximum are inclusive", () => {
    const s: Schema = { minimum: 1, maximum: 3 };
    expect(codes(s, 0)).toEqual([":minimum"]);
    expect(codes(s, 1)).toEqual([]);
    expect(codes(s, 3)).toEqual([]);
    expect(codes(s, 4)).toEqual([":maximum"]);
  });

  it("minLength and maxLength count code points", () => {
    expect(codes({ maxLength: 2 }, "\u{1F600}\u{1F600}")).toEqual([]);
    expect(codes({ minLength: 3 }, "\u{1F600}\u{1F600}")).toEqual([":minLength"]);
    expect(codes({ maxLength: 1 }, "ab")).toEqual([":maxLength"]);
    expect(codes({ maxLength: 1 }, "\ud83d")).toEqual([]);
  });

  it("minItems and maxItems", () => {
    expect(codes({ minItems: 2 }, [1])).toEqual([":minItems"]);
    expect(codes({ maxItems: 1 }, [1, 2])).toEqual([":maxItems"]);
    expect(codes({ minItems: 1, maxItems: 1 }, [1])).toEqual([]);
  });

  it("required in schema order with escaped pointers", () => {
    expect(codes({ required: ["b", "a/c", "x~y"] }, {})).toEqual(["/b:required", "/a~1c:required", "/x~0y:required"]);
  });

  it("additionalProperties false reports sorted extras", () => {
    expect(codes({ additionalProperties: false, properties: { a: {} } }, { z: 1, a: 1, b: 1 })).toEqual([
      "/b:additionalProperties",
      "/z:additionalProperties",
    ]);
    expect(codes({ additionalProperties: true }, { z: 1 })).toEqual([]);
  });

  it("properties and items recurse; type failure stops descent", () => {
    const s: Schema = { type: "object", properties: { l: { type: "array", items: { type: "string" } } } };
    expect(codes(s, { l: ["a", 1, 2] })).toEqual(["/l/1:type", "/l/2:type"]);
    expect(codes({ type: "string", minLength: 5 }, 1)).toEqual([":type"]);
  });

  it("ignores keywords that do not apply to the instance kind", () => {
    expect(codes({ minimum: 5, minLength: 5, minItems: 5, required: ["a"] }, null)).toEqual([]);
  });

  it("does not misbehave on __proto__ keys", () => {
    const v = JSON.parse('{"__proto__": 1}');
    expect(codes({ required: ["__proto__"] }, v)).toEqual([]);
    expect(codes({ required: ["toString"] }, {})).toEqual(["/toString:required"]);
  });
});
