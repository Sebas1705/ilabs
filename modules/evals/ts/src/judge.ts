import type { CompletionRequest, Message } from "@ilabs/providers";
import { trimJson } from "./graders.js";

/** The judge's system instruction (its wording is not pinned by the contract). */
export const JUDGE_INSTRUCTION =
  "You are a strict evaluator. You are given a rubric, the last user message of a conversation and a reply to that conversation. " +
  "Decide whether the reply satisfies the rubric. Answer with exactly one word, PASS or FAIL, optionally followed by a short explanation.";

/** Builds the 2-message judge request: rubric, last user message (empty when none) and reply, in that order. */
export function buildJudgeRequest(rubric: string, messages: Message[], reply: string): CompletionRequest {
  let lastUser = "";
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]!;
    if (m.role === "user") {
      lastUser = m.content;
      break;
    }
  }
  return {
    messages: [
      { role: "system", content: JUDGE_INSTRUCTION },
      { role: "user", content: `Rubric:\n${rubric}\n\nLast user message:\n${lastUser}\n\nReply to evaluate:\n${reply}` },
    ],
  };
}

function isWordChar(c: string | undefined): boolean {
  if (c === undefined) return false;
  const u = c.charCodeAt(0);
  return (u >= 65 && u <= 90) || (u >= 97 && u <= 122) || (u >= 48 && u <= 57) || u === 95;
}

/** `PASS` or `FAIL` when the text (after leading JSON whitespace) starts with it and the next char is not [A-Za-z0-9_]; else null. */
export function parseVerdict(text: string): "PASS" | "FAIL" | null {
  // Trailing whitespace is a non-word character, so trimming both ends cannot change the verdict.
  const body = trimJson(text);
  for (const word of ["PASS", "FAIL"] as const) {
    if (body.startsWith(word) && !isWordChar(body[word.length])) return word;
  }
  return null;
}
