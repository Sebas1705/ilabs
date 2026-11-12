export type IssueCode =
  | "type"
  | "enum"
  | "minimum"
  | "maximum"
  | "minLength"
  | "maxLength"
  | "minItems"
  | "maxItems"
  | "required"
  | "additionalProperties";

export interface Issue {
  path: string;
  code: IssueCode | "no_json" | "invalid_json";
}

export type SchemaType = "object" | "array" | "string" | "number" | "integer" | "boolean" | "null";

export type Schema = {
  type?: SchemaType;
  enum?: unknown[];
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  properties?: Record<string, Schema>;
  required?: string[];
  additionalProperties?: boolean;
  items?: Schema;
};

export class InvalidSchemaError extends Error {
  constructor(reason: string) {
    super(`invalid schema: ${reason}`);
    this.name = "InvalidSchemaError";
  }
}

const TYPES = ["object", "array", "string", "number", "integer", "boolean", "null"];
const NUMERIC_KEYWORDS = ["minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems"] as const;

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function check(schema: unknown, at: string): void {
  if (!isPlainObject(schema)) throw new InvalidSchemaError(`${at || "schema"} must be an object`);
  const where = at === "" ? "" : ` at ${at}`;
  if ("type" in schema && (typeof schema.type !== "string" || !TYPES.includes(schema.type))) {
    throw new InvalidSchemaError(`unknown type${where}`);
  }
  if ("enum" in schema && (!Array.isArray(schema.enum) || schema.enum.length === 0)) {
    throw new InvalidSchemaError(`enum must be a non-empty array${where}`);
  }
  for (const k of NUMERIC_KEYWORDS) {
    if (k in schema && (typeof schema[k] !== "number" || !Number.isFinite(schema[k]))) {
      throw new InvalidSchemaError(`${k} must be a number${where}`);
    }
  }
  if ("required" in schema && (!Array.isArray(schema.required) || schema.required.some((r) => typeof r !== "string"))) {
    throw new InvalidSchemaError(`required must be an array of strings${where}`);
  }
  if ("additionalProperties" in schema && typeof schema.additionalProperties !== "boolean") {
    throw new InvalidSchemaError(`additionalProperties must be a boolean${where}`);
  }
  if ("properties" in schema) {
    const props = schema.properties;
    if (!isPlainObject(props)) throw new InvalidSchemaError(`properties must be an object${where}`);
    for (const name of Object.keys(props)) check(props[name], `${at}/properties/${name}`);
  }
  if ("items" in schema) check(schema.items, `${at}/items`);
}

/** Throws `InvalidSchemaError` (`invalid schema: <reason>`) when the schema is malformed. Unknown keywords are ignored. */
export function checkSchema(schema: unknown): asserts schema is Schema {
  check(schema, "");
}

function escapePointer(name: string): string {
  return name.replaceAll("~", "~0").replaceAll("/", "~1");
}

/** JSON deep equality: numbers by value, objects without key order, no coercion between kinds. */
export function jsonEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a)) {
    return Array.isArray(b) && a.length === b.length && a.every((x, i) => jsonEqual(x, b[i]));
  }
  if (isPlainObject(a)) {
    if (!isPlainObject(b)) return false;
    const ka = Object.keys(a);
    if (ka.length !== Object.keys(b).length) return false;
    return ka.every((k) => Object.hasOwn(b, k) && jsonEqual(a[k], b[k]));
  }
  return false;
}

function typeMatches(type: SchemaType, v: unknown): boolean {
  switch (type) {
    case "object":
      return isPlainObject(v);
    case "array":
      return Array.isArray(v);
    case "string":
      return typeof v === "string";
    case "number":
      return typeof v === "number";
    case "integer":
      return typeof v === "number" && Number.isInteger(v);
    case "boolean":
      return typeof v === "boolean";
    case "null":
      return v === null;
  }
}

function walk(schema: Schema, value: unknown, path: string, out: Issue[]): void {
  const push = (code: IssueCode, at = path) => out.push({ path: at, code });
  if (schema.type !== undefined && !typeMatches(schema.type, value)) {
    push("type");
    return;
  }
  if (schema.enum !== undefined && !schema.enum.some((e) => jsonEqual(e, value))) push("enum");

  if (typeof value === "number") {
    if (schema.minimum !== undefined && value < schema.minimum) push("minimum");
    if (schema.maximum !== undefined && value > schema.maximum) push("maximum");
  } else if (typeof value === "string") {
    const len = [...value].length;
    if (schema.minLength !== undefined && len < schema.minLength) push("minLength");
    if (schema.maxLength !== undefined && len > schema.maxLength) push("maxLength");
  } else if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) push("minItems");
    if (schema.maxItems !== undefined && value.length > schema.maxItems) push("maxItems");
    const items = schema.items;
    if (items !== undefined) value.forEach((item, i) => walk(items, item, `${path}/${i}`, out));
  } else if (isPlainObject(value)) {
    for (const name of schema.required ?? []) {
      if (!Object.hasOwn(value, name)) push("required", `${path}/${escapePointer(name)}`);
    }
    const props = schema.properties ?? {};
    if (schema.additionalProperties === false) {
      for (const name of Object.keys(value).sort()) {
        if (!Object.hasOwn(props, name)) push("additionalProperties", `${path}/${escapePointer(name)}`);
      }
    }
    for (const name of Object.keys(props).sort()) {
      const sub = props[name];
      if (sub !== undefined && Object.hasOwn(value, name)) walk(sub, value[name], `${path}/${escapePointer(name)}`, out);
    }
  }
}

/** Validates a parsed JSON value; issues are in the contract order. */
export function validate(schema: Schema, value: unknown): Issue[] {
  const out: Issue[] = [];
  walk(schema, value, "", out);
  return out;
}
