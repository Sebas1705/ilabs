import type { Fixture } from "@ilabs/structured";
import { isArrayOf, isMessageLike, isRecord, isScriptEntry, parseFixtureFiles } from "./fixtureLoader";
import type { Key } from "./i18n";

// Loaded as raw text and parsed per file, so one malformed fixture is reported instead of crashing the app.
const modules = import.meta.glob("../../../contracts/structured/*.json", { eager: true, query: "?raw", import: "default" });

/** Minimal shape check: enough for the page to render and run the fixture without throwing on a missing field. */
function shapeProblem(value: unknown): string | null {
  if (!isRecord(value)) return "not an object";
  if (typeof value.name !== "string") return "missing name";
  if (!isRecord(value.schema)) return "missing schema";
  if (!isRecord(value.request) || !isArrayOf(value.request.messages, isMessageLike)) return "request.messages must be {role, content} objects";
  if (!isRecord(value.setup)) return "missing setup";
  if (typeof value.setup.maxRepairs !== "number") return "setup.maxRepairs must be a number";
  if (!isRecord(value.provider)) return "missing provider";
  if (typeof value.provider.id !== "string") return "provider.id must be a string";
  if (!isArrayOf(value.provider.script, isScriptEntry)) return "provider.script must be an array of {response} or {error: {kind}} entries";
  return isRecord(value.expect) ? null : "missing expect";
}

/** Pure and total: see `parseFixtureFiles`. */
export function parseStructuredFixtures(raw: Record<string, string>): { fixtures: Fixture[]; errors: string[] } {
  return parseFixtureFiles<Fixture>(raw, shapeProblem);
}

const loaded = parseStructuredFixtures(modules as Record<string, string>);
export const structuredFixtures: Fixture[] = loaded.fixtures;
/** One entry per fixture file that could not be loaded; shown as a warning on the page. */
export const loadErrors: string[] = loaded.errors;

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
