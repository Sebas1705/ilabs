import { BudgetTooSmallError, DEFAULT_KEEP_RECENT, runFixture, slidingWindow, type Fixture, type FixtureRun, type WindowResult } from "@ilabs/context";
import { useEffect, useMemo, useState } from "react";
import { LoadErrors } from "../components/LoadErrors";
import { TokenBar } from "../components/TokenBar";
import { contextFixtures, groupContextFixtures, loadErrors } from "../contextFixtures";
import { buildRows, totalTokens, type Row, type RowState } from "../contextView";
import { useT, type Key } from "../i18n";
import { useRoleLabel } from "../roles";

const DEFAULT_FIXTURE = "window-drops-oldest";

const STATE: Record<Exclude<RowState, "none">, { key: Key; glyph: string }> = {
  kept: { key: "cStateKept", glyph: "✓" },
  dropped: { key: "cStateDropped", glyph: "✕" },
  replaced: { key: "cStateReplaced", glyph: "↻" },
  summary: { key: "cStateSummary", glyph: "★" },
};

type Outcome =
  | { kind: "ok"; messages: Fixture["messages"]; droppedCount: number; tokensBefore: number; tokensAfter: number }
  | { kind: "tooSmall"; required: number }
  | { kind: "failed"; errorKind: string }
  | { kind: "crashed"; message: string };

function useFill() {
  const t = useT();
  return (key: Key, vars: Record<string, string | number>) =>
    t(key).replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
}

function runWindow(fixture: Fixture, maxTokens: number): Outcome {
  try {
    const r: WindowResult = slidingWindow(fixture.messages, { maxTokens });
    return { kind: "ok", ...r };
  } catch (e) {
    if (e instanceof BudgetTooSmallError) return { kind: "tooSmall", required: e.required };
    return { kind: "crashed", message: e instanceof Error ? e.message : String(e) };
  }
}

function outcomeOfRun(run: FixtureRun): Outcome {
  const { result } = run;
  if ("error" in result) {
    return result.error.kind === "budget_too_small" && result.error.required !== undefined
      ? { kind: "tooSmall", required: result.error.required }
      : { kind: "failed", errorKind: result.error.kind };
  }
  return { kind: "ok", ...result };
}

function MessageRow({ row, max }: { row: Row; max: number }) {
  const t = useT();
  const fill = useFill();
  const roleLabel = useRoleLabel();
  const state = row.state === "none" ? null : STATE[row.state];
  return (
    <li className={`ctx-row ctx-${row.state}`} data-state={row.state}>
      <div className="ctx-head">
        <strong>{roleLabel(row.message.role)}</strong>
        <span className="num">{fill("cCost", { n: row.tokens })}</span>
        {state && (
          <span className="state">
            <span aria-hidden="true">{state.glyph}</span> {t(state.key)}
          </span>
        )}
      </div>
      <p className="message ctx-content">{row.message.content === "" ? <span className="muted">{t("cEmptyContent")}</span> : row.message.content}</p>
      <TokenBar cost={row.tokens} max={max} />
    </li>
  );
}

