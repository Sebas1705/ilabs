import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { App } from "./App";

const scrollTo = vi.fn();

function setReducedMotion(reduced: boolean) {
  window.matchMedia = ((query: string) => ({
    matches: reduced && query.includes("prefers-reduced-motion"),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

beforeEach(() => {
  window.location.hash = "";
  document.title = "";
  scrollTo.mockClear();
  window.scrollTo = scrollTo as unknown as typeof window.scrollTo;
  setReducedMotion(false);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("URL and page never differ", () => {
  it.each(["#main", "#foo"])("restores the route hash with replaceState when %s is ignored", async (other) => {
    window.location.hash = "#/structured";
    render(<App />);
    const replace = vi.spyOn(window.history, "replaceState");
    window.location.hash = other;
    await waitFor(() => expect(window.location.hash).toBe("#/structured"));
    expect(replace).toHaveBeenCalledWith(null, "", "#/structured");
    expect(screen.getByRole("heading", { level: 1, name: "Structured output" })).toBeInTheDocument();
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it("rejects #/structuredX and restores the URL to the page shown", async () => {
    window.location.hash = "#/structured";
    render(<App />);
    window.location.hash = "#/structuredX";
    expect(await screen.findByRole("heading", { level: 1, name: "Providers" })).toBeInTheDocument();
    await waitFor(() => expect(window.location.hash).toBe("#/providers"));
  });

  it("normalizes an unknown #/xyz to the default route it falls back to", async () => {
    window.location.hash = "#/structured";
    render(<App />);
    window.location.hash = "#/xyz";
    expect(await screen.findByRole("heading", { level: 1, name: "Providers" })).toBeInTheDocument();
    await waitFor(() => expect(window.location.hash).toBe("#/providers"));
  });
});

describe("focus and scroll on navigation", () => {
  it("does not move focus or scroll on first load", () => {
    render(<App />);
    expect(document.body).toHaveFocus();
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it("moves focus to the new page heading and scrolls to the top when using the nav", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("link", { name: "Structured" }));
    const h1 = await screen.findByRole("heading", { level: 1, name: "Structured output" });
    await waitFor(() => expect(h1).toHaveFocus());
    expect(h1).toHaveAttribute("tabindex", "-1");
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: "smooth" });
    await user.click(screen.getByRole("link", { name: "Providers" }));
    const h1b = await screen.findByRole("heading", { level: 1, name: "Providers" });
    await waitFor(() => expect(h1b).toHaveFocus());
  });

  it("scrolls instantly when the user prefers reduced motion", async () => {
    setReducedMotion(true);
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("link", { name: "Structured" }));
    await waitFor(() => expect(scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: "instant" }));
  });

  it("leaves focus and scroll alone when a non-route hash is ignored", async () => {
    render(<App />);
    window.location.hash = "#foo";
    await waitFor(() => expect(window.location.hash).toBe("#/providers"));
    expect(document.body).toHaveFocus();
    expect(scrollTo).not.toHaveBeenCalled();
  });
});
