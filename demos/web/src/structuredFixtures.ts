import type { Fixture } from "@ilabs/structured";
import { compareCodeUnits } from "./sort";
import type { Key } from "./i18n";

const modules = import.meta.glob("../../../contracts/structured/*.json", { eager: true });

export const structuredFixtures: Fixture[] = Object.values(modules)
  .map((m) => (m as { default: Fixture }).default)
  .sort((a, b) => compareCodeUnits(a.name, b.name));

/** Fixture groups, in display order; the prefix is the start of the fixture name. */
const GROUPS: { prefix: string; label: Key }[] = [
  { prefix: "extract-", label: "groupExtract" },
  { prefix: "validate-", label: "groupValidate" },
  { prefix: "loop-", label: "groupLoop" },
];

export interface FixtureGroup {
  label: Key;
  fixtures: Fixture[];
}

/** Splits fixtures by name prefix; anything unrecognised lands in a trailing "other" group. */
export function groupFixtures(list: Fixture[]): FixtureGroup[] {
  const groups: FixtureGroup[] = GROUPS.map((g) => ({
    label: g.label,
    fixtures: list.filter((f) => f.name.startsWith(g.prefix)),
  }));
  const rest = list.filter((f) => !GROUPS.some((g) => f.name.startsWith(g.prefix)));
  if (rest.length > 0) groups.push({ label: "groupOther", fixtures: rest });
  return groups.filter((g) => g.fixtures.length > 0);
}
