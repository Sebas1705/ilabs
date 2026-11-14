import { InvalidSuiteError, parseSuite, type PureGrader } from "@ilabs/evals";
import type { Key } from "./i18n";

export const PURE_KINDS = ["equals", "contains", "notContains", "startsWith", "endsWith", "oneOf", "length", "json", "jsonEquals"] as const;
export type PureKind = (typeof PURE_KINDS)[number];

/** What the playground form holds: raw text for every field, so half-typed input never throws. */
export interface GraderParams {
  kind: PureKind;
  value: string;
  /** One value per line, for `oneOf`. */
  values: string;
  min: string;
  max: string;
  /** Raw JSON text of the schema. */
  schema: string;
  /** Raw JSON text of the expected value. */
  jsonValue: string;
  trim: boolean;
  ignoreCase: boolean;
}

export const DEFAULT_PARAMS: GraderParams = {
  kind: "equals",
  value: "hello",
  values: "yes\nno",
  min: "",
  max: "",
  schema: '{\n  "type": "object",\n  "required": ["name"],\n  "properties": { "name": { "type": "string" } }\n}',
  jsonValue: '{ "ok": true }',
  trim: false,
  ignoreCase: false,
};

/** Which form fields each grader kind reads. */
export const USES: Record<PureKind, { value?: true; values?: true; bounds?: true; schema?: true; jsonValue?: true; options?: true }> = {
  equals: { value: true, options: true },
  contains: { value: true, options: true },
  notContains: { value: true, options: true },
  startsWith: { value: true, options: true },
  endsWith: { value: true, options: true },
  oneOf: { values: true, options: true },
  length: { bounds: true },
  json: { schema: true },
  jsonEquals: { jsonValue: true },
};

export type BuildResult = { ok: true; grader: PureGrader } | { ok: false; error: Key; detail?: string };

const fail = (error: Key, detail?: string): BuildResult => (detail === undefined ? { ok: false, error } : { ok: false, error, detail });

/** Text to JSON value, or `{ ok: false }` when the text is not JSON (empty text included). */
function parseJson(text: string): { ok: true; value: unknown } | { ok: false } {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch {
    return { ok: false };
  }
}

/** Empty (or blank) text leaves the bound out; anything else becomes the number as typed, for the library to judge. */
const bound = (text: string): number | undefined => (text.trim() === "" ? undefined : Number(text));

/**
 * Turns the form into a grader or a parameter error (a dictionary key, plus the library's reason when it
 * has one). Pure and total.
 *
 * Only text-to-value conversion happens here; every rule about what a valid grader is comes from the library:
 * the raw grader object goes through a one-case `parseSuite`, the same code path the fixtures use.
 * - `oneOf`: every line of the text is one value, so empty text is the single value `""` (the library
 *   accepts it); there is no rule of our own.
 * - `length`: an empty bound is left out, anything else is passed as `Number(text)`, so `abc` (NaN), `-1`,
 *   `1.5` or `5`/`2` are rejected by the library, not by this file.
 * - Text that is not JSON (`json` schema, `jsonEquals` value, including empty text) never reaches the library.
 */
export function buildGrader(p: GraderParams): BuildResult {
  let raw: Record<string, unknown>;
  switch (p.kind) {
    case "equals":
    case "contains":
    case "notContains":
    case "startsWith":
    case "endsWith":
      raw = { kind: p.kind, value: p.value, trim: p.trim, ignoreCase: p.ignoreCase };
      break;
    case "oneOf":
      raw = { kind: "oneOf", values: p.values.split(/\r?\n/), trim: p.trim, ignoreCase: p.ignoreCase };
      break;
    case "length": {
      raw = { kind: "length" };
      const min = bound(p.min);
      const max = bound(p.max);
      if (min !== undefined) raw["min"] = min;
      if (max !== undefined) raw["max"] = max;
      break;
    }
    case "json": {
      const parsed = parseJson(p.schema);
      if (!parsed.ok) return fail("pErrJsonInvalid");
      raw = { kind: "json", schema: parsed.value };
      break;
    }
    case "jsonEquals": {
      const parsed = parseJson(p.jsonValue);
      if (!parsed.ok) return fail("pErrJsonInvalid");
      raw = { kind: "jsonEquals", value: parsed.value };
      break;
    }
  }
  try {
    const grader = parseSuite({ cases: [{ id: "playground", messages: [], graders: [raw] }] }).cases[0]?.graders[0];
    if (grader !== undefined && grader.kind !== "judge") return { ok: true, grader };
    return fail("pErrParams");
  } catch (e) {
    if (e instanceof InvalidSuiteError) return fail(p.kind === "json" ? "pErrSchema" : "pErrParams", e.message);
    throw e;
  }
}
