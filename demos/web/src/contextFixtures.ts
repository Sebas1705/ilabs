import type { Fixture } from "@ilabs/context";
import type { Key } from "./i18n";
import { isMessageLike, isRecord, parseFixtureFiles } from "./fixtureLoader";

// Loaded as raw text and parsed here: Vite's own JSON loader rejects the lone surrogate escape in
// window-lone-surrogate-cost.json, while JSON.parse (like the Kotlin and test runners) accepts it.
const modules = import.meta.glob("../../../contracts/context/*.json", { eager: true, query: "?raw", import: "default" });

/** Minimal shape check: enough for the page to render and run the fixture without throwing on a missing field. */
function shapeProblem(value: unknown): string | null {
  if (!isRecord(value)) return "not an object";
  if (typeof value.name !== "string") return "missing name";
  const { setup } = value;
  if (!isRecord(setup)) return "missing setup";
  if (setup.kind !== "window" && setup.kind !== "summarize") return "setup.kind must be window or summarize";
  if (typeof setup.maxTokens !== "number") return "setup.maxTokens must be a number";
  if (!Array.isArray(value.messages)) return "missing messages";
  return value.messages.every(isMessageLike) ? null : "messages must be {role, content} objects";
}

/** Pure and total: see `parseFixtureFiles`. */
export function parseFixtures(raw: Record<string, string>): { fixtures: Fixture[]; errors: string[] } {
  return parseFixtureFiles<Fixture>(raw, shapeProblem);
}

const loaded = parseFixtures(modules as Record<string, string>);
export const contextFixtures: Fixture[] = loaded.fixtures;
/** One entry per fixture file that could not be loaded; shown as a warning on the page. */
export const loadErrors: string[] = loaded.errors;

/** Fixture groups, in display order; the prefix is the start of the fixture name. */
const GROUPS: { prefix: string; label: Key }[] = [
  { prefix: "window-", label: "groupWindow" },
  { prefix: "summarize-", label: "groupSummarize" },
];

export interface FixtureGroup {
  label: Key;
  fixtures: Fixture[];
}

/** Splits fixtures by name prefix; anything unrecognised lands in a trailing "other" group. */
export function groupContextFixtures(list: Fixture[]): FixtureGroup[] {
  const groups: FixtureGroup[] = GROUPS.map((g) => ({
    label: g.label,
    fixtures: list.filter((f) => f.name.startsWith(g.prefix)),
  }));
  const rest = list.filter((f) => !GROUPS.some((g) => f.name.startsWith(g.prefix)));
  if (rest.length > 0) groups.push({ label: "groupOther", fixtures: rest });
  return groups.filter((g) => g.fixtures.length > 0);
}
