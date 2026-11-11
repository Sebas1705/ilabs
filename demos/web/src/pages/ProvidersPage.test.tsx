import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProvidersPage } from "./ProvidersPage";

function timelineTexts() {
  const list = screen.getByRole("list", { name: /timeline|cronología/i });
  return within(list)
    .getAllByRole("listitem")
    .map((li) => li.textContent ?? "");
}

describe("ProvidersPage", () => {
  it("shows retry-then-success rows in order", async () => {
    const user = userEvent.setup();
    render(<ProvidersPage />);
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
    render(<ProvidersPage />);
    await user.selectOptions(screen.getByLabelText("Fixture"), "fallback-empty");
    const result = await screen.findByRole("region", { name: "Result" });
    expect(within(result).getByText("unavailable")).toBeInTheDocument();
  });

  it("lists all 13 fixtures", () => {
    render(<ProvidersPage />);
    expect(within(screen.getByLabelText("Fixture")).getAllByRole("option")).toHaveLength(13);
  });

  it("toggles the language between en and es", async () => {
    const user = userEvent.setup();
    render(<ProvidersPage />);
    expect(screen.getByLabelText("Fixture")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /language|idioma/i }));
    expect(screen.getByLabelText("Escenario")).toBeInTheDocument();
    expect(screen.queryByLabelText("Fixture")).toBeNull();
    await user.click(screen.getByRole("button", { name: /language|idioma/i }));
    expect(screen.getByLabelText("Fixture")).toBeInTheDocument();
  });
});
