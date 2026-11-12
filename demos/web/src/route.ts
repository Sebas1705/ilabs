import { useEffect, useRef, useState } from "react";
import { ROUTES, type RouteDef } from "./routes";

/** Exact match on a route hash; anything else (unknown or near-miss like `#/structuredX`) falls back to the first route. */
export function parseRoute(hash: string, routes: readonly RouteDef[] = ROUTES): RouteDef {
  return routes.find((r) => r.hash === hash) ?? routes[0]!;
}

/**
 * Hash route kept in sync with the address bar; unknown hashes fall back to the first route.
 * Hashes that are not routes (for example #main) keep the current page. In every case the URL is
 * rewritten with replaceState to the hash of the page shown, so address bar and page never differ
 * (no new history entry, no navigation, no scroll).
 */
export function useRoute(routes: readonly RouteDef[] = ROUTES): RouteDef {
  const [route, setRoute] = useState<RouteDef>(() => parseRoute(window.location.hash, routes));
  const current = useRef(route);
  useEffect(() => {
    const onChange = () => {
      const hash = window.location.hash;
      const next = hash.startsWith("#/") ? parseRoute(hash, routes) : current.current;
      if (next !== current.current) {
        current.current = next;
        setRoute(next);
      }
      if (hash !== next.hash) window.history.replaceState(null, "", next.hash);
    };
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, [routes]);
  return route;
}
