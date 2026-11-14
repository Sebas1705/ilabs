import type { Message } from "@ilabs/providers";
import { describe, expect, it } from "vitest";
import { BudgetTooSmallError, slidingWindow } from "./window.js";

const m = (role: Message["role"], content: string): Message => ({ role, content });
const convo = (): Message[] => [m("system", "Be brief."), m("user", "User turn 1"), m("assistant", "Bot turn 1"), m("user", "User turn 2")];

describe("slidingWindow", () => {
  it("returns the input unchanged when it fits", () => {
    const r = slidingWindow(convo(), { maxTokens: 28 });
    expect(r).toEqual({ messages: convo(), droppedCount: 0, tokensBefore: 28, tokensAfter: 28 });
  });

  it("drops the oldest non-system messages", () => {
    const r = slidingWindow(convo(), { maxTokens: 20 });
    expect(r.messages).toEqual([m("system", "Be brief."), m("user", "User turn 2")]);
    expect(r).toMatchObject({ droppedCount: 2, tokensBefore: 28, tokensAfter: 14 });
  });

  it("fails with budget_too_small and required", () => {
    let caught: unknown;
    try {
      slidingWindow(convo(), { maxTokens: 13 });
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(BudgetTooSmallError);
    expect(caught).toBeInstanceOf(Error);
    expect((caught as BudgetTooSmallError).kind).toBe("budget_too_small");
    expect((caught as BudgetTooSmallError).required).toBe(14);
  });

  it("requires the cost of S when there is no non-system message", () => {
    try {
      slidingWindow([m("system", "Be brief."), m("system", "Use English.")], { maxTokens: 10 });
      expect.unreachable();
    } catch (e) {
      expect((e as BudgetTooSmallError).required).toBe(14);
    }
  });

  it("does not mutate the input array or its messages", () => {
    const input = convo();
    const snapshot = JSON.stringify(input);
    const first = input[0];
    const r = slidingWindow(input, { maxTokens: 20 });
    expect(JSON.stringify(input)).toBe(snapshot);
    expect(input[0]).toBe(first);
    expect(r.messages).not.toBe(input);
    const fits = slidingWindow(input, { maxTokens: 100 });
    expect(fits.messages).not.toBe(input);
    expect(fits.messages).toEqual(input);
  });

  it("applies a custom counter to fits, totals and windowing", () => {
    const counter = (msg: Message) => msg.content.length;
    const input = [m("system", "ab"), m("user", "cccc"), m("assistant", "dd"), m("user", "e")];
    const r = slidingWindow(input, { maxTokens: 6, counter });
    expect(r.messages).toEqual([m("system", "ab"), m("assistant", "dd"), m("user", "e")]);
    expect(r).toMatchObject({ droppedCount: 1, tokensBefore: 9, tokensAfter: 5 });
    expect(() => slidingWindow(input, { maxTokens: 2, counter })).toThrow(BudgetTooSmallError);
  });

  it.each([0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY])("rejects maxTokens %s with RangeError", (maxTokens) => {
    expect(() => slidingWindow([], { maxTokens })).toThrow(RangeError);
  });
});
