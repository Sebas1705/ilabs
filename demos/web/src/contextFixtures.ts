import type { Fixture } from "@ilabs/context";
import type { Key } from "./i18n";
import { compareCodeUnits } from "./sort";

// Loaded as raw text and parsed here: Vite's own JSON loader rejects the lone surrogate escape in
// window-lone-surrogate-cost.json, while JSON.parse (like the Kotlin and test runners) accepts it.
const modules = import.meta.glob("../../../contracts/context/*.json", { eager: true, query: "?raw", import: "default" });

/** Minimal shape check: enough for the page to render and run the fixture without throwing on a missing field. */
function shapeProblem(value: unknown): string | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return "not an object";
  const f = value as Record<string, unknown>;
  if (typeof f.name !== "string") return "missing name";
  const setup = f.setup as Record<string, unknown> | null | undefined;
  if (typeof setup !== "object" || setup === null) return "missing setup";
  if (setup.kind !== "window" && setup.kind !== "summarize") return "setup.kind must be window or summarize";
  if (typeof setup.maxTokens !== "number") return "setup.maxTokens must be a number";
  if (!Array.isArray(f.messages)) return "missing messages";
  const badMessage = f.messages.some(
    (m: unknown) => typeof m !== "object" || m === null || typeof (m as { role?: unknown }).role !== "string" || typeof (m as { content?: unknown }).content !== "string",
  );
  return badMessage ? "messages must be {role, content} objects" : null;
}

/** Pure and total: parses each raw fixture on its own, skips the invalid ones and names them in `errors`. Never throws. */
export function parseFixtures(raw: Record<string, string>): { fixtures: Fixture[]; errors: string[] } {
  const fixtures: Fixture[] = [];
  const errors: string[] = [];
  for (const [path, text] of Object.entries(raw)) {
    const file = path.slice(path.lastIndexOf("/") + 1);
    try {
      const value: unknown = JSON.parse(text);
      const problem = shapeProblem(value);
      if (problem) errors.push(`${file}: ${problem}`);
      else fixtures.push(value as Fixture);
    } catch (e) {
      errors.push(`${file}: ${e instanceof Error ? e.message : String(e)}`);
    }
  }
  fixtures.sort((a, b) => compareCodeUnits(a.name, b.name));
  errors.sort(compareCodeUnits);
  return { fixtures, errors };
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
