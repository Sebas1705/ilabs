import { runFixture, type Fixture, type FixtureRun } from "@ilabs/structured";
import { useEffect, useMemo, useState } from "react";
import { AttemptTimeline } from "../components/AttemptTimeline";
import { IssueList } from "../components/IssueList";
import { OutcomeBadge } from "../components/Timeline";
import { useT } from "../i18n";
import { useRoleLabel } from "../roles";
import { formatJson } from "../json";
import { groupFixtures, structuredFixtures } from "../structuredFixtures";

const DEFAULT_FIXTURE = "loop-repair-second";

function Inputs({ fixture }: { fixture: Fixture }) {
  const t = useT();
  return (
    <>
      <section aria-labelledby="h-schema" className="card">
        <h2 id="h-schema">{t("schema")}</h2>
        <pre className="raw json" tabIndex={0} role="region" aria-label={t("regionSchema")}>
          {formatJson(fixture.schema)}
        </pre>
      </section>
      <section aria-labelledby="h-request" className="card">
        <h2 id="h-request">{t("request")}</h2>
        <ul className="messages">
          {fixture.request.messages.map((m, i) => (
            <li key={i}>
              <code>{m.role}</code>
              <span className="message">{m.content}</span>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

function Setup({ fixture }: { fixture: Fixture }) {
  const t = useT();
  const { script } = fixture.provider;
  return (
    <section aria-labelledby="h-setup" className="card">
      <h2 id="h-setup">{t("setup")}</h2>
      <dl className="kv">
        <div>
          <dt>{t("maxRepairs")}</dt>
          <dd>{fixture.setup.maxRepairs}</dd>
        </div>
        <div>
          <dt>{t("providerId")}</dt>
          <dd>{fixture.provider.id}</dd>
        </div>
      </dl>
      <h3>{t("scripts")}</h3>
      <p className="muted">{t("scriptHint")}</p>
      {script.length === 0 ? (
        <p className="muted">{t("emptyScript")}</p>
      ) : (
        <ol className="script-list">
          {script.map((e, i) => (
            <li key={i}>
              {e.error ? (
                <>
                  {t("entryError")} <OutcomeBadge outcome={e.error.kind} />
                  {e.error.retryAfterMs !== undefined && e.error.retryAfterMs !== null && (
                    <span className="muted">
                      {" "}
                      ({t("retryAfter")} {e.error.retryAfterMs} ms)
                    </span>
                  )}
                </>
              ) : (
                <>
                  {t("entryResponse")}
                  <pre className="raw">{e.response}</pre>
                </>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** A provider error (the expected error carries no issues) raised by a call that left no trace entry. */
function providerFailure(run: FixtureRun): { attempt: number; kind: string } | undefined {
  const { result } = run;
  if (!("error" in result) || result.error.issues !== undefined) return undefined;
  return run.attempts > run.trace.length ? { attempt: run.attempts, kind: result.error.kind } : undefined;
}

function Result({ run }: { run: FixtureRun }) {
  const t = useT();
  const roleLabel = useRoleLabel();
  const { result } = run;
  return (
    <section aria-labelledby="h-result" className="card">
      <h2 id="h-result">{t("result")}</h2>
      {"error" in result ? (
        <>
          <p className="result">
            {t("failedWith")} <OutcomeBadge outcome={result.error.kind} />
          </p>
          {result.error.issues && result.error.issues.length > 0 && <IssueList issues={result.error.issues} />}
        </>
      ) : (
        <>
          <p className="result">
            <OutcomeBadge outcome="ok" /> {t("finalValue")}
          </p>
          <pre className="raw json" tabIndex={0} role="region" aria-label={t("regionFinal")}>
            {formatJson(result.value)}
          </pre>
        </>
      )}
      <dl className="kv">
        <div>
          <dt>{t("attemptsCount")}</dt>
          <dd>{run.attempts}</dd>
        </div>
        <div>
          <dt>{t("appended")}</dt>
          <dd>{run.appended.length === 0 ? t("appendedNone") : run.appended.map(roleLabel).join(", ")}</dd>
        </div>
      </dl>
    </section>
  );
}

export function StructuredPage() {
  const t = useT();
  const groups = useMemo(() => groupFixtures(structuredFixtures), []);
  const [name, setName] = useState(
    structuredFixtures.some((f) => f.name === DEFAULT_FIXTURE) ? DEFAULT_FIXTURE : (structuredFixtures[0]?.name ?? ""),
  );
  const [run, setRun] = useState<FixtureRun | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const fixture = structuredFixtures.find((f) => f.name === name);

  useEffect(() => {
    let cancelled = false;
    setRun(null);
    setRunError(null);
    if (fixture) {
      runFixture(fixture).then(
        (r) => !cancelled && setRun(r),
        (e: unknown) => !cancelled && setRunError(e instanceof Error ? e.message : String(e)),
      );
    }
    return () => {
      cancelled = true;
    };
  }, [fixture]);

  return (
    <>
      <main id="main" className="column" tabIndex={-1}>
        <h1 tabIndex={-1}>{t("sTitle")}</h1>
        <p className="muted">{t("sSubtitle")}</p>
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
          <>
            <Inputs fixture={fixture} />
            <Setup fixture={fixture} />
          </>
        )}
        {runError !== null && (
          <p role="alert" className="card error">
            {t("runError")}: {runError}
          </p>
        )}
        {run && (
          <>
            <section aria-labelledby="h-attempts" className="card">
              <h2 id="h-attempts">{t("attempts")}</h2>
              <AttemptTimeline trace={run.trace} failure={providerFailure(run)} appended={run.appended} />
            </section>
            <Result run={run} />
          </>
        )}
      </main>
      <footer className="column muted">{t("sFooter")}</footer>
    </>
  );
}
