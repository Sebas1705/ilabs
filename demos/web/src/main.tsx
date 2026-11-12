import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { parseRoute } from "./route";
import "./styles.css";

// Minimal hash routing: the registry in routes.ts lists the pages; the first one is the default.
const wanted = parseRoute(window.location.hash).hash;
if (window.location.hash !== wanted) {
  window.history.replaceState(null, "", wanted);
}

createRoot(document.getElementById("root") as HTMLElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
