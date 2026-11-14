import type { CompletionRequest, Message, Provider } from "@ilabs/providers";
import { defaultCounter, isSummaryMessage, SUMMARY_PREFIX } from "./tokens.js";
import { requirePositiveInteger, slidingWindow, type WindowOptions, type WindowResult } from "./window.js";

export interface SummarizeOptions extends WindowOptions {
  /** Number of most recent non-system messages that are never summarized (default 4). */
  keepRecent?: number;
}

export interface SummarizeResult extends WindowResult {
  summarized: boolean;
  summaryCalls: 0 | 1;
  summarizerRequest: CompletionRequest | null;
}

const INSTRUCTION =
  "You condense conversations. Summarize the transcript you receive into a short, faithful summary that keeps the facts, decisions, names and open questions needed to continue the conversation. Reply with the summary text only.";

function buildRequest(sent: Message[]): CompletionRequest {
  const transcript = sent.map((m) => `[${m.role}]\n${m.content}`).join("\n\n");
  return { messages: [{ role: "system", content: INSTRUCTION }, { role: "user", content: transcript }] };
}

/**
 * Keeps a conversation inside `maxTokens`: replaces the older turns (and any existing summaries) by ONE new
 * summary produced by `provider`, then windows the result if it is still too big. At most one provider call.
 * Provider errors and `BudgetTooSmallError` propagate unchanged. The input is never mutated.
 */
export async function summarizeContext(provider: Provider, messages: Message[], options: SummarizeOptions): Promise<SummarizeResult> {
  requirePositiveInteger("maxTokens", options.maxTokens);
  const keepRecent = options.keepRecent ?? 4;
  requirePositiveInteger("keepRecent", keepRecent);
  const counter = options.counter ?? defaultCounter;
  const { maxTokens } = options;

  const tokensBefore = messages.reduce((total, m) => total + counter(m), 0);
  if (tokensBefore <= maxTokens) {
    return { messages: [...messages], droppedCount: 0, tokensBefore, tokensAfter: tokensBefore, summarized: false, summaryCalls: 0, summarizerRequest: null };
  }

  const nonSystem: number[] = [];
  messages.forEach((m, i) => {
    if (m.role !== "system") nonSystem.push(i);
  });
  const protectedCount = Math.min(keepRecent, nonSystem.length);
  const removed = new Set<number>(nonSystem.slice(0, nonSystem.length - protectedCount));
  if (removed.size === 0) {
    // Nothing older: existing summaries alone never trigger a call.
    const w = slidingWindow(messages, { maxTokens, counter });
    return { ...w, summarized: false, summaryCalls: 0, summarizerRequest: null };
  }
  messages.forEach((m, i) => {
    if (isSummaryMessage(m)) removed.add(i);
  });

  const sent = messages.filter((_, i) => removed.has(i));
  const request = buildRequest(sent);
  const reply = await provider.complete(request);

  const summary: Message = { role: "system", content: SUMMARY_PREFIX + reply.text };
  const rebuilt: Message[] = [];
  let inserted = false;
  messages.forEach((m, i) => {
    if (!removed.has(i)) rebuilt.push(m);
    else if (!inserted) {
      rebuilt.push(summary);
      inserted = true;
    }
  });

  const final = rebuilt.reduce((total, m) => total + counter(m), 0) <= maxTokens ? rebuilt : slidingWindow(rebuilt, { maxTokens, counter }).messages;
  const tokensAfter = final.reduce((total, m) => total + counter(m), 0);
  return {
    messages: final,
    droppedCount: messages.length - (final.length - 1),
    tokensBefore,
    tokensAfter,
    summarized: true,
    summaryCalls: 1,
    summarizerRequest: request,
  };
}
