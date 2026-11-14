import { InvalidSuiteError, parseSuite, runFixture, type CaseResult, type Fixture, type FixtureRun, type Suite, type TargetSummary } from "@ilabs/evals";
import { useEffect, useMemo, useState } from "react";
import { GraderPlayground } from "../components/GraderPlayground";
import { IssueList } from "../components/IssueList";
import { LoadErrors } from "../components/LoadErrors";
import { ResultMatrix, type CellRef } from "../components/ResultMatrix";
import { StatusBadge } from "../components/StatusBadge";
import { evalsFixtures, groupEvalsFixtures, loadErrors } from "../evalsFixtures";
import { useFill } from "../fill";
import { useT } from "../i18n";
import { formatJson } from "../json";
import { useRoleLabel } from "../roles";

const DEFAULT_FIXTURE = "status-fail-beats-pass";

/** The run state of the fixture called `name`. */
type RunState =
  | { kind: "running"; name: string }
  | { kind: "ready"; name: string; run: FixtureRun }
  | { kind: "failed"; name: string; message: string };

/** The suite as the library parses it, or null when it does not (invalid fixtures); never throws. */
function parsedSuite(fixture: Fixture): { suite: Suite } | { reason: string | null } {
  try {
    return { suite: parseSuite(fixture.suite) };
  } catch (e) {
    return { reason: e instanceof InvalidSuiteError ? e.message : null };
  }
}

function PassRate({ summary }: { summary: TargetSummary }) {
  const fill = useFill();
  const pct = Math.round(summary.passRate * 100);
  return (
    <li className="rate">
      <div className="rate-head">
        <strong>
          <code>{summary.targetId}</code>
        </strong>
        <span className="num rate-pct">{pct}%</span>
        <span className="num">{fill("eOfTotal", { passed: summary.passed, total: summary.total })}</span>
      </div>
      <span className="ratebar" aria-hidden="true">
        <span style={{ width: `${pct}%` }} />
      </span>
      <p className="muted rate-counts">{fill("eCounts", { passed: summary.passed, failed: summary.failed, errored: summary.errored })}</p>
    </li>
  );
}

