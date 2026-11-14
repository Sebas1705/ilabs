import { defaultCounter, isSummaryMessage } from "@ilabs/context";
import type { Message } from "@ilabs/providers";

export type RowState = "kept" | "dropped" | "replaced" | "summary" | "none";

export interface Row {
  key: string;
  message: Message;
  state: RowState;
  tokens: number;
}

export const totalTokens = (messages: Message[]): number => messages.reduce((n, m) => n + defaultCounter(m), 0);

/**
 * Lines the original conversation up with the messages that survived. Messages are matched by identity
 * (the library keeps the objects it keeps). When a summary was made, the original messages that were
 * folded into it are "replaced" and the new summary is inserted where the first of them stood.
 * `survivors === null` means the run produced no result: every row is "none".
 */
export function buildRows(original: Message[], survivors: Message[] | null, summarized: boolean, keepRecent: number): Row[] {
  const toRow = (message: Message, i: number, state: RowState): Row => ({ key: `m${i}`, message, state, tokens: defaultCounter(message) });
  if (survivors === null) return original.map((m, i) => toRow(m, i, "none"));

  const kept = new Set(survivors);
  const folded = new Set<number>();
  const summary = summarized ? survivors.find((m) => !original.includes(m)) : undefined;
  if (summarized) {
    const nonSystem: number[] = [];
    original.forEach((m, i) => {
      if (m.role !== "system") nonSystem.push(i);
    });
    const protectedCount = Math.min(keepRecent, nonSystem.length);
    nonSystem.slice(0, nonSystem.length - protectedCount).forEach((i) => folded.add(i));
    original.forEach((m, i) => {
      if (isSummaryMessage(m)) folded.add(i);
    });
  }

  const rows: Row[] = [];
  let inserted = false;
  original.forEach((m, i) => {
    if (summary && folded.has(i) && !inserted) {
      inserted = true;
      rows.push({ key: "summary", message: summary, state: "summary", tokens: defaultCounter(summary) });
    }
    rows.push(toRow(m, i, kept.has(m) ? "kept" : folded.has(i) ? "replaced" : "dropped"));
  });
  return rows;
}
