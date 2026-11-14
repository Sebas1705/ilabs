import { readdirSync } from "node:fs";
import { resolve } from "node:path";
import { evalsFixtures, groupEvalsFixtures, loadErrors, parseEvalsFixtures } from "./evalsFixtures";

const good = (name: string, extra: Record<string, unknown> = {}) =>
  JSON.stringify({ name, suite: { cases: [] }, targets: [{ id: "a", script: [{ response: "x" }] }], expect: { invalidSuite: true }, ...extra });

describe("parseEvalsFixtures", () => {
  it("keeps valid fixtures sorted and names the skipped ones", () => {
    const { fixtures, errors } = parseEvalsFixtures({
      "../e/b.json": good("b"),
      "../e/a.json": good("a"),
      "../e/null-suite.json": good("null-suite", { suite: null }),
      "../e/broken.json": "{",
      "../e/array.json": "[]",
      "../e/no-targets.json": JSON.stringify({ name: "x", suite: {}, expect: {} }),
    });
    expect(fixtures.map((f) => f.name)).toEqual(["a", "b", "null-suite"]);
    expect(errors).toHaveLength(3);
  });

  const variants: Record<string, string> = {
    "suite-absent": JSON.stringify({ name: "x", targets: [], expect: {} }),
    "target-null": good("x", { targets: [null] }),
    "target-no-id": good("x", { targets: [{ script: [] }] }),
    "target-id-number": good("x", { targets: [{ id: 1, script: [] }] }),
    "script-missing": good("x", { targets: [{ id: "a" }] }),
    "script-entry-null": good("x", { targets: [{ id: "a", script: [null] }] }),
    "script-entry-neither": good("x", { targets: [{ id: "a", script: [{}] }] }),
    "judge-array": good("x", { judge: [] }),
    "judge-no-script": good("x", { judge: {} }),
    "expect-array": good("x", { expect: [] }),
    "name-number": good("x", { name: 5 }),
  };
  it.each(Object.keys(variants))("rejects a malformed shape without throwing: %s", (key) => {
    const { fixtures, errors } = parseEvalsFixtures({ [`../e/${key}.json`]: variants[key]! });
    expect(fixtures).toHaveLength(0);
    expect(errors).toHaveLength(1);
  });

  it("accepts a judge with a script", () => {
    expect(parseEvalsFixtures({ "../e/j.json": good("j", { judge: { script: [{ response: "PASS" }] } }) }).fixtures).toHaveLength(1);
  });
});

describe("the shipped contract fixtures", () => {
  const files = readdirSync(resolve(__dirname, "../../../contracts/evals")).filter((f) => f.endsWith(".json"));

  it("loads every file without a load error", () => {
    expect(files.length).toBeGreaterThanOrEqual(162);
    expect(loadErrors).toEqual([]);
    expect(evalsFixtures).toHaveLength(files.length);
  });

  it("puts every fixture in exactly one group, with no stray 'other'", () => {
    const groups = groupEvalsFixtures(evalsFixtures);
    expect(groups.reduce((n, g) => n + g.fixtures.length, 0)).toBe(evalsFixtures.length);
    expect(groups.map((g) => g.label)).toEqual(["groupRun", "groupStrings", "groupLength", "groupJson", "groupJudge", "groupInvalid"]);
  });

  it("sends unknown prefixes to a trailing other group", () => {
    const groups = groupEvalsFixtures([{ name: "zzz-new" } as never]);
    expect(groups.map((g) => g.label)).toEqual(["groupOther"]);
  });
});
