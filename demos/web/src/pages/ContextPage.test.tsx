import { readdirSync } from "node:fs";
import { resolve } from "node:path";
import * as context from "@ilabs/context";
import { defaultCounter, slidingWindow } from "@ilabs/context";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { App } from "../App";
import { contextFixtures } from "../contextFixtures";
import { LangContext, type Lang } from "../i18n";
import { ContextPage } from "./ContextPage";

vi.mock("@ilabs/context", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@ilabs/context")>();
  return { ...actual, runFixture: vi.fn(actual.runFixture), slidingWindow: vi.fn(actual.slidingWindow) };
});

function mount(lang: Lang = "en") {
  return render(
    <LangContext.Provider value={lang}>
      <ContextPage />
    </LangContext.Provider>,
  );
}

async function pick(name: string) {
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText(/^(Fixture|Escenario)$/), name);
  return user;
}

function fixtureByName(name: string) {
  const f = contextFixtures.find((x) => x.name === name);
  if (!f) throw new Error(`missing fixture ${name}`);
  return f;
}

function slider0() {
  return screen.getByRole("slider", { name: "Token budget" });
}

function rows() {
  return within(screen.getByRole("list", { name: /^(Conversation|Conversación)$/ })).getAllByRole("listitem");
}

function states() {
  return rows().map((r) => r.getAttribute("data-state"));
}

function total(name: string, label: string): string {
  const region = screen.getByRole("region", { name });
  return within(region).getByText(label).nextElementSibling?.textContent ?? "";
}

const fixtureCount = readdirSync(resolve(__dirname, "../../../../contracts/context")).filter((f) => f.endsWith(".json")).length;

