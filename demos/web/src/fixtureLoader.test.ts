import { parseFixtureFiles } from "./fixtureLoader";

type Named = { name: string };
const shape = (v: unknown): string | null => {
  if (typeof v !== "object" || v === null || Array.isArray(v)) return "not an object";
  return typeof (v as { name?: unknown }).name === "string" ? null : "missing name";
};
const parse = (files: Record<string, string>) => parseFixtureFiles<Named>(files, shape);

describe("parseFixtureFiles", () => {
  it("never throws on malformed input and keeps the valid fixtures, sorted by code unit", () => {
    const files = {
      "../c/b.json": '{"name":"b"}',
      "../c/invalid.json": "{ nope",
      "../c/null.json": "null",
      "../c/array.json": "[]",
      "../c/number.json": "42",
      "../c/empty.json": "",
      "../c/nokey.json": '{"other":1}',
      "../c/a.json": '{"name":"a"}',
      "../c/Z.json": '{"name":"Z"}',
    };
    expect(() => parse(files)).not.toThrow();
    const { fixtures, errors } = parse(files);
    expect(fixtures.map((f) => f.name)).toEqual(["Z", "a", "b"]);
    expect(errors).toHaveLength(6);
    for (const file of ["invalid.json", "null.json", "array.json", "number.json", "empty.json", "nokey.json"]) {
      expect(errors.filter((e) => e.startsWith(`${file}:`)), file).toHaveLength(1);
    }
    expect(errors).toEqual([...errors].sort());
  });

  it("returns nothing for no files and tolerates a shape check that throws", () => {
    expect(parse({})).toEqual({ fixtures: [], errors: [] });
    const r = parseFixtureFiles<Named>({ "x/y.json": '{"name":"a"}' }, () => {
      throw new Error("boom");
    });
    expect(r.fixtures).toEqual([]);
    expect(r.errors).toEqual(["y.json: boom"]);
  });

  it("accepts a lone surrogate escape, which JSON.parse allows", () => {
    expect(parse({ "s.json": '{"name":"\\ud800"}' }).fixtures).toHaveLength(1);
  });
});
