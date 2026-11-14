import { extractJson, jsonEqual, validate } from "@ilabs/structured";
import type { GraderResult, PureGrader } from "./types.js";

function isJsonWhitespace(c: string | undefined): boolean {
  return c === " " || c === "\t" || c === "\n" || c === "\r";
}

/** Removes space, tab, LF and CR from both ends (not `String.prototype.trim`). */
export function trimJson(s: string): string {
  let start = 0;
  let end = s.length;
  while (start < end && isJsonWhitespace(s[start])) start++;
  while (end > start && isJsonWhitespace(s[end - 1])) end--;
  return s.slice(start, end);
}

/** Lower-cases A-Z only (not `toLowerCase`, which folds more). */
export function foldAscii(s: string): string {
  let out = "";
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    out += c >= 65 && c <= 90 ? String.fromCharCode(c + 32) : s[i];
  }
  return out;
}

/** Evaluates one grader other than `judge` on the reply exactly as returned. */
export function evaluateGrader(grader: PureGrader, reply: string): GraderResult {
  switch (grader.kind) {
    case "equals":
    case "contains":
    case "notContains":
    case "startsWith":
    case "endsWith":
    case "oneOf": {
      const prep = (s: string): string => {
        let r = grader.trim === true ? trimJson(s) : s;
        if (grader.ignoreCase === true) r = foldAscii(r);
        return r;
      };
      const actual = prep(reply);
      let passed: boolean;
      switch (grader.kind) {
        case "equals":
          passed = actual === prep(grader.value);
          break;
        case "contains":
          passed = actual.includes(prep(grader.value));
          break;
        case "notContains":
          passed = !actual.includes(prep(grader.value));
          break;
        case "startsWith":
          passed = actual.startsWith(prep(grader.value));
          break;
        case "endsWith":
          passed = actual.endsWith(prep(grader.value));
          break;
        case "oneOf":
          passed = grader.values.some((v) => actual === prep(v));
          break;
      }
      return { kind: grader.kind, passed };
    }
    case "length": {
      const n = [...reply].length;
      const passed = (grader.min === undefined || n >= grader.min) && (grader.max === undefined || n <= grader.max);
      return { kind: "length", passed };
    }
    case "json": {
      const ex = extractJson(reply);
      if (!ex.ok) return { kind: "json", passed: false, issues: [{ path: "", code: ex.code }] };
      const issues = validate(grader.schema, ex.value);
      return { kind: "json", passed: issues.length === 0, issues };
    }
    case "jsonEquals": {
      const ex = extractJson(reply);
      if (!ex.ok) return { kind: "jsonEquals", passed: false, issues: [{ path: "", code: ex.code }] };
      return { kind: "jsonEquals", passed: jsonEqual(ex.value, grader.value), issues: [] };
    }
  }
}