describe("ContextPage", () => {
  it("is reachable from the nav through the registry", async () => {
    window.location.hash = "";
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("link", { name: "Context" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Context window" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Context" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Providers" })).not.toHaveAttribute("aria-current");
    expect(window.location.hash).toBe("#/context");
  });

  it("reaches every fixture, grouped by prefix, with localized labels", () => {
    mount();
    const selector = screen.getByLabelText("Fixture");
    expect(fixtureCount).toBeGreaterThanOrEqual(48);
    expect(contextFixtures).toHaveLength(fixtureCount);
    expect(within(selector).getAllByRole("option")).toHaveLength(fixtureCount);
    const groups = within(selector).getAllByRole("group");
    expect(groups.map((g) => g.getAttribute("label"))).toEqual(["Sliding window", "Summarize"]);
    expect(within(groups[0]!).getAllByRole("option").every((o) => o.textContent?.startsWith("window-"))).toBe(true);
    expect(within(groups[1]!).getAllByRole("option").every((o) => o.textContent?.startsWith("summarize-"))).toBe(true);
  });

  it("sorts fixtures by code unit", () => {
    const names = contextFixtures.map((f) => f.name);
    expect(names).toEqual([...names].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)));
  });

  it("moves the slider and re-runs the window exactly as slidingWindow computes", async () => {
    mount();
    await pick("window-drops-oldest");
    const f = fixtureByName("window-drops-oldest");
    const slider = screen.getByRole("slider", { name: "Token budget" });
    const sum = f.messages.reduce((n, m) => n + defaultCounter(m), 0);
    expect(slider).toHaveAttribute("min", "1");
    expect(slider).toHaveAttribute("max", String(sum));
    expect(slider).toHaveAttribute("step", "1");
    expect(slider).not.toBeDisabled();

    // Fixture's own budget (hard-coded from the contract): 42 before, 28 after, 2 dropped.
    expect(slider).toHaveValue("30");
    expect(slider).toHaveAttribute("aria-valuetext", "tokens: 30");
    expect(total("Totals", "Tokens before")).toBe("42");
    expect(total("Totals", "Tokens after")).toBe("28");
    expect(total("Totals", "Dropped messages")).toBe("2");
    expect(states()).toEqual(["kept", "dropped", "dropped", "kept", "kept", "kept"]);

    for (const budget of [10, 20, 28, 42]) {
      fireEvent.change(slider, { target: { value: String(budget) } });
      let expected;
      try {
        expected = slidingWindow(f.messages, { maxTokens: budget });
      } catch {
        expected = null;
      }
      if (expected === null) {
        expect(screen.getByRole("status")).toHaveTextContent(/Budget too small \(needs \d+\)/);
        continue;
      }
      const kept = new Set(expected.messages);
      expect(states()).toEqual(f.messages.map((m) => (kept.has(m) ? "kept" : "dropped")));
      expect(total("Totals", "Tokens before")).toBe(String(expected.tokensBefore));
      expect(total("Totals", "Tokens after")).toBe(String(expected.tokensAfter));
      expect(total("Totals", "Dropped messages")).toBe(String(expected.droppedCount));
    }
    fireEvent.change(slider, { target: { value: "42" } });
    expect(total("Totals", "Dropped messages")).toBe("0");
    expect(states().every((s) => s === "kept")).toBe(true);
  });

  it("shows the role, content as text, a token cost and a decorative bar per message", async () => {
    mount();
    await pick("window-drops-oldest");
    const first = rows()[0]!;
    expect(first).toHaveTextContent("system");
    expect(first).toHaveTextContent("Be brief.");
    expect(first).toHaveTextContent(`Tokens: ${defaultCounter({ role: "system", content: "Be brief." })}`);
    expect(first.querySelector('[aria-hidden="true"].tokenbar')).not.toBeNull();
    expect(first).toHaveTextContent("Kept");
    expect(rows()[1]).toHaveTextContent("Dropped");
    expect(screen.getByRole("list", { name: "Conversation" }).querySelector("script, img")).toBeNull();
  });

  it("shows the needs-N state instead of clamping when the budget is below the requirement", async () => {
    mount();
    await pick("window-last-too-big");
    const slider = screen.getByRole("slider", { name: "Token budget" });
    expect(slider).toHaveValue("16");
    expect(screen.getByRole("status")).toHaveTextContent("Budget too small (needs 17)");
    expect(screen.queryByRole("region", { name: "Totals" })).toBeNull();
    fireEvent.change(slider, { target: { value: "17" } });
    expect(screen.getByRole("status")).not.toHaveTextContent("too small");
    expect(states().at(-1)).toBe("kept");
    expect(total("Totals", "Dropped messages")).toBe("2");
    fireEvent.change(slider, { target: { value: "5" } });
    expect(screen.getByRole("status")).toHaveTextContent("Budget too small (needs 17)");
  });

  it("shows the summarizer request, the summary, the totals and a disabled slider for summarize-one-summary", async () => {
    mount();
    await pick("summarize-one-summary");
    const request = await screen.findByRole("region", { name: "Summarizer request" });
    expect(request).toHaveTextContent("User turn 1");
    expect(request).toHaveTextContent("Bot turn 2");
    expect(within(request).getAllByRole("listitem")).toHaveLength(2);
    const summary = screen.getByRole("region", { name: "Summary message" });
    expect(summary).toHaveTextContent("Summary of the earlier conversation:");
    expect(summary).toHaveTextContent("They said hello.");
    expect(total("Totals", "Tokens before")).toBe("49");
    expect(total("Totals", "Tokens after")).toBe("39");
    expect(total("Totals", "Dropped messages")).toBe("4");
    expect(within(screen.getByRole("region", { name: "Provider calls" })).getByTestId("calls-count")).toHaveTextContent("1");
    expect(slider0()).toHaveAttribute("aria-valuetext", "tokens: 40 (fixed by the fixture)");
    const slider = screen.getByRole("slider", { name: "Token budget" });
    expect(slider).toBeDisabled();
    expect(slider).toHaveValue("40");
    expect(screen.getByText(/fixed by the fixture/i)).toBeInTheDocument();
    expect(states()).toEqual(["kept", "summary", "replaced", "replaced", "replaced", "replaced", "kept", "kept"]);
  });

  it.each(["summarize-provider-error", "summarize-script-exhausted", "summarize-budget-too-small-after-call"])(
    "shows the request and 'Provider calls 1' for the failed run %s",
    async (fixtureName) => {
      mount();
      await pick(fixtureName);
      const request = await screen.findByRole("region", { name: "Summarizer request" });
      expect(request).toBeInTheDocument();
      const calls = screen.getByRole("region", { name: "Provider calls" });
      expect(within(calls).getByTestId("calls-count")).toHaveTextContent("1");
      expect(within(calls).queryByText(/No call was made/)).not.toBeInTheDocument();
    },
  );

  it("says no call was made when the summarizer was never called (summarize-fits-no-call), in both languages", async () => {
    const en = mount();
    await pick("summarize-fits-no-call");
    const calls = await screen.findByRole("region", { name: "Provider calls" });
    expect(within(calls).getByTestId("calls-count")).toHaveTextContent("0");
    expect(within(calls).getByText("No call was made to the summarizer provider.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Summarizer request" })).not.toBeInTheDocument();
    en.unmount();
    mount("es");
    await pick("summarize-fits-no-call");
    const llamadas = await screen.findByRole("region", { name: "Llamadas al proveedor" });
    expect(within(llamadas).getByText("No se hizo ninguna llamada al proveedor de resumen.")).toBeInTheDocument();
  });

  it("reads each role label once (no 'system (system)' duplication) in English", async () => {
    mount("en");
    await pick("summarize-one-summary");
    const first = rows()[0]!;
    expect(first).toHaveTextContent(/^system/);
    expect(first.textContent).not.toMatch(/system\s*\(system\)/i);
    expect(within(first).getAllByText("system")).toHaveLength(1);
  });

  it("links the disabled slider to its explanatory note", async () => {
    mount();
    await pick("summarize-one-summary");
    const slider = slider0();
    expect(slider).toBeDisabled();
    const note = document.getElementById(slider.getAttribute("aria-describedby") ?? "");
    expect(note).toHaveTextContent(/fixed by the fixture/i);
  });

  it("shows an explicit empty state for empty list fixtures", async () => {
    mount();
    await pick("window-empty-list");
    expect(screen.getByText("This conversation has no messages.")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Conversation" })).toBeNull();
    await pick("summarize-empty-list");
    await waitFor(() => expect(screen.getByRole("main")).not.toHaveAttribute("aria-busy", "true"));
    expect(screen.getByText("This conversation has no messages.")).toBeInTheDocument();
  });

  it("renders an error state when the runner rejects and recovers on another fixture", async () => {
    vi.mocked(context.runFixture).mockRejectedValueOnce(new Error("invalid fixture: boom"));
    mount();
    await pick("summarize-one-summary");
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not run the fixture");
    expect(alert).toHaveTextContent("invalid fixture: boom");
    await pick("summarize-incremental");
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
    expect(await screen.findByRole("region", { name: "Summary message" })).toBeInTheDocument();
  });

  it("shows an expected summarize failure as a result, not as an error", async () => {
    mount();
    await pick("summarize-call-then-budget-too-small-protected-heavy");
    await waitFor(() => expect(screen.getByRole("main")).not.toHaveAttribute("aria-busy", "true"));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("status")).toHaveTextContent(/Budget too small \(needs \d+\)/);
    expect(screen.getByRole("region", { name: "Summarizer request" })).toBeInTheDocument();
  });

  it("shows a translated, non-blocking warning for fixtures that failed to load", async () => {
    const mod = await import("../contextFixtures");
    mod.loadErrors.push("broken.json: invalid JSON");
    try {
      const { unmount } = mount();
      const note = screen.getByTestId("load-errors");
      expect(note).toHaveTextContent("Fixtures skipped because they could not be loaded:");
      expect(note).toHaveTextContent("broken.json");
      expect(screen.getByLabelText("Fixture")).toBeInTheDocument();
      unmount();
      mount("es");
      expect(screen.getByTestId("load-errors")).toHaveTextContent("Escenarios omitidos por no poder cargarse:");
    } finally {
      mod.loadErrors.length = 0;
    }
  });

  it("keeps the live region constant while the budget stays in the same coarse state", async () => {
    mount();
    await pick("window-drops-oldest");
    const status = screen.getByRole("status");
    const before = status.textContent;
    expect(before).toBe("Budget is enough");
    const slider = screen.getByRole("slider", { name: "Token budget" });
    for (const budget of [29, 28, 27, 21, 42, 35]) {
      fireEvent.change(slider, { target: { value: String(budget) } });
      expect(screen.getByRole("status").textContent).toBe(before);
    }
    // The per-tick figures live in the totals, which is not a live region.
    expect(screen.getByRole("region", { name: "Totals" }).closest("[aria-live], [role=status], [role=alert]")).toBeNull();
    expect(within(screen.getByRole("region", { name: "Totals" })).queryByRole("status")).toBeNull();
  });

  it("changes the live region only when crossing into too small", async () => {
    mount();
    await pick("window-last-too-big");
    const slider = screen.getByRole("slider", { name: "Token budget" });
    expect(screen.getByRole("status").textContent).toBe("Budget too small (needs 17)");
    fireEvent.change(slider, { target: { value: "17" } });
    const ok = screen.getByRole("status").textContent;
    expect(ok).toBe("Budget is enough");
    for (const budget of [18, 20, 25]) {
      fireEvent.change(slider, { target: { value: String(budget) } });
      expect(screen.getByRole("status").textContent).toBe(ok);
    }
    fireEvent.change(slider, { target: { value: "3" } });
    expect(screen.getByRole("status").textContent).toBe("Budget too small (needs 17)");
    fireEvent.change(slider, { target: { value: "9" } });
    expect(screen.getByRole("status").textContent).toBe("Budget too small (needs 17)");
  });

  it("links the disabled slider to its explanatory note", async () => {
    mount();
    await pick("summarize-one-summary");
    const slider = screen.getByRole("slider", { name: "Token budget" });
    expect(slider).toHaveAccessibleDescription(/fixed by the fixture/i);
  });

  it("shows the lone-surrogate cost the contract expects", async () => {
    mount();
    await pick("window-lone-surrogate-cost");
    const f = fixtureByName("window-lone-surrogate-cost");
    const expected = (f.expect.result as { tokensBefore: number }).tokensBefore;
    expect(expected).toBe(6);
    expect(rows()[0]).toHaveTextContent(`Tokens: ${expected}`);
    expect(total("Totals", "Tokens before")).toBe(String(expected));
  });

  it("turns an unexpected error in the window run into the error state and recovers", async () => {
    const actual = await vi.importActual<typeof import("@ilabs/context")>("@ilabs/context");
    vi.mocked(context.slidingWindow).mockImplementation(() => {
      throw new Error("unexpected boom");
    });
    mount();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("unexpected boom");
    expect(screen.queryByRole("region", { name: "Totals" })).toBeNull();
    vi.mocked(context.slidingWindow).mockImplementation(actual.slidingWindow);
    await pick("window-fits-unchanged");
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("region", { name: "Totals" })).toBeInTheDocument();
  });

  it("renders in Spanish", async () => {
    mount("es");
    const groups = within(screen.getByLabelText("Escenario")).getAllByRole("group");
    expect(groups.map((g) => g.getAttribute("label"))).toEqual(["Ventana deslizante", "Resumen"]);
    await pick("window-last-too-big");
    expect(screen.getByRole("slider", { name: "Presupuesto de tokens" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Presupuesto insuficiente (necesita 17)");
    expect(rows()[0]).toHaveTextContent("sistema");
    fireEvent.change(screen.getByRole("slider"), { target: { value: "40" } });
    expect(rows()[0]).toHaveTextContent("Conservado");
    expect(screen.getByRole("region", { name: "Totales" })).toHaveTextContent("Tokens antes");
    await pick("summarize-one-summary");
    expect(await screen.findByRole("region", { name: "Petición al resumidor" })).toBeInTheDocument();
    expect(screen.getByText(/fijado por el escenario/i)).toBeInTheDocument();
    expect(states()).toContain("summary");
    expect(rows()[1]).toHaveTextContent("Resumen nuevo");
    expect(rows()[2]).toHaveTextContent("Sustituido por el resumen");
  });

  it("renders every fixture without throwing", async () => {
    mount();
    const user = userEvent.setup();
    const selector = screen.getByLabelText("Fixture");
    for (const fx of contextFixtures) {
      await user.selectOptions(selector, fx.name);
      await waitFor(() => expect(screen.getByRole("main")).not.toHaveAttribute("aria-busy", "true"));
      expect(screen.queryByRole("alert"), fx.name).toBeNull();
      if (fx.messages.length === 0) expect(screen.getByText("This conversation has no messages."), fx.name).toBeInTheDocument();
      else expect(rows(), fx.name).toHaveLength(fx.messages.length + (screen.queryByRole("region", { name: "Summary message" }) ? 1 : 0));
      const slider = screen.getByRole("slider");
      if (fx.setup.kind === "summarize") expect(slider, fx.name).toBeDisabled();
    }
  }, 60_000);
});
