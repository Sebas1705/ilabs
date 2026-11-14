import { describe, expect, it } from "vitest";
import { defaultCounter, estimateTokens, isSummaryMessage, SUMMARY_PREFIX } from "./tokens.js";

describe("estimateTokens", () => {
  it("is 0 for the empty string", () => expect(estimateTokens("")).toBe(0));
  it("rounds code points / 4 up", () => {
    expect(estimateTokens("abcd")).toBe(1);
    expect(estimateTokens("abcde")).toBe(2);
    expect(estimateTokens("abcdefgh")).toBe(2);
    expect(estimateTokens("abcdefghi")).toBe(3);
  });
  it("counts an astral emoji as one code point", () => {
    expect(estimateTokens("😀😁😂🤣😃")).toBe(2);
    expect(estimateTokens("😀😁😂🤣")).toBe(1);
  });
  it("counts a lone surrogate as one code point", () => {
    expect(estimateTokens("\uD800")).toBe(1);
    expect(estimateTokens("\uDC00𐀀\uD800")).toBe(1);
    expect(estimateTokens("\uD800\uD800\uD800\uD800\uD800")).toBe(2);
  });
});

describe("defaultCounter", () => {
  it("adds 4 of framing overhead", () => {
    expect(defaultCounter({ role: "user", content: "" })).toBe(4);
    expect(defaultCounter({ role: "assistant", content: "Okay" })).toBe(5);
  });
});

describe("isSummaryMessage", () => {
  it("has the 37 code point prefix ending in LF", () => {
    expect([...SUMMARY_PREFIX]).toHaveLength(37);
    expect(SUMMARY_PREFIX.endsWith("\n")).toBe(true);
  });
  it("needs role system and the exact prefix", () => {
    expect(isSummaryMessage({ role: "system", content: SUMMARY_PREFIX })).toBe(true);
    expect(isSummaryMessage({ role: "system", content: SUMMARY_PREFIX + "x" })).toBe(true);
    expect(isSummaryMessage({ role: "user", content: SUMMARY_PREFIX })).toBe(false);
    expect(isSummaryMessage({ role: "assistant", content: SUMMARY_PREFIX })).toBe(false);
    expect(isSummaryMessage({ role: "system", content: "Summary of the earlier conversation: x" })).toBe(false);
    expect(isSummaryMessage({ role: "system", content: "summary of the earlier conversation:\n" })).toBe(false);
    expect(isSummaryMessage({ role: "system", content: " " + SUMMARY_PREFIX })).toBe(false);
  });
});
