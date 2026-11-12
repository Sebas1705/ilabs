import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentType } from "react";
import { vi } from "vitest";
import { App } from "./App";
import type { RouteDef } from "./routes";

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

describe("route registry in the app", () => {
  it("sets the document title from the registry", async () => {
    const user = userEvent.setup();
    render(<App />);
    expect(document.title).toBe("iLabs · Providers");
    await user.click(screen.getByRole("link", { name: "Structured" }));
    await waitFor(() => expect(document.title).toBe("iLabs · Structured"));
  });

  it("renders a page added to the registry with no other change", async () => {
    const Home: ComponentType = () => (
      <main id="main" tabIndex={-1}>
        <h1 tabIndex={-1}>Home</h1>
      </main>
    );
    const Extra: ComponentType = () => (
      <main id="main" tabIndex={-1}>
        <h1 tabIndex={-1}>Extra page</h1>
      </main>
    );
    const routes: RouteDef[] = [
      { id: "home", hash: "#/home", titleKey: "navProviders", navKey: "navProviders", component: Home },
      { id: "extra", hash: "#/extra", titleKey: "navStructured", navKey: "navStructured", component: Extra },
    ];
    const user = userEvent.setup();
    render(<App routes={routes} />);
    expect(screen.getByRole("heading", { level: 1, name: "Home" })).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Structured" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Extra page" })).toBeInTheDocument();
    expect(window.location.hash).toBe("#/extra");
    expect(document.title).toBe("iLabs · Structured");
  });

  it("does not accept #/structuredX as the structured page", async () => {
    window.location.hash = "#/structured";
    render(<App />);
    window.location.hash = "#/structuredX";
    expect(await screen.findByRole("heading", { level: 1, name: "Providers" })).toBeInTheDocument();
  });
});
