import type { Fixture } from "@ilabs/evals";
import { isArrayOf, isRecord, isScriptEntry, parseFixtureFiles } from "./fixtureLoader";
import type { Key } from "./i18n";

// Loaded as raw text and parsed per file, so one malformed fixture is reported instead of crashing the app.
const modules = import.meta.glob("../../../contracts/evals/*.json", { eager: true, query: "?raw", import: "default" });

const isTarget = (value: unknown): boolean => isRecord(value) && typeof value.id === "string" && isArrayOf(value.script, isScriptEntry);

/**
 * Minimal shape check: enough for the page to run and render the fixture without throwing. `suite` is only
 * required to be present, because the `invalid-*` fixtures make it malformed on purpose (null, an array...).
 */
function shapeProblem(value: unknown): string | null {
  if (!isRecord(value)) return "not an object";
  if (typeof value.name !== "string") return "missing name";
  if (!("suite" in value)) return "missing suite";
  if (!isArrayOf(value.targets, isTarget)) return "targets must be {id, script} objects with provider script entries";
  if (value.judge !== undefined && !(isRecord(value.judge) && isArrayOf(value.judge.script, isScriptEntry))) {
    return "judge must be an object with a provider script";
  }
  return isRecord(value.expect) ? null : "missing expect";
}

/** Pure and total: see `parseFixtureFiles`. */
export function parseEvalsFixtures(raw: Record<string, string>): { fixtures: Fixture[]; errors: string[] } {
  return parseFixtureFiles<Fixture>(raw, shapeProblem);
}

const loaded = parseEvalsFixtures(modules as Record<string, string>);
export const evalsFixtures: Fixture[] = loaded.fixtures;
/** One entry per fixture file that could not be loaded; shown as a warning on the page. */
export const loadErrors: string[] = loaded.errors;

/** Fixture groups, in display order; a fixture belongs to a group when its name starts with one of the prefixes. */
const GROUPS: { prefixes: string[]; label: Key }[] = [
  { prefixes: ["status-", "provider-", "summary-", "multi-", "calls-", "no-", "empty-"], label: "groupRun" },
  { prefixes: ["equals-", "contains-", "notcontains-", "startswith-", "endswith-", "oneof-", "string-"], label: "groupStrings" },
  { prefixes: ["length-"], label: "groupLength" },
  { prefixes: ["json-", "jsonequals-"], label: "groupJson" },
  { prefixes: ["judge-"], label: "groupJudge" },
  { prefixes: ["invalid-"], label: "groupInvalid" },
];

export interface FixtureGroup {
  label: Key;
  fixtures: Fixture[];
}

const inGroup = (f: Fixture, prefixes: string[]): boolean => prefixes.some((p) => f.name.startsWith(p));

/** Splits fixtures by name prefix; anything unrecognised lands in a trailing "other" group. */
export function groupEvalsFixtures(list: Fixture[]): FixtureGroup[] {
  const groups: FixtureGroup[] = GROUPS.map((g) => ({ label: g.label, fixtures: list.filter((f) => inGroup(f, g.prefixes)) }));
  const rest = list.filter((f) => !GROUPS.some((g) => inGroup(f, g.prefixes)));
  if (rest.length > 0) groups.push({ label: "groupOther", fixtures: rest });
  return groups.filter((g) => g.fixtures.length > 0);
}
