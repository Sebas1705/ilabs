import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// jsdom does not implement scrolling; the app calls window.scrollTo after a page change.
window.scrollTo = () => {};

afterEach(() => {
  cleanup();
  try {
    localStorage.clear();
  } catch {
    /* storage unavailable */
  }
});
