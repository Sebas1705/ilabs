import { describe, expect, it } from "vitest";
import { buildJudgeRequest, parseVerdict } from "./judge.js";

describe("parseVerdict", () => {
  it("accepts PASS and FAIL followed by the end or a non-word ASCII character", () => {
    expect(parseVerdict("PASS")).toBe("PASS");
    expect(parseVerdict("FAIL")).toBe("FAIL");
    expect(parseVerdict("PASS.")).toBe("PASS");
    expect(parseVerdict("PASS: ok")).toBe("PASS");
    expect(parseVerdict("FAIL: too curt")).toBe("FAIL");
    expect(parseVerdict(" \n\t\r PASS")).toBe("PASS");
    expect(parseVerdict("PASS\nbecause")).toBe("PASS");
    expect(parseVerdict("PASSé")).toBe("PASS");
    expect(parseVerdict("FAIL-1")).toBe("FAIL");
  });

  it("rejects everything else", () => {
    for (const text of ["", "   ", "pass", "fail", "Pass", "PASSED", "Passing", "PASS1", "PASS_x", "FAIL_x", "FAILED", "Verdict: PASS", " PASS", "\u000bPASS", "﻿PASS", "PAS"]) {
      expect(parseVerdict(text), JSON.stringify(text)).toBeNull();
    }
  });
});

describe("buildJudgeRequest", () => {
  const messages = [
    { role: "user" as const, content: "First" },
    { role: "assistant" as const, content: "Mid" },
    { role: "user" as const, content: "Second question" },
    { role: "assistant" as const, content: "Tail" },
  ];

  it("has exactly 2 messages and only the messages field", () => {
    const req = buildJudgeRequest("Is it polite?", messages, "Hi there");
    expect(Object.keys(req)).toEqual(["messages"]);
    expect(req.messages.map((m) => m.role)).toEqual(["system", "user"]);
    expect(Object.keys(req.messages[0]!).sort()).toEqual(["content", "role"]);
    expect(req.messages[0]!.content.length).toBeGreaterThan(0);
  });

  it("puts the rubric, the last user message and the reply in order", () => {
    const user = buildJudgeRequest("Is it polite?", messages, "Hi there").messages[1]!.content;
    const a = user.indexOf("Is it polite?");
    const b = user.indexOf("Second question", a + 1);
    const c = user.indexOf("Hi there", b + 1);
    expect(a).toBeGreaterThanOrEqual(0);
    expect(b).toBeGreaterThan(a);
    expect(c).toBeGreaterThan(b);
    expect(user).not.toContain("First");
    expect(user).not.toContain("Tail");
  });

  it("uses the empty string when the case has no user message", () => {
    const withNone = buildJudgeRequest("RUBRIC", [{ role: "system", content: "S" }], "REPLY");
    const empty = buildJudgeRequest("RUBRIC", [], "REPLY");
    expect(withNone.messages[1]!.content).toBe(empty.messages[1]!.content);
    const user = withNone.messages[1]!.content;
    expect(user.indexOf("REPLY")).toBeGreaterThan(user.indexOf("RUBRIC"));
    expect(user.length).toBeGreaterThan(0);
  });
});
