import { compareCodeUnits } from "./sort";
import { fixtures } from "./fixtures";
import { structuredFixtures } from "./structuredFixtures";

describe("compareCodeUnits", () => {
  it("orders by UTF-16 code unit, like String.compareTo, whatever the locale", () => {
    expect(["b", "a", "B", "_x", "-y", "é", "z", "ab", "a-b"].sort(compareCodeUnits)).toEqual(
      ["-y", "B", "_x", "a", "a-b", "ab", "b", "z", "é"],
    );
    expect(compareCodeUnits("a", "a")).toBe(0);
    expect(compareCodeUnits("a", "b")).toBeLessThan(0);
    expect(compareCodeUnits("b", "a")).toBeGreaterThan(0);
  });
});

describe("fixture order", () => {
  it.each([
    ["providers", fixtures],
    ["structured", structuredFixtures],
  ])("sorts the %s fixtures by plain code-unit order of their names", (_label, list) => {
    const names = list.map((f) => f.name);
    expect(names.length).toBeGreaterThan(0);
    expect(names).toEqual([...names].sort());
  });
});
