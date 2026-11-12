import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";

beforeEach(() => {
  window.location.hash = "";
  document.documentElement.removeAttribute("data-theme");
});

describe("App navigation", () => {
  it("opens the providers page by default and marks its link as current", () => {
    render(<App />);
    expect(screen.getByRole("heading", { level: 1, name: "Providers" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Providers" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Structured" })).not.toHaveAttribute("aria-current");
  });

  it("switches between the two pages with the nav links", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("link", { name: "Structured" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Structured output" })).toBeInTheDocument();
    expect(window.location.hash).toBe("#/structured");
    expect(screen.getByRole("link", { name: "Structured" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Providers" })).not.toHaveAttribute("aria-current");
    await user.click(screen.getByRole("link", { name: "Providers" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Providers" })).toBeInTheDocument();
    expect(window.location.hash).toBe("#/providers");
  });

  it("starts on the structured page when the hash says so", () => {
    window.location.hash = "#/structured";
    render(<App />);
    expect(screen.getByRole("heading", { level: 1, name: "Structured output" })).toBeInTheDocument();
  });

  it("follows hash changes made outside the nav", async () => {
    render(<App />);
    window.location.hash = "#/structured";
    expect(await screen.findByRole("heading", { level: 1, name: "Structured output" })).toBeInTheDocument();
  });

  it("keeps the language and the theme across pages", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: /language|idioma/i }));
    await user.selectOptions(screen.getByLabelText("Tema"), "dark");
    await user.click(screen.getByRole("link", { name: "Estructurado" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Salida estructurada" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("es");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    expect(screen.getByLabelText("Tema")).toHaveValue("dark");
    await user.click(screen.getByRole("link", { name: "Proveedores" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Proveedores" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("es");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("labels the navigation landmark", () => {
    render(<App />);
    expect(screen.getByRole("navigation", { name: "Modules" })).toBeInTheDocument();
  });

  it.each([
    ["#/structured", "Structured output"],
    ["#/providers", "Providers"],
  ])("keeps the route and focuses main when the skip link is used on %s", async (hash, title) => {
    const user = userEvent.setup();
    window.location.hash = hash;
    render(<App />);
    await user.click(screen.getByRole("link", { name: "Skip to content" }));
    expect(window.location.hash).toBe(hash);
    expect(screen.getByRole("heading", { level: 1, name: title })).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveFocus();
  });

  it.each(["#main", "#foo"])("ignores the non-route hash %s set from outside", async (other) => {
    window.location.hash = "#/structured";
    render(<App />);
    window.location.hash = other;
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByRole("heading", { level: 1, name: "Structured output" })).toBeInTheDocument();
  });

  it("falls back to providers for an unknown route", async () => {
    window.location.hash = "#/structured";
    render(<App />);
    window.location.hash = "#/xyz";
    expect(await screen.findByRole("heading", { level: 1, name: "Providers" })).toBeInTheDocument();
  });
});
