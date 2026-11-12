import type { Role } from "@ilabs/providers";
import type { AttemptTrace } from "@ilabs/structured";
import { render } from "@testing-library/react";
import { AttemptTimeline } from "./AttemptTimeline";
import { LangContext, type Lang } from "../i18n";

const attempt = (reply: string): AttemptTrace => ({
  reply,
  extracted: { ok: false, code: "no_json" },
  issues: [{ path: "", code: "no_json" }],
});

function mount(appended: Role[], lang: Lang = "en", count = 3) {
  const trace = Array.from({ length: count }, (_, i) => attempt(`reply ${i + 1}`));
  return render(
    <LangContext.Provider value={lang}>
      <AttemptTimeline trace={trace} appended={appended} />
    </LangContext.Provider>,
  );
}

function dividers() {
  return document.querySelectorAll(".repair");
}

describe("AttemptTimeline repair divider", () => {
  it("derives each divider's roles from the roles appended at that step", () => {
    mount(["assistant", "user", "system", "assistant"]);
    const found = dividers();
    expect(found).toHaveLength(2);
    expect(found[0]).toHaveTextContent("assistant, user");
    expect(found[1]).toHaveTextContent("system, assistant");
  });

  it("translates the role labels", () => {
    mount(["assistant", "user", "assistant", "user"], "es");
    for (const d of dividers()) expect(d).toHaveTextContent("asistente, usuario");
    expect(document.body).not.toHaveTextContent(/\b(assistant|user)\b/);
  });

  it("shows no role words when nothing was appended for that step", () => {
    mount([], "en", 2);
    expect(dividers()).toHaveLength(1);
    expect(dividers()[0]).not.toHaveTextContent(/assistant|user|system/);
    expect(dividers()[0]).toHaveTextContent("Repair requested");
  });
});
