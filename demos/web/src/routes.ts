import type { ComponentType } from "react";
import type { Key } from "./i18n";
import { ContextPage } from "./pages/ContextPage";
import { ProvidersPage } from "./pages/ProvidersPage";
import { StructuredPage } from "./pages/StructuredPage";

export interface RouteDef {
  /** Stable identifier. */
  id: string;
  /** Exact location hash that selects the page, for example `#/providers`. */
  hash: string;
  /** Dictionary key for the document title and the page name. */
  titleKey: Key;
  /** Dictionary key for the nav link label. */
  navKey: Key;
  component: ComponentType;
}

/** The one list of pages: the nav, the page switch, the title and the hash parser all read it. The first entry is the default. */
export const ROUTES: readonly RouteDef[] = [
  { id: "providers", hash: "#/providers", titleKey: "navProviders", navKey: "navProviders", component: ProvidersPage },
  { id: "structured", hash: "#/structured", titleKey: "navStructured", navKey: "navStructured", component: StructuredPage },
  { id: "context", hash: "#/context", titleKey: "navContext", navKey: "navContext", component: ContextPage },
];
