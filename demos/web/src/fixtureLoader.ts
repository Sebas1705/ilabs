import { compareCodeUnits } from "./sort";

/**
 * Pure and total: parses each raw fixture file on its own, skips the invalid ones and names them in `errors`
 * (`<file>: <reason>`). Never throws, whatever the input. `shapeProblem` returns a reason or null and only has
 * to guard the page from crashing; the contract tests do the strict validation.
 * Fixtures are sorted by `name` and errors by text, both by UTF-16 code unit.
 */
export function parseFixtureFiles<T extends { name: string }>(
  files: Record<string, string>,
  shapeProblem: (value: unknown) => string | null,
): { fixtures: T[]; errors: string[] } {
  const fixtures: T[] = [];
  const errors: string[] = [];
  for (const [path, text] of Object.entries(files)) {
    const file = path.slice(path.lastIndexOf("/") + 1);
    try {
      const value: unknown = JSON.parse(text);
      const problem = shapeProblem(value);
      if (problem) errors.push(`${file}: ${problem}`);
      else fixtures.push(value as T);
    } catch (e) {
      errors.push(`${file}: ${e instanceof Error ? e.message : String(e)}`);
    }
  }
  fixtures.sort((a, b) => compareCodeUnits(a.name, b.name));
  errors.sort(compareCodeUnits);
  return { fixtures, errors };
}

/** True for a plain JSON object (not null, not an array). */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** True for `{ role: string, content: string }`: what the pages print for every request message. */
export function isMessageLike(value: unknown): boolean {
  return isRecord(value) && typeof value.role === "string" && typeof value.content === "string";
}

/**
 * True for a provider script entry the pages can print: an object with a string `response`, or an `error`
 * object with a string `kind` and a `retryAfterMs` that is absent, null or a number (or both). Anything
 * else (null, a string, neither key, a non-object `error`) would make the script list throw while rendering.
 */
export function isScriptEntry(value: unknown): boolean {
  if (!isRecord(value)) return false;
  const { response, error } = value;
  if (response === undefined && error === undefined) return false;
  if (response !== undefined && typeof response !== "string") return false;
  if (error === undefined) return true;
  if (!isRecord(error) || typeof error.kind !== "string") return false;
  const retry = error.retryAfterMs;
  return retry === undefined || retry === null || typeof retry === "number";
}

/** True for a non-empty-or-empty array whose every element passes `check`. */
export function isArrayOf(value: unknown, check: (item: unknown) => boolean): value is unknown[] {
  return Array.isArray(value) && value.every(check);
}
