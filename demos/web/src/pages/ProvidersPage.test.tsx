import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as providers from "@ilabs/providers";
import { vi } from "vitest";
import { App } from "../App";

vi.mock("@ilabs/providers", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@ilabs/providers")>();
  return { ...actual, runFixture: vi.fn(actual.runFixture) };
});

function timelineTexts() {
  const list = screen.getByRole("list", { name: /timeline|cronología/i });
  return within(list)
    .getAllByRole("listitem")
    .map((li) => li.textContent ?? "");
}

beforeEach(() => {
  window.location.hash = "";
});

describe("ProvidersPage", () => {
  it("shows retry-then-success rows in order", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.selectOptions(screen.getByLabelText("Fixture"), "retry-then-success");
    await screen.findByRole("list", { name: "Timeline" });
    const rows = timelineTexts();
    expect(rows).toHaveLength(3);
    expect(rows[0]).toContain("rate_limit");
    expect(rows[1]).toContain("100 ms");
    expect(rows[2]).toContain("ok");
  });

  it("shows unavailable for fallback-empty", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.selectOptions(screen.getByLabelText("Fixture"), "fallback-empty");
    const result = await screen.findByRole("region", { name: "Result" });
    expect(within(result).getByText("unavailable", { selector: "code" })).toBeInTheDocument();
  });

  it("lists all 13 fixtures", () => {
    render(<App />);
    expect(within(screen.getByLabelText("Fixture")).getAllByRole("option")).toHaveLength(13);
  });

  it("toggles the language between en and es", async () => {
    const user = userEvent.setup();
    render(<App />);
    expect(screen.getByLabelText("Fixture")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /language|idioma/i }));
    expect(screen.getByLabelText("Escenario")).toBeInTheDocument();
    expect(screen.queryByLabelText("Fixture")).toBeNull();
    await user.click(screen.getByRole("button", { name: /language|idioma/i }));
    expect(screen.getByLabelText("Fixture")).toBeInTheDocument();
  });

  it("shows the translated outcome label next to the code", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.selectOptions(screen.getByLabelText("Fixture"), "retry-then-success");
    const list = await screen.findByRole("list", { name: "Timeline" });
    const rows = within(list).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("rate_limit");
    expect(rows[0]).toHaveTextContent("rate limited");
    expect(rows[2]).toHaveTextContent("success");
    await user.click(screen.getByRole("button", { name: /language|idioma/i }));
    const es = within(await screen.findByRole("list", { name: "Cronología" })).getAllByRole("listitem");
    expect(es[0]).toHaveTextContent("límite de peticiones");
  });

  it("keeps the visible text of the language button in its accessible name", () => {
    render(<App />);
    const button = screen.getByRole("button", { name: /language/i });
    const visible = button.textContent ?? "";
    expect(visible).not.toBe("");
    expect(button.getAttribute("aria-label") ?? visible).toContain(visible);
  });

  it("renders an error state when the runner fails", async () => {
    vi.mocked(providers.runFixture).mockRejectedValueOnce(new Error("invalid fixture: boom"));
    render(<App />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not run the fixture");
    expect(alert).toHaveTextContent("invalid fixture: boom");
    expect(screen.queryByRole("region", { name: "Result" })).toBeNull();
  });

  it("recovers from a runner failure when another fixture is selected", async () => {
    const user = userEvent.setup();
    vi.mocked(providers.runFixture).mockRejectedValueOnce(new Error("boom"));
    render(<App />);
    await screen.findByRole("alert");
    await user.selectOptions(screen.getByLabelText("Fixture"), "retry-then-success");
    expect(await screen.findByRole("region", { name: "Result" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
