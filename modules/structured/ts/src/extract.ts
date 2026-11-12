export type Extraction = { ok: true; value: unknown } | { ok: false; code: "no_json" | "invalid_json" };

const MAX_DEPTH = 128;
const FENCE = "```";

function isJsonWhitespace(c: string | undefined): boolean {
  return c === " " || c === "\t" || c === "\n" || c === "\r";
}

/** Trims JSON whitespace only (not String.prototype.trim). */
function trimJson(s: string): string {
  let start = 0;
  let end = s.length;
  while (start < end && isJsonWhitespace(s[start])) start++;
  while (end > start && isJsonWhitespace(s[end - 1])) end--;
  return s.slice(start, end);
}

/** Maximum number of simultaneously open arrays/objects outside string literals. Iterative. */
function maxDepth(text: string): number {
  let depth = 0;
  let max = 0;
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      if (c === "\\") i++;
      else if (c === '"') inString = false;
    } else if (c === '"') inString = true;
    else if (c === "{" || c === "[") {
      depth++;
      if (depth > max) max = depth;
    } else if (c === "}" || c === "]") depth--;
  }
  return max;
}

function tryParse(candidate: string): { ok: true; value: unknown } | undefined {
  // An empty (or JSON-whitespace-only) candidate can never parse; skip the costly throwing JSON.parse.
  if (trimJson(candidate).length === 0) return undefined;
  if (maxDepth(candidate) > MAX_DEPTH) return undefined;
  try {
    return { ok: true, value: JSON.parse(candidate) };
  } catch {
    return undefined;
  }
}

function fencedBlocks(text: string): string[] {
  const lines = text.split("\n");
  const blocks: string[] = [];
  let i = 0;
  while (i < lines.length) {
    if (!(lines[i] as string).startsWith(FENCE)) {
      i++;
      continue;
    }
    let close = -1;
    for (let j = i + 1; j < lines.length; j++) {
      let line = lines[j] as string;
      let end = line.length;
      while (end > 0 && isJsonWhitespace(line[end - 1])) end--;
      line = line.slice(0, end);
      if (line === FENCE) {
        close = j;
        break;
      }
    }
    if (close < 0) break;
    blocks.push(lines.slice(i + 1, close).join("\n"));
    i = close + 1;
  }
  return blocks;
}

/** From the first `{` or `[` to its balanced close, string-literal aware; undefined when unbalanced. */
function balancedRegion(text: string): string | undefined {
  let start = -1;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === "{" || text[i] === "[") {
      start = i;
      break;
    }
  }
  if (start < 0) return undefined;
  let depth = 0;
  let inString = false;
  for (let i = start; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      if (c === "\\") i++;
      else if (c === '"') inString = false;
    } else if (c === '"') inString = true;
    else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return undefined;
}

/** Finds the first strict-JSON candidate: whole text, fenced blocks, then the first balanced region. */
export function extractJson(text: string): Extraction {
  const whole = tryParse(trimJson(text));
  if (whole) return whole;
  for (const block of fencedBlocks(text)) {
    const hit = tryParse(block);
    if (hit) return hit;
  }
  const region = balancedRegion(text);
  if (region !== undefined) {
    const hit = tryParse(region);
    if (hit) return hit;
  }
  const hasMarker = text.includes("{") || text.includes("[") || text.includes(FENCE);
  return { ok: false, code: hasMarker ? "invalid_json" : "no_json" };
}
