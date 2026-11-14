import type { Status } from "@ilabs/evals";
import { useT, type Key } from "../i18n";

export const STATUS: Record<Status, { key: Key; glyph: string; tone: "ok" | "err" | "warn" }> = {
  pass: { key: "eStatusPass", glyph: "✓", tone: "ok" },
  fail: { key: "eStatusFail", glyph: "✕", tone: "err" },
  error: { key: "eStatusError", glyph: "!", tone: "warn" },
};

/** Translated word for a status, for names and sentences. */
export function useStatusLabel(): (status: Status) => string {
  const t = useT();
  return (status) => t(STATUS[status].key);
}
