import { readdirSync } from "node:fs";
import { resolve } from "node:path";
import * as evals from "@ilabs/evals";
import { summarize } from "@ilabs/evals";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { App } from "../App";
import { evalsFixtures } from "../evalsFixtures";
import { LangContext, type Lang } from "../i18n";
import { EvalsPage } from "./EvalsPage";

vi.mock("@ilabs/evals", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@ilabs/evals")>();
  return { ...actual, runFixture: vi.fn(actual.runFixture) };
});

function mount(lang: Lang = "en") {
  return render(
    <LangContext.Provider value={lang}>
      <EvalsPage />
    </LangContext.Provider>,
  );
}

async function pick(name: string) {
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText(/^(Fixture|Escenario)$/), name);
  await waitFor(() => expect(screen.getByRole("main")).not.toHaveAttribute("aria-busy", "true"));
  return user;
}

/** The run's polite status line: the first live region on the page (the playground's verdict is the second). */
const runStatus = () => screen.getAllByRole("status")[0]!;

const fixtureCount = readdirSync(resolve(__dirname, "../../../../contracts/evals")).filter((f) => f.endsWith(".json")).length;

describe("EvalsPage: run a suite", () => {
  it("is reachable from the nav through the registry", async () => {
    window.location.hash = "";
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("link", { name: "Evals" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Evals" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Evals" })).toHaveAttribute("aria-current", "page");
    expect(window.location.hash).toBe("#/evals");
  });

  it("reaches every fixture, grouped by prefix, with localized labels", () => {
    mount();
    const selector = screen.getByLabelText("Fixture");
    expect(fixtureCount).toBeGreaterThanOrEqual(162);
    expect(evalsFixtures).toHaveLength(fixtureCount);
    expect(within(selector).getAllByRole("option")).toHaveLength(fixtureCount);
    const groups = within(selector).getAllByRole("group");
    expect(groups.map((g) => g.getAttribute("label"))).toEqual(["Run results", "Strings", "Length", "JSON", "Judge", "Invalid suites"]);
  });

  it("shows a fail cell with its grader details for status-fail-beats-pass", async () => {
    mount();
    await pick("status-fail-beats-pass");
    const table = screen.getByRole("table");
    const cell = within(table).getByRole("button", { name: "c1 × a: Fail" });
    expect(cell).toHaveTextContent("Fail");
    expect(cell.querySelector('[aria-hidden="true"]')).not.toBeNull();
    const detail = screen.getByRole("region", { name: "Detail: c1 on a" });
    expect(detail).toHaveTextContent("hello there");
    const graders = within(within(detail).getByRole("list", { name: "Graders" })).getAllByRole("listitem");
    expect(graders).toHaveLength(2);
    expect(graders[0]).toHaveTextContent("contains");
    expect(graders[0]).toHaveTextContent("Pass");
    expect(graders[1]).toHaveTextContent("length");
    expect(graders[1]).toHaveTextContent("Fail");
  });

  it("shows the error cell and no grader rows when the provider fails", async () => {
    mount();
    await pick("provider-error-skips-graders");
    expect(screen.getByRole("button", { name: "c1 × a: Error" })).toHaveTextContent("Error");
    const detail = screen.getByRole("region", { name: "Detail: c1 on a" });
    expect(detail).toHaveTextContent("Provider error: timeout");
    expect(within(detail).getByRole("list", { name: "Case messages" })).toBeInTheDocument();
    expect(within(detail).queryByText("Graders")).toBeNull();
    expect(within(detail).queryByRole("list", { name: "Graders" })).toBeNull();
  });

  it("shows 67% and the counts for a two-thirds summary", async () => {
    mount();
    await pick("summary-pass-rate-two-thirds");
    const region = screen.getByRole("region", { name: "Pass rate per target" });
    expect(region).toHaveTextContent("a");
    expect(region).toHaveTextContent("67%");
    expect(region).toHaveTextContent("2 of 3");
    expect(region).toHaveTextContent("2 passed, 1 failed, 0 errors");
    expect(region.querySelector('.ratebar[aria-hidden="true"]')).not.toBeNull();
  });

  it("agrees with summarize() for every shipped run fixture", async () => {
    mount();
    const user = userEvent.setup();
    for (const name of ["summary-first-appearance-order", "multi-target-order", "status-error-then-fail"]) {
      await user.selectOptions(screen.getByLabelText("Fixture"), name);
      await waitFor(() => expect(screen.getByRole("main")).not.toHaveAttribute("aria-busy", "true"));
      const run = await evals.runFixture(evalsFixtures.find((f) => f.name === name)!);
      const rows = screen.getByRole("region", { name: "Pass rate per target" }).querySelectorAll("li");
      const expected = summarize(run.output!.results);
      expect(rows).toHaveLength(expected.length);
      expected.forEach((s, i) => expect(rows[i]).toHaveTextContent(`${Math.round(s.passRate * 100)}%`));
    }
  });

  it("shows the validation error state for an invalid suite", async () => {
    mount();
    await pick("invalid-suite-null");
    const region = screen.getByRole("region", { name: "Invalid suite" });
    expect(region).toHaveTextContent("invalid suite:");
    expect(region).toHaveTextContent("No provider was called.");
    expect(runStatus()).toHaveTextContent("Invalid suite: nothing was run");
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByRole("region", { name: "Pass rate per target" })).toBeNull();
  });

  it.each(["invalid-target-id-judge", "invalid-duplicate-target-id"])(
    "shows the target ids, not the suite JSON, when only the targets are invalid (%s)",
    async (name) => {
      mount();
      await pick(name);
      const region = screen.getByRole("region", { name: "Invalid suite" });
      expect(region).toHaveTextContent("ids must be unique");
      const ids = within(region).getByRole("list", { name: "Target ids" });
      const expected = evalsFixtures.find((f) => f.name === name)!.targets.map((t) => t.id);
      expect(within(ids).getAllByRole("listitem").map((li) => li.textContent)).toEqual(expected);
      expect(within(region).queryByRole("region", { name: "Suite JSON" })).toBeNull();
    },
  );

  it("shows the suite JSON and the library reason when the suite itself is invalid", async () => {
    mount();
    await pick("invalid-suite-null");
    const region = screen.getByRole("region", { name: "Invalid suite" });
    expect(within(region).getByRole("region", { name: "Suite JSON" })).toBeInTheDocument();
    expect(within(region).queryByRole("list", { name: "Target ids" })).toBeNull();
  });

  it("never pairs the previous fixture's results with the newly selected one while its run is pending", async () => {
    mount();
    await pick("status-fail-beats-pass");
    expect(screen.getByRole("button", { name: "c1 × a: Fail" })).toBeInTheDocument();
    vi.mocked(evals.runFixture).mockImplementationOnce(() => new Promise(() => {}));
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText("Fixture"), "status-all-pass");
    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "true");
    expect(runStatus()).toHaveTextContent("Running the suite…");
    expect(screen.queryByRole("button", { name: /c1 × a/ })).toBeNull();
    expect(screen.queryByRole("region", { name: /^Detail/ })).toBeNull();
  });

  it("renders a run with no targets as an explicit empty state", async () => {
    mount();
    await pick("no-targets-empty-run");
    expect(screen.getByText("This run has no targets, so there are no results.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("moves the detail to the cell the user selects", async () => {
    mount();
    const user = await pick("summary-pass-rate-two-thirds");
    expect(screen.getByRole("region", { name: "Detail: c1 on a" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "c3 × a: Fail" }));
    const detail = screen.getByRole("region", { name: "Detail: c3 on a" });
    expect(detail).toHaveTextContent("no");
    expect(screen.getByRole("button", { name: "c3 × a: Fail" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "c1 × a: Pass" })).toHaveAttribute("aria-pressed", "false");
  });

  it("shows grader issues with path and code", async () => {
    mount();
    await pick("json-schema-issues");
    const detail = screen.getByRole("region", { name: "Detail: c1 on a" });
    const issues = within(detail).getByRole("list", { name: "Issues" });
    expect(within(issues).getAllByRole("listitem")).toHaveLength(2);
    expect(issues).toHaveTextContent("/name");
    expect(issues).toHaveTextContent("required");
    expect(issues).toHaveTextContent("/age");
    expect(issues).toHaveTextContent("minimum");
  });

  it("shows a grader error such as no_judge", async () => {
    mount();
    await pick("judge-missing-no-call");
    const detail = screen.getByRole("region", { name: "Detail: c1 on a" });
    expect(detail).toHaveTextContent("no_judge");
  });

  it("shows the calls per provider, judge included", async () => {
    mount();
    await pick("calls-judge-key-zero");
    const calls = screen.getByRole("region", { name: "Provider calls" });
    expect(calls).toHaveTextContent("a");
    expect(calls).toHaveTextContent("judge");
  });

  it("keeps the live region coarse: running, then done", async () => {
    mount();
    await pick("status-all-pass");
    expect(runStatus()).toHaveTextContent("Run complete: 1 case, 1 target");
    await pick("multi-target-order");
    expect(runStatus()).toHaveTextContent(/Run complete: \d+ cases?, \d+ targets?/);
  });

  it("renders an error state when the runner rejects and recovers on another fixture", async () => {
    vi.mocked(evals.runFixture).mockRejectedValueOnce(new Error("invalid fixture: boom"));
    mount();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not run the fixture");
    expect(alert).toHaveTextContent("invalid fixture: boom");
    expect(screen.queryByRole("table")).toBeNull();
    await pick("status-all-pass");
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  it("shows a translated, non-blocking warning for fixtures that failed to load", async () => {
    const mod = await import("../evalsFixtures");
    mod.loadErrors.push("broken.json: invalid JSON");
    try {
      const { unmount } = mount();
      expect(screen.getByTestId("load-errors")).toHaveTextContent("broken.json");
      expect(screen.getByLabelText("Fixture")).toBeInTheDocument();
      unmount();
      mount("es");
      expect(screen.getByTestId("load-errors")).toHaveTextContent("Escenarios omitidos por no poder cargarse:");
    } finally {
      mod.loadErrors.length = 0;
    }
  });

  it("renders in Spanish", async () => {
    mount("es");
    await pick("status-fail-beats-pass");
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "c1 × a: Falla" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Detalle: c1 en a" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Tasa de aciertos por objetivo" })).toBeInTheDocument();
    expect(runStatus()).toHaveTextContent("Ejecución terminada: 1 caso, 1 objetivo");
    await pick("invalid-suite-null");
    expect(screen.getByRole("region", { name: "Suite no válida" })).toBeInTheDocument();
  });

  it("renders every fixture without throwing", async () => {
    mount();
    const user = userEvent.setup();
    const selector = screen.getByLabelText("Fixture");
    for (const fx of evalsFixtures) {
      await user.selectOptions(selector, fx.name);
      await waitFor(() => expect(screen.getByRole("main")).not.toHaveAttribute("aria-busy", "true"));
      expect(screen.queryByRole("alert"), fx.name).toBeNull();
      const invalid = "invalidSuite" in fx.expect;
      expect(screen.queryByRole("region", { name: "Invalid suite" }) !== null, fx.name).toBe(invalid);
      if (!invalid && fx.targets.length > 0) {
        const cells = within(screen.getByRole("table")).getAllByRole("button");
        const expected = "results" in fx.expect ? fx.expect.results.length : 0;
        expect(cells, fx.name).toHaveLength(expected);
      }
    }
  }, 120_000);
});
