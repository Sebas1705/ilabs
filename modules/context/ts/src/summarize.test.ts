import { MockProvider, ProviderError, type CompletionRequest, type Message, type Provider } from "@ilabs/providers";
import { describe, expect, it } from "vitest";
import { SUMMARY_PREFIX } from "./tokens.js";
import { summarizeContext } from "./summarize.js";
import { BudgetTooSmallError } from "./window.js";

const m = (role: Message["role"], content: string): Message => ({ role, content });
const convo = (): Message[] => [
  m("system", "Be brief."),
  m("user", "User turn 1"),
  m("assistant", "Bot turn 1"),
  m("user", "User turn 2"),
  m("assistant", "Bot turn 2"),
  m("user", "User turn 3"),
  m("assistant", "Bot turn 3"),
];

describe("summarizeContext", () => {
  it("returns the input unchanged without a call when it fits", async () => {
    const p = new MockProvider("a", []);
    const r = await summarizeContext(p, convo(), { maxTokens: 100 });
    expect(r).toMatchObject({ messages: convo(), droppedCount: 0, summarized: false, summaryCalls: 0, summarizerRequest: null });
  });

  it("replaces older messages with one summary and exposes the request", async () => {
    const p = new MockProvider("a", [{ response: "They said hello." }]);
    const r = await summarizeContext(p, convo(), { maxTokens: 40, keepRecent: 2 });
    expect(r.messages).toEqual([m("system", "Be brief."), m("system", SUMMARY_PREFIX + "They said hello."), m("user", "User turn 3"), m("assistant", "Bot turn 3")]);
    expect(r).toMatchObject({ summarized: true, summaryCalls: 1, droppedCount: 4, tokensBefore: 49, tokensAfter: 39 });
    expect(r.summarizerRequest?.messages.map((x) => x.role)).toEqual(["system", "user"]);
  });

  it("applies a custom counter to the fits check and both totals", async () => {
    // Summary messages cost 1; every other message costs its length.
    const counter = (msg: Message) => (msg.content.startsWith(SUMMARY_PREFIX) ? 1 : msg.content.length);
    const input = [m("user", "aaaa"), m("assistant", "bbbb"), m("user", "cc"), m("assistant", "dd")];
    const fits = await summarizeContext(new MockProvider("a", []), input, { maxTokens: 12, keepRecent: 2, counter });
    expect(fits).toMatchObject({ summarized: false, summaryCalls: 0, tokensBefore: 12, tokensAfter: 12 });
    const r = await summarizeContext(new MockProvider("a", [{ response: "s" }]), input, { maxTokens: 8, keepRecent: 2, counter });
    expect(r).toMatchObject({ summarized: true, droppedCount: 2, tokensBefore: 12, tokensAfter: 5 });
  });

  it("does not mutate the input array or its messages", async () => {
    const input = convo();
    const snapshot = JSON.stringify(input);
    const p = new MockProvider("a", [{ response: "x" }]);
    await summarizeContext(p, input, { maxTokens: 40, keepRecent: 2 });
    expect(JSON.stringify(input)).toBe(snapshot);
    const fits = await summarizeContext(p, input, { maxTokens: 1000 });
    expect(fits.messages).not.toBe(input);
  });

  it("propagates provider errors unchanged", async () => {
    const p = new MockProvider("a", [{ error: { kind: "rate_limit" } }]);
    const err = await summarizeContext(p, convo(), { maxTokens: 40, keepRecent: 2 }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect((err as ProviderError).kind).toBe("rate_limit");
  });

  it("propagates an exhausted script as unavailable", async () => {
    const err = await summarizeContext(new MockProvider("a", []), convo(), { maxTokens: 40, keepRecent: 2 }).catch((e: unknown) => e);
    expect((err as ProviderError).kind).toBe("unavailable");
  });

  it("rethrows a non-provider exception unchanged", async () => {
    const boom = new TypeError("boom");
    const p: Provider = { id: "x", complete: () => Promise.reject(boom) };
    await expect(summarizeContext(p, convo(), { maxTokens: 40, keepRecent: 2 })).rejects.toBe(boom);
  });

  it("makes no call when nothing is older, and fails with budget_too_small", async () => {
    const seen: CompletionRequest[] = [];
    const p: Provider = { id: "x", complete: (req) => (seen.push(req), Promise.reject(new Error("no"))) };
    await expect(summarizeContext(p, [m("system", "Be brief."), m("user", "User turn 1")], { maxTokens: 10, keepRecent: 4 })).rejects.toBeInstanceOf(BudgetTooSmallError);
    expect(seen).toHaveLength(0);
  });

  it("makes exactly one call before failing with budget_too_small", async () => {
    const seen: CompletionRequest[] = [];
    const p: Provider = { id: "x", complete: (req) => (seen.push(req), Promise.resolve({ text: "x", provider: "x" })) };
    await expect(summarizeContext(p, convo(), { maxTokens: 20, keepRecent: 2 })).rejects.toBeInstanceOf(BudgetTooSmallError);
    expect(seen).toHaveLength(1);
  });

  it.each([0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY])("rejects maxTokens %s with RangeError", async (maxTokens) => {
    await expect(summarizeContext(new MockProvider("a", []), [], { maxTokens })).rejects.toBeInstanceOf(RangeError);
  });

  it.each([0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY])("rejects keepRecent %s with RangeError", async (keepRecent) => {
    await expect(summarizeContext(new MockProvider("a", []), [], { maxTokens: 10, keepRecent })).rejects.toBeInstanceOf(RangeError);
  });
});
