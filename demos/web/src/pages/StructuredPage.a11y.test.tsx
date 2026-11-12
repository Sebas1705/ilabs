import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LangContext, type Lang } from "../i18n";
import { StructuredPage } from "./StructuredPage";

function mount(lang: Lang) {
  return render(
    <LangContext.Provider value={lang}>
      <StructuredPage />
    </LangContext.Provider>,
  );
}

async function pick(name: string, label: string) {
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText(label), name);
}

function focusableBlocks() {
  return Array.from(document.querySelectorAll("pre[tabindex]"));
}

describe("scrollable code blocks are named regions", () => {
  it.each([
    ["en", "Fixture", ["Schema JSON", "Extracted JSON value (Attempt 1)", "Extracted JSON value (Attempt 2)", "Final value JSON"]],
    ["es", "Escenario", ["JSON del esquema", "Valor JSON extraído (Intento 1)", "Valor JSON extraído (Intento 2)", "Valor final en JSON"]],
  ] as const)("names every focusable <pre> in %s", async (lang, fixtureLabel, names) => {
    mount(lang);
    await pick("loop-repair-second", fixtureLabel);
    await screen.findByRole("region", { name: lang === "en" ? "Result" : "Resultado" });
    const blocks = focusableBlocks();
    expect(blocks).toHaveLength(names.length);
    for (const b of blocks) {
      expect(b).toHaveAttribute("role", "region");
      expect(b.getAttribute("aria-label")?.trim()).toBeTruthy();
    }
    expect(blocks.map((b) => b.getAttribute("aria-label"))).toEqual(names);
    for (const n of names) expect(screen.getByRole("region", { name: n })).toBeInTheDocument();
  });

  it("gives every focusable block a distinct name", async () => {
    mount("en");
    await pick("loop-repair-second", "Fixture");
    await screen.findByRole("region", { name: "Result" });
    const labels = focusableBlocks().map((b) => b.getAttribute("aria-label"));
    expect(new Set(labels).size).toBe(labels.length);
  });
});