export function ContextPage() {
  const t = useT();
  const fill = useFill();
  const roleLabel = useRoleLabel();
  const groups = useMemo(() => groupContextFixtures(contextFixtures), []);
  const [name, setName] = useState(
    contextFixtures.some((f) => f.name === DEFAULT_FIXTURE) ? DEFAULT_FIXTURE : (contextFixtures[0]?.name ?? ""),
  );
  const fixture = contextFixtures.find((f) => f.name === name);
  const isWindow = fixture?.setup.kind !== "summarize";

  const total = useMemo(() => (fixture ? totalTokens(fixture.messages) : 0), [fixture]);
  const max = Math.max(1, total);
  const fixtureBudget = fixture?.setup.maxTokens ?? 1;
  const [override, setOverride] = useState<{ name: string; value: number } | null>(null);
  const budget = isWindow
    ? override?.name === name
      ? override.value
      : Math.min(max, Math.max(1, fixtureBudget))
    : fixtureBudget;
  const sliderMax = isWindow ? max : Math.max(max, fixtureBudget);

  // Window fixtures re-run synchronously on every slider move; summarize fixtures run once, asynchronously.
  const windowOutcome = useMemo(() => (fixture && isWindow ? runWindow(fixture, budget) : null), [fixture, isWindow, budget]);
  const [run, setRun] = useState<FixtureRun | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setRun(null);
    setRunError(null);
    if (fixture && fixture.setup.kind === "summarize") {
      runFixture(fixture).then(
        (r) => !cancelled && setRun(r),
        (e: unknown) => !cancelled && setRunError(e instanceof Error ? e.message : String(e)),
      );
    }
    return () => {
      cancelled = true;
    };
  }, [fixture]);

  const outcome: Outcome | null = isWindow ? windowOutcome : run ? outcomeOfRun(run) : null;
  const busy = !isWindow && run === null && runError === null;
  const survivors = outcome?.kind === "ok" ? outcome.messages : null;
  const summarized = !isWindow && run !== null && "summarized" in run.result && run.result.summarized === true;
  const rows = useMemo(
    () => (fixture ? buildRows(fixture.messages, survivors, summarized, fixture.setup.keepRecent ?? DEFAULT_KEEP_RECENT) : []),
    [fixture, survivors, summarized],
  );
  const maxCost = rows.reduce((m, r) => Math.max(m, r.tokens), 0);
  const summaryRow = rows.find((r) => r.state === "summary");
  const resultRows = rows.filter((r) => r.state === "kept" || r.state === "summary");
  const request = !isWindow ? (run?.summarizerRequest ?? null) : null;

  let status = "";
  if (busy) status = t("cRunning");
  // Coarse states only: the per-step figures live in aria-valuetext and the Totals region, so the polite
  // live region speaks on a transition (running, enough, too small, failed), not on every slider tick.
  else if (outcome?.kind === "ok") status = t("cStatusOk");
  else if (outcome?.kind === "tooSmall") status = fill("cTooSmall", { n: outcome.required });
  else if (outcome?.kind === "failed") status = fill("cFailedWith", { kind: outcome.errorKind });

  return (
    <>
      <main id="main" className="column" tabIndex={-1} aria-busy={busy}>
        <h1 tabIndex={-1}>{t("cTitle")}</h1>
        <p className="muted">{t("cSubtitle")}</p>
        <LoadErrors errors={loadErrors} />
        <label className="field">
          <span>{t("fixture")}</span>
          <select value={name} onChange={(e) => setName(e.target.value)}>
            {groups.map((g) => (
              <optgroup key={g.label} label={t(g.label)}>
                {g.fixtures.map((f) => (
                  <option key={f.name} value={f.name}>
                    {f.name}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>

        {fixture && (
          <section aria-labelledby="h-budget" className="card">
            <h2 id="h-budget">{t("cBudgetHeading")}</h2>
            <div className="slider-row">
              <label htmlFor="budget">{t("cBudgetLabel")}</label>
              <span className="num">{fill("cCost", { n: budget })}</span>
            </div>
            <input
              id="budget"
              className="slider"
              type="range"
              min={1}
              max={sliderMax}
              step={1}
              value={budget}
              disabled={!isWindow}
              aria-valuetext={isWindow ? `${t("cTokensUnit")}: ${budget}` : `${t("cTokensUnit")}: ${budget} (${t("cFixedByFixture")})`}
              aria-describedby="budget-note"
              onChange={(e) => setOverride({ name, value: Number(e.target.value) })}
            />
            <p id="budget-note" className="muted">{isWindow ? t("cBudgetLive") : fill("cBudgetFixed", { n: fixtureBudget })}</p>
            <p role="status" className={`status${outcome?.kind === "tooSmall" || outcome?.kind === "failed" ? " status-warn" : ""}`}>
              {status}
            </p>
          </section>
        )}

        {(runError ?? (outcome?.kind === "crashed" ? outcome.message : null)) !== null && (
          <p role="alert" className="card error">
            {t("runError")}: {runError ?? (outcome?.kind === "crashed" ? outcome.message : "")}
          </p>
        )}

        {outcome?.kind === "ok" && (
          <section role="region" aria-label={t("cTotals")} className="card">
            <h2>{t("cTotals")}</h2>
            <dl className="kv">
              <div>
                <dt>{t("cTokensBefore")}</dt>
                <dd className="num">{outcome.tokensBefore}</dd>
              </div>
              <div>
                <dt>{t("cTokensAfter")}</dt>
                <dd className="num">{outcome.tokensAfter}</dd>
              </div>
              <div>
                <dt>{t("cDroppedCount")}</dt>
                <dd className="num">{outcome.droppedCount}</dd>
              </div>
            </dl>
          </section>
        )}

        {!isWindow && run && (
          <section role="region" aria-label={t("cCalls")} className="card">
            <h2>{t("cCalls")}</h2>
            <p className="num" data-testid="calls-count">{run.calls}</p>
            {run.calls === 0 && <p className="muted">{t("cNoCall")}</p>}
          </section>
        )}

        {request && (
          <section role="region" aria-label={t("cRequest")} className="card">
            <h2>{t("cRequest")}</h2>
            <ul className="messages">
              {request.messages.map((m, i) => (
                <li key={i}>
                  <code>{roleLabel(m.role)}</code>
                  <pre className="raw">{m.content}</pre>
                </li>
              ))}
            </ul>
          </section>
        )}

        {summaryRow && (
          <section role="region" aria-label={t("cSummary")} className="card">
            <h2>{t("cSummary")}</h2>
            <pre className="raw">{summaryRow.message.content}</pre>
          </section>
        )}

        <section aria-labelledby="h-conversation" className="card">
          <h2 id="h-conversation">{t("cConversation")}</h2>
          {fixture && fixture.messages.length === 0 ? (
            <p className="muted">{t("cEmpty")}</p>
          ) : (
            <ol className="ctx-list" aria-label={t("cConversation")}>
              {rows.map((r) => (
                <MessageRow key={r.key} row={r} max={maxCost} />
              ))}
            </ol>
          )}
        </section>

        {outcome?.kind === "ok" && fixture && fixture.messages.length > 0 && (
          <section aria-labelledby="h-result" className="card">
            <h2 id="h-result">{t("cResultList")}</h2>
            {resultRows.length === 0 ? (
              <p className="muted">{t("cResultNone")}</p>
            ) : (
              <ol className="ctx-list" aria-label={t("cResultList")}>
                {resultRows.map((r) => (
                  <MessageRow key={r.key} row={r} max={maxCost} />
                ))}
              </ol>
            )}
          </section>
        )}
      </main>
      <footer className="column muted">{t("cFooter")}</footer>
    </>
  );
}
