import type { CompletionRequest, Message, Provider } from "@ilabs/providers";
import { extractJson, type Extraction } from "./extract.js";
import { checkSchema, validate, type Issue, type Schema } from "./schema.js";

export interface AttemptTrace {
  reply: string;
  extracted: Extraction;
  issues: Issue[];
}

export interface StructuredOptions {
  /** Maximum number of repair calls after the first one. Default 2; must be a non-negative integer. */
  maxRepairs?: number;
}

export interface StructuredResult {
  value: unknown;
  attempts: number;
  trace: AttemptTrace[];
}

export class StructuredError extends Error {
  readonly kind = "invalid_output" as const;

  constructor(
    readonly issues: Issue[],
    readonly attempts: number,
    readonly trace: AttemptTrace[],
  ) {
    super(`invalid_output after ${attempts} attempt(s)`);
    this.name = "StructuredError";
  }
}

function repairMessage(issues: Issue[]): string {
  const list = issues.map((i) => `${i.path === "" ? "(root)" : i.path}: ${i.code}`).join("; ");
  return `Your previous reply was not valid. Problems: ${list}. Reply again with only the corrected JSON.`;
}

/** Asks the provider for JSON matching the schema, repairing up to `maxRepairs` times. */
export async function structured(
  provider: Provider,
  request: CompletionRequest,
  schema: Schema,
  options: StructuredOptions = {},
): Promise<StructuredResult> {
  const maxRepairs = options.maxRepairs ?? 2;
  if (!(Number.isInteger(maxRepairs) && maxRepairs >= 0)) {
    throw new RangeError(`maxRepairs must be a non-negative integer, got ${maxRepairs}`);
  }
  checkSchema(schema);

  const messages: Message[] = [...request.messages];
  const trace: AttemptTrace[] = [];
  let attempts = 0;
  for (;;) {
    attempts++;
    const { text } = await provider.complete({ ...request, messages: [...messages] });
    const extracted = extractJson(text);
    const issues: Issue[] = extracted.ok ? validate(schema, extracted.value) : [{ path: "", code: extracted.code }];
    trace.push({ reply: text, extracted, issues });
    if (extracted.ok && issues.length === 0) return { value: extracted.value, attempts, trace };
    if (attempts > maxRepairs) throw new StructuredError(issues, attempts, trace);
    messages.push({ role: "assistant", content: text }, { role: "user", content: repairMessage(issues) });
  }
}
