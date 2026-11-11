import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ProvidersPage } from "./pages/ProvidersPage";
import "./styles.css";

// Minimal hash routing: /providers is the only page and the default.
if (!window.location.hash.startsWith("#/providers")) {
  window.history.replaceState(null, "", "#/providers");
}

createRoot(document.getElementById("root") as HTMLElement).render(
  <StrictMode>
    <ProvidersPage />
  </StrictMode>,
);
