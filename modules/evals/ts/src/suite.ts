import type { Message } from "@ilabs/providers";
import { checkSchema } from "@ilabs/structured";
import type { EvalCase, Grader, Suite } from "./types.js";

/** Thrown for every invalid suite: `invalid suite: <reason>`. */
export class InvalidSuiteError extends Error {
  constructor(reason: string) {
    super(`invalid suite: ${reason}`);
    this.name = "InvalidSuiteError";
  }
}

const fail = (reason: string): never => {
  throw new InvalidSuiteError(reason);
};

const ROLES = ["system", "user", "assistant"];
const STRING_KINDS = ["equals", "contains", "notContains", "startsWith", "endsWith"];

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function noUnknownKeys(o: Record<string, unknown>, allowed: string[], where: string, strict: boolean): void {
  if (!strict) return;
  for (const k of Object.keys(o)) {
    if (!allowed.includes(k)) fail(`unknown key "${k}" in ${where}`);
  }
}

function optionalBoolean(o: Record<string, unknown>, key: string, where: string): boolean | undefined {
  if (!Object.hasOwn(o, key)) return undefined;
  const v = o[key];
  if (typeof v !== "boolean") fail(`${where}: ${key} must be a boolean`);
  return v as boolean;
}

function bound(o: Record<string, unknown>, key: string, where: string): number | undefined {
  if (!Object.hasOwn(o, key)) return undefined;
  const v = o[key];
  if (typeof v !== "number" || !Number.isInteger(v) || v < 0) fail(`${where}: ${key} must be an integer >= 0`);
  return v as number;
}

function checkMessage(m: unknown, where: string, strict: boolean): Message {
  if (!isObject(m)) return fail(`${where} must be an object`);
  noUnknownKeys(m, ["role", "content"], where, strict);
  if (typeof m["role"] !== "string" || !ROLES.includes(m["role"])) fail(`${where}: role must be system, user or assistant`);
  if (typeof m["content"] !== "string") fail(`${where}: content must be a string`);
  return { role: m["role"] as Message["role"], content: m["content"] as string };
}

function checkGrader(g: unknown, where: string, strict: boolean): Grader {
  if (!isObject(g)) return fail(`${where} must be an object`);
  const kind = g["kind"];
  if (typeof kind !== "string") return fail(`${where}: kind must be a string`);
  if (STRING_KINDS.includes(kind)) {
    noUnknownKeys(g, ["kind", "value", "trim", "ignoreCase"], where, strict);
    if (typeof g["value"] !== "string") fail(`${where}: value must be a string`);
    const out: Record<string, unknown> = { kind, value: g["value"] };
    const trim = optionalBoolean(g, "trim", where);
    const ignoreCase = optionalBoolean(g, "ignoreCase", where);
    if (trim !== undefined) out["trim"] = trim;
    if (ignoreCase !== undefined) out["ignoreCase"] = ignoreCase;
    return out as unknown as Grader;
  }
  switch (kind) {
    case "oneOf": {
      noUnknownKeys(g, ["kind", "values", "trim", "ignoreCase"], where, strict);
      const values = g["values"];
      if (!Array.isArray(values) || values.length === 0 || values.some((v) => typeof v !== "string")) {
        fail(`${where}: values must be a non-empty array of strings`);
      }
      const out: Record<string, unknown> = { kind, values: [...(values as string[])] };
      const trim = optionalBoolean(g, "trim", where);
      const ignoreCase = optionalBoolean(g, "ignoreCase", where);
      if (trim !== undefined) out["trim"] = trim;
      if (ignoreCase !== undefined) out["ignoreCase"] = ignoreCase;
      return out as unknown as Grader;
    }
    case "length": {
      noUnknownKeys(g, ["kind", "min", "max"], where, strict);
      const min = bound(g, "min", where);
      const max = bound(g, "max", where);
      if (min === undefined && max === undefined) fail(`${where}: length needs min or max`);
      if (min !== undefined && max !== undefined && min > max) fail(`${where}: min must be <= max`);
      const out: Record<string, unknown> = { kind };
      if (min !== undefined) out["min"] = min;
      if (max !== undefined) out["max"] = max;
      return out as unknown as Grader;
    }
    case "json": {
      noUnknownKeys(g, ["kind", "schema"], where, strict);
      const schema = g["schema"];
      if (!isObject(schema)) fail(`${where}: schema must be an object`);
      try {
        checkSchema(schema);
      } catch (e) {
        fail(`${where}: ${e instanceof Error ? e.message : String(e)}`);
      }
      return { kind: "json", schema: schema as never };
    }
    case "jsonEquals": {
      noUnknownKeys(g, ["kind", "value"], where, strict);
      if (!Object.hasOwn(g, "value") || g["value"] === undefined) fail(`${where}: value is required`);
      return { kind: "jsonEquals", value: g["value"] };
    }
    case "judge": {
      noUnknownKeys(g, ["kind", "rubric"], where, strict);
      if (typeof g["rubric"] !== "string" || g["rubric"].length === 0) fail(`${where}: rubric must be a non-empty string`);
      return { kind: "judge", rubric: g["rubric"] as string };
    }
    default:
      return fail(`${where}: unknown kind ${JSON.stringify(kind)}`);
  }
}

function checkCase(c: unknown, i: number, strict: boolean): EvalCase {
  const where = `cases[${i}]`;
  if (!isObject(c)) return fail(`${where} must be an object`);
  noUnknownKeys(c, ["id", "messages", "graders"], where, strict);
  if (typeof c["id"] !== "string" || c["id"].length === 0) fail(`${where}: id must be a non-empty string`);
  if (!Array.isArray(c["messages"])) fail(`${where}: messages must be an array`);
  if (!Array.isArray(c["graders"]) || c["graders"].length === 0) fail(`${where}: graders must be a non-empty array`);
  return {
    id: c["id"] as string,
    messages: (c["messages"] as unknown[]).map((m, j) => checkMessage(m, `${where}.messages[${j}]`, strict)),
    graders: (c["graders"] as unknown[]).map((g, j) => checkGrader(g, `${where}.graders[${j}]`, strict)),
  };
}

/**
 * Validates everything about a suite that does not involve the targets and the judge and returns a typed copy.
 * `strict` adds the parse-time rule: no unknown key anywhere except inside `json.schema` and `jsonEquals.value`.
 */
export function validateSuite(json: unknown, strict: boolean): Suite {
  if (!isObject(json)) return fail("the suite must be an object");
  noUnknownKeys(json, ["cases"], "the suite", strict);
  const cases = json["cases"];
  if (!Array.isArray(cases) || cases.length === 0) return fail("cases must be a non-empty array");
  const out = cases.map((c, i) => checkCase(c, i, strict));
  const seen = new Set<string>();
  for (const c of out) {
    if (seen.has(c.id)) fail(`duplicate case id ${JSON.stringify(c.id)}`);
    seen.add(c.id);
  }
  return { cases: out };
}

/** Parses a suite from a JSON value or a JSON text (strict) and validates it. Throws `invalid suite: <reason>`. */
export function parseSuite(json: unknown): Suite {
  let value = json;
  if (typeof json === "string") {
    try {
      value = JSON.parse(json);
    } catch (e) {
      return fail(`not valid JSON (${e instanceof Error ? e.message : String(e)})`);
    }
  }
  return validateSuite(value, true);
}
