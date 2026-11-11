import type { FixtureEvent } from "@ilabs/providers";
import { useT, type Key } from "../i18n";

const OUTCOME_KEY: Record<string, Key> = {
  ok: "outcomeOk",
  rate_limit: "outcomeRateLimit",
  timeout: "outcomeTimeout",
  invalid_output: "outcomeInvalid",
  unavailable: "outcomeUnavailable",
};

/** A coloured badge that always carries its text label (never colour alone). */
export function OutcomeBadge({ outcome }: { outcome: string }) {
  const t = useT();
  const tone = outcome === "ok" ? "ok" : outcome === "rate_limit" || outcome === "timeout" ? "warn" : "err";
  const key = OUTCOME_KEY[outcome];
  return (
    <span className={`badge badge-${tone}`} title={key ? t(key) : undefined}>
      <span aria-hidden="true">{tone === "ok" ? "✓" : tone === "warn" ? "!" : "✕"}</span>
      <code>{outcome}</code>
    </span>
  );
}

export function Timeline({ events }: { events: FixtureEvent[] }) {
  const t = useT();
  return (
    <ol className="timeline" aria-label={t("timeline")}>
      {events.map((ev, i) =>
        ev.type === "call" ? (
          <li key={i} className="row">
            <span className="row-kind">{t("callTo")} {"→"}</span>
            <strong>{ev.provider}</strong>
            <OutcomeBadge outcome={ev.outcome} />
          </li>
        ) : (
          <li key={i} className="row row-wait">
            <span className="row-kind">{t("wait")}</span>
            <strong>{ev.ms} ms</strong>
          </li>
        ),
      )}
    </ol>
  );
}
