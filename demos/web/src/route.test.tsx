import { render, renderHook, screen } from "@testing-library/react";
import type { ComponentType } from "react";
import { Nav } from "./components/Nav";
import { LangContext } from "./i18n";
import { parseRoute, useRoute } from "./route";
import { ROUTES, type RouteDef } from "./routes";

const Blank: ComponentType = () => null;
const fake: RouteDef[] = [
  { id: "alpha", hash: "#/alpha", titleKey: "navProviders", navKey: "navProviders", component: Blank },
  { id: "beta", hash: "#/beta", titleKey: "navStructured", navKey: "navStructured", component: Blank },
  { id: "gamma", hash: "#/gamma", titleKey: "navProviders", navKey: "navStructured", component: Blank },
];

describe("route registry", () => {
  it("lists providers first (the default), then structured, context and evals", () => {
    expect(ROUTES.map((r) => r.hash)).toEqual(["#/providers", "#/structured", "#/context", "#/evals"]);
    expect(new Set(ROUTES.map((r) => r.id)).size).toBe(ROUTES.length);
  });

  it("matches only exact route hashes", () => {
    expect(parseRoute("#/structured").id).toBe("structured");
    expect(parseRoute("#/providers").id).toBe("providers");
  });

  it.each(["#/structuredX", "#/structured/", "#/structured?x=1", "#/providersX", "#/xyz", "", "#", "#foo", "#/"])(
    "falls back to the default route for %j",
    (hash) => {
      expect(parseRoute(hash).id).toBe("providers");
    },
  );

  it("works over any registry, falling back to its first entry", () => {
    expect(parseRoute("#/gamma", fake).id).toBe("gamma");
    expect(parseRoute("#/beta", fake).id).toBe("beta");
    expect(parseRoute("#/delta", fake).id).toBe("alpha");
    expect(parseRoute("#/betaX", fake).id).toBe("alpha");
  });

  it("builds the nav from the registry", () => {
    render(
      <LangContext.Provider value="en">
        <Nav current={fake[2]!} routes={fake} />
      </LangContext.Provider>,
    );
    const links = screen.getAllByRole("link");
    expect(links.map((l) => l.getAttribute("href"))).toEqual(["#/alpha", "#/beta", "#/gamma"]);
    expect(links[2]).toHaveAttribute("aria-current", "page");
    expect(links[0]).not.toHaveAttribute("aria-current");
  });

  it("useRoute reads the initial hash from the registry", () => {
    window.location.hash = "#/beta";
    const { result } = renderHook(() => useRoute(fake));
    expect(result.current.id).toBe("beta");
  });
});
