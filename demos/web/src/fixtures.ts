import type { Fixture } from "@ilabs/providers";
import { isArrayOf, isMessageLike, isRecord, isScriptEntry, parseFixtureFiles } from "./fixtureLoader";

// Loaded as raw text and parsed per file, so one malformed fixture is reported instead of crashing the app.
const modules = import.meta.glob("../../../contracts/providers/*.json", { eager: true, query: "?raw", import: "default" });

/** Minimal shape check: enough for the page to render and run the fixture without throwing on a missing field. */
function shapeProblem(value: unknown): string | null {
  if (!isRecord(value)) return "not an object";
  if (typeof value.name !== "string") return "missing name";
  if (!isRecord(value.setup)) return "missing setup";
  if (value.setup.kind !== "retry" && value.setup.kind !== "fallback") return "setup.kind must be retry or fallback";
  for (const key of ["maxAttempts", "baseDelayMs", "maxDelayMs"]) {
    if (value.setup[key] !== undefined && typeof value.setup[key] !== "number") return `setup.${key} must be a number`;
  }
  if (!Array.isArray(value.providers)) return "missing providers";
  for (const p of value.providers) {
    if (!isRecord(p)) return "providers must be objects";
    if (typeof p.id !== "string") return "provider id must be a string";
    if (!isArrayOf(p.script, isScriptEntry)) return "provider script must be an array of {response} or {error: {kind}} entries";
  }
  if (!isRecord(value.request) || !isArrayOf(value.request.messages, isMessageLike)) return "request.messages must be {role, content} objects";
  return isRecord(value.expect) ? null : "missing expect";
}

/** Pure and total: see `parseFixtureFiles`. */
export function parseProviderFixtures(raw: Record<string, string>): { fixtures: Fixture[]; errors: string[] } {
  return parseFixtureFiles<Fixture>(raw, shapeProblem);
}

const loaded = parseProviderFixtures(modules as Record<string, string>);
export const fixtures: Fixture[] = loaded.fixtures;
/** One entry per fixture file that could not be loaded; shown as a warning on the page. */
export const loadErrors: string[] = loaded.errors;
