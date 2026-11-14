import type { Message } from "@ilabs/providers";

/**
 * Cost in tokens of one message. Must return a NON-NEGATIVE INTEGER: non-integers and NaN are undefined behavior (garbage in, garbage out).
 */
export type TokenCounter = (message: Message) => number;

/** Exact prefix (37 code points, ending with LF) that marks a summary message. */
export const SUMMARY_PREFIX = "Summary of the earlier conversation:\n";

/** `ceil(codePoints / 4)`; code points, not UTF-16 units (a lone surrogate counts as one). */
export function estimateTokens(text: string): number {
  return Math.ceil([...text].length / 4);
}

/** Default counter: 4 tokens of framing plus the estimate for the content. */
export const defaultCounter: TokenCounter = (message) => 4 + estimateTokens(message.content);

/** A summary message is a `system` message whose content starts with the exact prefix. */
export function isSummaryMessage(message: Message): boolean {
  return message.role === "system" && message.content.startsWith(SUMMARY_PREFIX);
}
