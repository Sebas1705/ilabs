import type { CaseResult } from "@ilabs/evals";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { LangContext } from "../i18n";
import { ResultMatrix } from "./ResultMatrix";
import { StatusBadge } from "./StatusBadge";

const r = (caseId: string, targetId: string, status: CaseResult["status"]): CaseResult => ({ caseId, targetId, status, graders: [] });

function mount(selected: { caseId: string; targetId: string } | null, onSelect = vi.fn()) {
  render(
    <LangContext.Provider value="en">
      <ResultMatrix
        caseIds={["c1", "c2"]}
        targetIds={["a", "b"]}
        results={[r("c1", "a", "pass"), r("c1", "b", "fail"), r("c2", "a", "error"), r("c2", "b", "pass")]}
        selected={selected}
        onSelect={onSelect}
      />
    </LangContext.Provider>,
  );
  return onSelect;
}

describe("StatusBadge", () => {
  it.each([
    ["pass", "✓", "Pass"],
    ["fail", "✕", "Fail"],
    ["error", "!", "Error"],
  ] as const)("shows a glyph and the word for %s", (status, glyph, word) => {
    const { container } = render(
      <LangContext.Provider value="en">
        <StatusBadge status={status} />
      </LangContext.Provider>,
    );
    expect(container.querySelector('[aria-hidden="true"]')).toHaveTextContent(glyph);
    expect(container).toHaveTextContent(word);
  });
});

describe("ResultMatrix", () => {
  it("has one row per case, one column per target and a named button per cell", () => {
    mount(null);
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Case", "a", "b"]);
    expect(within(table).getAllByRole("rowheader").map((h) => h.textContent)).toEqual(["c1", "c2"]);
    expect(within(table).getAllByRole("button").map((b) => b.getAttribute("aria-label"))).toEqual([
      "c1 × a: Pass",
      "c1 × b: Fail",
      "c2 × a: Error",
      "c2 × b: Pass",
    ]);
  });

  it("marks the selected cell and reports clicks", async () => {
    const onSelect = mount({ caseId: "c1", targetId: "b" });
    expect(screen.getByRole("button", { name: "c1 × b: Fail" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "c1 × a: Pass" })).toHaveAttribute("aria-pressed", "false");
    await userEvent.setup().click(screen.getByRole("button", { name: "c2 × a: Error" }));
    expect(onSelect).toHaveBeenCalledWith({ caseId: "c2", targetId: "a" });
  });

  it("names the matrix once: the scroll region, not the table as well", () => {
    mount(null);
    expect(screen.getAllByLabelText("Results matrix")).toHaveLength(1);
    expect(screen.getByRole("table")).not.toHaveAccessibleName();
  });

  it("is scrollable by keyboard inside a named region so the page never scrolls sideways", () => {
    mount(null);
    const scroller = screen.getByRole("region", { name: "Results matrix" });
    expect(scroller).toHaveAttribute("tabindex", "0");
    expect(scroller).toContainElement(screen.getByRole("table"));
  });
});