function CellDetail({ result, suite }: { result: CaseResult; suite: Suite | null }) {
  const t = useT();
  const fill = useFill();
  const roleLabel = useRoleLabel();
  const evalCase = suite?.cases.find((c) => c.id === result.caseId);
  const providerFailed = result.status === "error" && result.reply === undefined;
  return (
    <section role="region" aria-label={fill("eDetail", { case: result.caseId, target: result.targetId })} className="card detail-card">
      <h3>{fill("eDetail", { case: result.caseId, target: result.targetId })}</h3>
      <p>
        <StatusBadge status={result.status} />
      </p>
      {evalCase && (
        <>
          <h4>{t("eCaseMessages")}</h4>
          {evalCase.messages.length === 0 ? (
            <p className="muted">{t("cEmpty")}</p>
          ) : (
            <ul className="messages" aria-label={t("eCaseMessages")}>
              {evalCase.messages.map((m, i) => (
                <li key={i}>
                  <code>{roleLabel(m.role)}</code>
                  <span className="message">{m.content === "" ? <span className="muted">{t("cEmptyContent")}</span> : m.content}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {providerFailed ? (
        <p className="status status-warn" data-testid="provider-error">
          {fill("eProviderError", { kind: result.error ?? "" })}
          <br />
          <span className="muted">{t("eNoGraders")}</span>
        </p>
      ) : (
        <>
          <h4>{t("eReply")}</h4>
          {result.reply === "" ? (
            <p className="muted">{t("eEmptyReply")}</p>
          ) : (
            <pre className="raw">{result.reply}</pre>
          )}
          {result.graders.length > 0 && (
            <>
              <h4>{t("eGraders")}</h4>
              <ul className="graders" aria-label={t("eGraders")}>
                {result.graders.map((g, i) => {
                  const params = evalCase?.graders[i];
                  return (
                    <li key={i} className="grader">
                      <div className="grader-head">
                        <code className="grader-kind">{g.kind}</code>
                        <StatusBadge status={g.error !== undefined ? "error" : g.passed ? "pass" : "fail"} />
                      </div>
                      {params && <code className="params">{formatParams(params)}</code>}
                      {g.error !== undefined && <p className="grader-error">{fill("eGraderError", { error: g.error })}</p>}
                      {g.issues && g.issues.length > 0 && <IssueList issues={g.issues} />}
                    </li>
                  );
                })}
              </ul>
            </>
          )}
        </>
      )}
    </section>
  );
}

/** The grader's parameters without its kind, on one line (`{"value":"hello"}`); empty text when it has none. */
function formatParams(grader: object): string {
  const rest = Object.fromEntries(Object.entries(grader).filter(([k]) => k !== "kind"));
  return Object.keys(rest).length === 0 ? "" : (JSON.stringify(rest) ?? "");
}

export function EvalsPage() {
  const t = useT();
  const fill = useFill();
  const groups = useMemo(() => groupEvalsFixtures(evalsFixtures), []);
  const [name, setName] = useState(
    evalsFixtures.some((f) => f.name === DEFAULT_FIXTURE) ? DEFAULT_FIXTURE : (evalsFixtures[0]?.name ?? ""),
  );
  const fixture = evalsFixtures.find((f) => f.name === name);
  const [stored, setState] = useState<RunState>({ kind: "running", name: "" });
  // A state that belongs to another fixture is never shown: until the effect catches up (one render after a
  // switch) the page counts as running, so old results can not be paired with the new fixture.
  const state: RunState = stored.name === name ? stored : { kind: "running", name };
  const [picked, setPicked] = useState<{ name: string; cell: CellRef } | null>(null);

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "running", name });
    if (fixture) {
      runFixture(fixture).then(
        (run) => !cancelled && setState({ kind: "ready", name, run }),
        (e: unknown) => !cancelled && setState({ kind: "failed", name, message: e instanceof Error ? e.message : String(e) }),
      );
    }
    return () => {
      cancelled = true;
    };
  }, [fixture, name]);

  const run = state.kind === "ready" ? state.run : null;
  const parsed = useMemo(() => (fixture ? parsedSuite(fixture) : { reason: null }), [fixture]);
  const suite = "suite" in parsed ? parsed.suite : null;
  const output = run?.output ?? null;
  const results = output?.results ?? [];
  const caseIds = suite ? suite.cases.map((c) => c.id) : [...new Set(results.map((r) => r.caseId))];
  const targetIds = fixture ? fixture.targets.map((x) => x.id) : [];

  // The first cell is selected until the user picks another, so the detail is visible without a click.
  const wanted = picked?.name === name ? picked.cell : null;
  const selected = results.find((r) => r.caseId === wanted?.caseId && r.targetId === wanted?.targetId) ?? results[0] ?? null;
  const busy = state.kind === "running" && fixture !== undefined;

  const count = (n: number, one: "eCaseOne" | "eTargetOne", many: "eCaseMany" | "eTargetMany") => fill(n === 1 ? one : many, { n });
  let status = "";
  if (busy) status = t("eStatusRunning");
  else if (run?.invalidSuite) status = t("eStatusInvalid");
  else if (run) {
    status = fill("eStatusDone", {
      cases: count(caseIds.length, "eCaseOne", "eCaseMany"),
      targets: count(targetIds.length, "eTargetOne", "eTargetMany"),
    });
  }

  return (
    <>
      <main id="main" className="column" tabIndex={-1} aria-busy={busy}>
        <h1 tabIndex={-1}>{t("eTitle")}</h1>
        <p className="muted">{t("eSubtitle")}</p>
        <LoadErrors errors={loadErrors} />

        <section aria-labelledby="h-run" className="block">
          <h2 id="h-run">{t("eRunHeading")}</h2>
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
          <p role="status" className={`status${run?.invalidSuite ? " status-warn" : ""}`}>
            {status}
          </p>

          {state.kind === "failed" && (
            <p role="alert" className="card error">
              {t("runError")}: {state.message}
            </p>
          )}

          {run?.invalidSuite && fixture && (
            <section role="region" aria-label={t("eInvalidHeading")} className="card">
              <h3>
                <StatusBadge status="error" /> {t("eInvalidHeading")}
              </h3>
              <p>{t("eInvalidText")}</p>
              {"reason" in parsed && parsed.reason !== null ? (
                <>
                  <p>
                    <strong>{t("eReason")}</strong> <code className="message">{parsed.reason}</code>
                  </p>
                  <p className="muted">{t("eNoCall")}</p>
                  <pre className="raw json" tabIndex={0} role="region" aria-label={t("eSuiteJson")}>
                    {formatJson(fixture.suite)}
                  </pre>
                </>
              ) : (
                <>
                  {/* The suite parsed, so the offending part is the target ids that runSuite rejected. */}
                  <p>
                    <strong>{t("eReason")}</strong> {t("eInvalidTargets")}
                  </p>
                  <p className="muted">{t("eNoCall")}</p>
                  <ul className="calls" aria-label={t("eTargetIds")}>
                    {fixture.targets.map((x, i) => (
                      <li key={i}>
                        <code>{x.id === "" ? t("eEmptyId") : x.id}</code>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </section>
          )}

          {run && !run.invalidSuite && output && (
            <>
              {targetIds.length === 0 ? (
                <p className="card muted">{t("eNoTargets")}</p>
              ) : (
                <>
                  <section role="region" aria-label={t("eSummary")} className="card">
                    <h3>{t("eSummary")}</h3>
                    <ul className="rates">
                      {output.summary.map((s) => (
                        <PassRate key={s.targetId} summary={s} />
                      ))}
                    </ul>
                  </section>
                  <div className="card">
                    <h3>{t("eMatrix")}</h3>
                    <p className="muted">{t("eCellHint")}</p>
                    <ResultMatrix
                      caseIds={caseIds}
                      targetIds={targetIds}
                      results={results}
                      selected={selected ? { caseId: selected.caseId, targetId: selected.targetId } : null}
                      onSelect={(cell) => setPicked({ name, cell })}
                    />
                  </div>
                  {selected && <CellDetail result={selected} suite={suite} />}
                </>
              )}
              <section role="region" aria-label={t("cCalls")} className="card">
                <h3>{t("cCalls")}</h3>
                {Object.keys(output.calls).length === 0 ? (
                  <p className="muted">{t("noCalls")}</p>
                ) : (
                  <ul className="calls">
                    {Object.entries(output.calls).map(([id, n]) => (
                      <li key={id}>
                        <code>{id}</code> <span className="num">{n}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}
        </section>

        <GraderPlayground />
      </main>
      <footer className="column muted">{t("eFooter")}</footer>
    </>
  );
}
