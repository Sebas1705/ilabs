import type { Message } from "@ilabs/providers";
import { defaultCounter, type TokenCounter } from "./tokens.js";

export interface WindowOptions {
  maxTokens: number;
  counter?: TokenCounter;
}

export interface WindowResult {
  messages: Message[];
  droppedCount: number;
  tokensBefore: number;
  tokensAfter: number;
}

/** Thrown when even the system messages plus the last non-system message exceed the budget. */
export class BudgetTooSmallError extends Error {
  readonly kind = "budget_too_small" as const;
  readonly required: number;

  constructor(required: number) {
    super(`budget_too_small: at least ${required} tokens are required`);
    this.name = "BudgetTooSmallError";
    this.required = required;
  }
}

/** @internal Throws `RangeError` unless `value` is an integer >= 1. */
export function requirePositiveInteger(name: string, value: number): void {
  if (!Number.isInteger(value) || value < 1) throw new RangeError(`${name} must be an integer >= 1, got ${value}`);
}

const sum = (messages: Message[], counter: TokenCounter): number => messages.reduce((total, message) => total + counter(message), 0);

/**
 * Keeps a conversation inside `maxTokens` by dropping the oldest non-system messages.
 * System messages are never dropped or moved. Pure: the input is never mutated.
 */
export function slidingWindow(messages: Message[], options: WindowOptions): WindowResult {
  requirePositiveInteger("maxTokens", options.maxTokens);
  const counter = options.counter ?? defaultCounter;
  const { maxTokens } = options;

  const tokensBefore = sum(messages, counter);
  if (tokensBefore <= maxTokens) return { messages: [...messages], droppedCount: 0, tokensBefore, tokensAfter: tokensBefore };

  const system = messages.filter((m) => m.role === "system");
  const systemCost = sum(system, counter);
  const nonSystemIndexes: number[] = [];
  messages.forEach((m, i) => {
    if (m.role !== "system") nonSystemIndexes.push(i);
  });
  const lastIndex = nonSystemIndexes[nonSystemIndexes.length - 1];
  if (lastIndex === undefined) throw new BudgetTooSmallError(systemCost);
  const required = systemCost + counter(messages[lastIndex]!);
  if (required > maxTokens) throw new BudgetTooSmallError(required);

  // Longest contiguous suffix of the non-system messages, stopping at the first one that does not fit.
  const kept = new Set<number>();
  let used = systemCost;
  for (let k = nonSystemIndexes.length - 1; k >= 0; k--) {
    const index = nonSystemIndexes[k]!;
    const cost = counter(messages[index]!);
    if (used + cost > maxTokens) break;
    used += cost;
    kept.add(index);
  }
  const result = messages.filter((m, i) => m.role === "system" || kept.has(i));
  return { messages: result, droppedCount: messages.length - result.length, tokensBefore, tokensAfter: used };
}
