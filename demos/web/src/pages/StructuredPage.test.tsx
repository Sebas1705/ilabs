import { readdirSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as structured from "@ilabs/structured";
import { vi } from "vitest";
import { LangContext, type Lang } from "../i18n";
import { structuredFixtures } from "../structuredFixtures";
import { StructuredPage } from "./StructuredPage";

vi.mock("@ilabs/structured", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@ilabs/structured")>();
  return { ...actual, runFixture: vi.fn(actual.runFixture) };
});

function mount(lang: Lang = "en") {
  return render(
    <LangContext.Provider value={lang}>
      <StructuredPage />
    </LangContext.Provider>,
  );
}

async function pick(name: string) {
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText(/^(Fixture|Escenario)$/), name);
  return user;
}

function attemptHeadings() {
  return screen.queryAllByRole("heading", { level: 3, name: /^(Attempt|Intento) \d+$/ });
}

// Counted straight from the contracts directory, independently of the import.meta.glob in structuredFixtures.ts.
const fixtureCount = readdirSync(resolve(__dirname, "../../../../contracts/structured")).filter((f) =>
  f.endsWith(".json"),
).length;

describe("StructuredPage", () => {
  it("reaches every fixture, grouped by prefix", () => {
    mount();
    const selector = screen.getByLabelText("Fixture");
    expect(fixtureCount).toBeGreaterThanOrEqual(70);
    expect(within(selector).getAllByRole("option")).toHaveLength(fixtureCount);
    expect(structuredFixtures).toHaveLength(fixtureCount);
    const groups = within(selector).getAllByRole("group");
    expect(groups.map((g) => g.getAttribute("label"))).toEqual(["Extraction", "Validation", "Repair loop"]);
    expect(within(groups[0]!).getAllByRole("option").every((o) => o.textContent?.startsWith("extract-"))).toBe(true);
    expect(within(groups[1]!).getAllByRole("option").every((o) => o.textContent?.startsWith("validate-"))).toBe(true);
    expect(within(groups[2]!).getAllByRole("option").every((o) => o.textContent?.startsWith("loop-"))).toBe(true);
  });

  it("localizes the group labels", () => {
    mount("es");
    const groups = within(screen.getByLabelText("Escenario")).getAllByRole("group");
    expect(groups.map((g) => g.getAttribute("label"))).toEqual(["Extracción", "Validación", "Bucle de reparación"]);
  });

  it("shows the schema, the request and the setup", async () => {
    mount();
    await pick("loop-repair-second");
    expect(screen.getByRole("region", { name: "Schema" })).toHaveTextContent('"minimum": 0');
    expect(screen.getByRole("region", { name: "Request" })).toHaveTextContent("How old?");
    const setup = screen.getByRole("region", { name: "Setup" });
    expect(setup).toHaveTextContent("Max repairs");
    expect(setup).toHaveTextContent('{"age": -1}');
  });

  it("replays loop-repair-second as two attempts with an issue and the final value", async () => {
    mount();
    await pick("loop-repair-second");
    const result = await screen.findByRole("region", { name: "Result" });
    expect(attemptHeadings()).toHaveLength(2);
    const attempts = screen.getByRole("list", { name: "Attempts" });
    const issue = within(attempts).getAllByRole("list", { name: "Issues" })[0]!;
    expect(issue).toHaveTextContent("/age");
    expect(issue).toHaveTextContent("minimum");
    expect(issue).toHaveTextContent("below the minimum");
    expect(attempts).toHaveTextContent("Repair requested");
    expect(result).toHaveTextContent('"age": 7');
    expect(within(result).getByText("2", { selector: "dd" })).toBeInTheDocument();
    expect(result).toHaveTextContent("assistant, user");
  });

  it("shows the raw reply as text, never as HTML", async () => {
    mount();
    await pick("extract-prose-around");
    await screen.findByRole("region", { name: "Result" });
    const attempts = screen.getByRole("list", { name: "Attempts" });
    expect(attempts.querySelector("pre")?.textContent).toMatch(/\{/);
    expect(attempts.querySelector("script, img")).toBeNull();
  });

  it("shows no_json with a translated label and a final invalid_output error for extract-no-json", async () => {
    mount();
    await pick("extract-no-json");
    const result = await screen.findByRole("region", { name: "Result" });
    const attempts = screen.getByRole("list", { name: "Attempts" });
    expect(within(attempts).getAllByText("no_json", { selector: "code" }).length).toBeGreaterThan(0);
    expect(attempts).toHaveTextContent("no JSON found");
    expect(result).toHaveTextContent("invalid_output");
    expect(result).toHaveTextContent("no_json");
  });

  it("shows the root path label for extract-empty-reply", async () => {
    mount();
    await pick("extract-empty-reply");
    await screen.findByRole("region", { name: "Result" });
    const attempts = screen.getByRole("list", { name: "Attempts" });
    expect(within(within(attempts).getByRole("list", { name: "Issues" })).getByRole("listitem")).toHaveTextContent("(root)");
    expect(attempts).toHaveTextContent("(empty reply)");
  });

  it("translates outcomes, issues and the root label to Spanish", async () => {
    mount("es");
    await pick("extract-empty-reply");
    await screen.findByRole("region", { name: "Resultado" });
    const attempts = screen.getByRole("list", { name: "Intentos" });
    expect(attempts).toHaveTextContent("(raíz)");
    expect(attempts).toHaveTextContent("no_json");
    expect(attempts).toHaveTextContent("no se encontró JSON");
    expect(attempts).toHaveTextContent("(respuesta vacía)");
  });

  it("shows the extracted value for an accepted attempt", async () => {
    mount();
    await pick("loop-first-try");
    await screen.findByRole("region", { name: "Result" });
    expect(attemptHeadings()).toHaveLength(1);
    const attempts = screen.getByRole("list", { name: "Attempts" });
    expect(attempts).toHaveTextContent("accepted");
    expect(attempts).toHaveTextContent("Extracted JSON");
  });

  it("shows the provider failure row and no reply rows for loop-provider-error-first", async () => {
    mount();
    await pick("loop-provider-error-first");
    const result = await screen.findByRole("region", { name: "Result" });
    const attempts = screen.getByRole("list", { name: "Attempts" });
    expect(attemptHeadings()).toHaveLength(0);
    expect(within(attempts).getAllByRole("listitem")).toHaveLength(1);
    expect(attempts).toHaveTextContent("Attempt 1: provider failed with timed out (timeout)");
    expect(screen.queryByText(/No reply was recorded/)).toBeNull();
    expect(result).toHaveTextContent("timeout");
  });

  it("shows one reply row then the provider failure row for loop-provider-error-mid", async () => {
    mount();
    await pick("loop-provider-error-mid");
    await screen.findByRole("region", { name: "Result" });
    expect(attemptHeadings()).toHaveLength(1);
    const attempts = screen.getByRole("list", { name: "Attempts" });
    const issue = within(attempts).getByRole("list", { name: "Issues" });
    expect(issue).toHaveTextContent("/a");
    expect(issue).toHaveTextContent("required");
    expect(attempts).toHaveTextContent("Repair requested");
    expect(within(attempts).getAllByRole("listitem", {}).filter((li) => li.className === "attempt-item")).toHaveLength(2);
    expect(attempts).toHaveTextContent("Attempt 2: provider failed with rate limited (rate_limit)");
  });

  it("shows the reply row and the provider failure row for loop-script-exhausted", async () => {
    mount();
    await pick("loop-script-exhausted");
    await screen.findByRole("region", { name: "Result" });
    expect(attemptHeadings()).toHaveLength(1);
    const attempts = screen.getByRole("list", { name: "Attempts" });
    expect(attempts).toHaveTextContent("no JSON found");
    expect(attempts).toHaveTextContent("Attempt 2: provider failed with unavailable (unavailable)");
  });

  it("translates the provider failure row to Spanish", async () => {
    mount("es");
    await pick("loop-provider-error-mid");
    await screen.findByRole("region", { name: "Resultado" });
    expect(screen.getByRole("list", { name: "Intentos" })).toHaveTextContent(
      "Intento 2: el proveedor falló con límite de peticiones (rate_limit)",
    );
  });

  it("adds no provider failure row for a structured invalid_output failure", async () => {
    mount();
    await pick("extract-no-json");
    await screen.findByRole("region", { name: "Result" });
    expect(screen.getByRole("list", { name: "Attempts" })).not.toHaveTextContent("provider failed");
  });

  it("renders an error state when the runner fails and recovers on another fixture", async () => {
    vi.mocked(structured.runFixture).mockRejectedValueOnce(new Error("invalid fixture: boom"));
    mount();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not run the fixture");
    expect(alert).toHaveTextContent("invalid fixture: boom");
    expect(screen.queryByRole("region", { name: "Result" })).toBeNull();
    await pick("loop-first-try");
    expect(await screen.findByRole("region", { name: "Result" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("renders every fixture without throwing and reports its attempts", async () => {
    const user = userEvent.setup();
    mount();
    const selector = screen.getByLabelText("Fixture");
    for (const fx of structuredFixtures) {
      await user.selectOptions(selector, fx.name);
      const result = await screen.findByRole("region", { name: "Result" });
      await waitFor(() => expect(within(result).getByText(String(fx.expect.attempts), { selector: "dd" })).toBeInTheDocument());
      expect(screen.queryByRole("alert"), fx.name).toBeNull();
    }
  }, 60_000);

  it("renders a minimal fixture that passes the shape check, and one using every script entry flavour, without throwing", async () => {
    const mod = await import("../structuredFixtures");
    const minimal = { name: "zz-minimal", schema: {}, request: { messages: [] }, setup: { maxRepairs: 0 }, provider: { id: "p", script: [] }, expect: {} };
    const full = {
      name: "zz-full",
      schema: { type: "object" },
      request: { messages: [{ role: "user", content: "hi" }] },
      setup: { maxRepairs: 1 },
      provider: { id: "p", script: [{ response: "{}" }, { error: { kind: "rate_limit", retryAfterMs: 5 } }, { error: { kind: "timeout", retryAfterMs: null } }] },
      expect: {},
    };
    const { fixtures: accepted, errors } = mod.parseStructuredFixtures({ "m.json": JSON.stringify(minimal), "f.json": JSON.stringify(full) });
    expect(errors).toEqual([]);
    mod.structuredFixtures.push(...accepted);
    try {
      const user = userEvent.setup();
      mount();
      for (const fx of accepted) {
        await user.selectOptions(screen.getByLabelText("Fixture"), fx.name);
        expect(screen.getByRole("heading", { name: "Setup" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "Request" })).toBeInTheDocument();
        await screen.findByRole("region", { name: "Result" }).catch(() => screen.findByRole("alert"));
      }
    } finally {
      mod.structuredFixtures.splice(mod.structuredFixtures.length - accepted.length, accepted.length);
    }
  });

  it("shows a translated, non-blocking warning for fixtures that failed to load and keeps the rest selectable", async () => {
    const mod = await import("../structuredFixtures");
    mod.loadErrors.push("broken.json: invalid JSON");
    try {
      const { unmount } = mount();
      const note = screen.getByTestId("load-errors");
      expect(note).toHaveTextContent("Fixtures skipped because they could not be loaded:");
      expect(note).toHaveTextContent("broken.json");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(within(screen.getByLabelText("Fixture")).getAllByRole("option")).toHaveLength(fixtureCount);
      unmount();
      mount("es");
      expect(screen.getByTestId("load-errors")).toHaveTextContent("Escenarios omitidos por no poder cargarse:");
    } finally {
      mod.loadErrors.length = 0;
    }
  });
});
