import { describe, expect, it } from "vitest";
import { FallbackProvider, MockProvider, ProviderError } from "./index.js";

describe("MockProvider", () => {
  it("returns normally for an empty messages array", async () => {
    const p = new MockProvider("a", [{ response: "ok" }]);
    expect(await p.complete({ messages: [] })).toEqual({ text: "ok", provider: "a" });
  });

  it("fails with unavailable once the script is exhausted", async () => {
    const p = new MockProvider("a", [{ response: "ok" }]);
    await p.complete({ messages: [] });
    await expect(p.complete({ messages: [] })).rejects.toMatchObject({ kind: "unavailable" });
    await expect(p.complete({ messages: [] })).rejects.toBeInstanceOf(ProviderError);
  });
});

describe("FallbackProvider", () => {
  it("fails with unavailable for an empty list", async () => {
    await expect(new FallbackProvider([]).complete({ messages: [] })).rejects.toMatchObject({
      kind: "unavailable",
    });
  });
});
