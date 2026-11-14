import { loadErrors, parseFixtures } from "./contextFixtures";

const good = (name: string) =>
  JSON.stringify({ name, setup: { kind: "window", maxTokens: 10 }, messages: [{ role: "user", content: "hi" }], expect: { result: {} } });

describe("parseFixtures", () => {
  it("keeps valid fixtures sorted by code unit and reports the bad ones by file", () => {
    const { fixtures, errors } = parseFixtures({
      "../c/window-b.json": good("window-b"),
      "../c/broken.json": "{ not json",
      "../c/window-a.json": good("window-a"),
      "../c/no-messages.json": JSON.stringify({ name: "x", setup: { kind: "window", maxTokens: 1 } }),
      "../c/bad-kind.json": JSON.stringify({ name: "y", setup: { kind: "other", maxTokens: 1 }, messages: [] }),
      "../c/bad-message.json": JSON.stringify({ name: "z", setup: { kind: "summarize", maxTokens: 1 }, messages: [{ role: "user" }] }),
      "../c/null.json": "null",
    });
    expect(fixtures.map((f) => f.name)).toEqual(["window-a", "window-b"]);
    expect(errors).toHaveLength(5);
    for (const file of ["broken.json", "no-messages.json", "bad-kind.json", "bad-message.json", "null.json"]) {
      expect(errors.filter((e) => e.includes(file)), file).toHaveLength(1);
    }
  });

  it("never throws, whatever the input", () => {
    expect(() => parseFixtures({ a: "", b: "[]", c: "42", d: '{"name":1}' })).not.toThrow();
    expect(parseFixtures({}).fixtures).toEqual([]);
  });

  it("loads every real contract with no errors", () => {
    expect(loadErrors).toEqual([]);
  });
});
