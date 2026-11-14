import { runFixture, type Fixture, type FixtureRun } from "@ilabs/providers";
import { useEffect, useState } from "react";
import { OutcomeBadge, Timeline } from "../components/Timeline";
import { LoadErrors } from "../components/LoadErrors";
import { fixtures, loadErrors } from "../fixtures";
import { useT } from "../i18n";

function Setup({ fixture }: { fixture: Fixture }) {
  const t = useT();
  const { setup } = fixture;
  const rows: [string, string][] = [[t("kind"), setup.kind === "retry" ? t("kindRetry") : t("kindFallback")]];
  if (setup.maxAttempts !== undefined) rows.push([t("maxAttempts"), String(setup.maxAttempts)]);
  if (setup.baseDelayMs !== undefined) rows.push([t("baseDelay"), `${setup.baseDelayMs} ms`]);
  if (setup.maxDelayMs !== undefined) rows.push([t("maxDelay"), `${setup.maxDelayMs} ms`]);
  const prompt = fixture.request.messages.map((m) => `${m.role}: ${m.content}`).join(" / ");
  rows.push([t("request"), prompt]);
  return (
    <section aria-labelledby="h-setup" className="card">
      <h2 id="h-setup">{t("setup")}</h2>
      <dl className="kv">
        {rows.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      <h3>{t("scripts")}</h3>
      <p className="muted">{t("scriptHint")}</p>
      {fixture.providers.length === 0 ? (
        <p>{t("noProviders")}</p>
      ) : (
        fixture.providers.map((p) => (
          <div key={p.id} className="script">
            <strong>{p.id}</strong>
            {p.script.length === 0 ? (
              <p className="muted">{t("emptyScript")}</p>
            ) : (
              <ol>
                {p.script.map((e, i) => (
                  <li key={i}>
                    {"response" in e ? (
                      <>
                        {t("entryResponse")} <q>{e.response}</q>
                      </>
                    ) : (
                      <>
                        {t("entryError")} <OutcomeBadge outcome={e.error.kind} />
                        {e.error.retryAfterMs !== undefined && e.error.retryAfterMs !== null && (
                          <span className="muted">
                            {" "}
                            ({t("retryAfter")} {e.error.retryAfterMs} ms)
                          </span>
                        )}
                      </>
                    )}
                  </li>
                ))}
              </ol>
            )}
          </div>
        ))
      )}
    </section>
  );
}

function Result({ run }: { run: FixtureRun }) {
  const t = useT();
  const { result, calls } = run;
  const entries = Object.entries(calls);
  return (
    <section aria-labelledby="h-result" className="card">
      <h2 id="h-result">{t("result")}</h2>
      {"error" in result ? (
        <p className="result">
          {t("failedWith")} <OutcomeBadge outcome={result.error.kind} />
        </p>
      ) : (
        <p className="result">
          <OutcomeBadge outcome="ok" /> <q>{result.text}</q>{" "}
          <span className="muted">
            {t("answeredBy")} <strong>{result.provider}</strong>
          </span>
        </p>
      )}
      <h3>{t("calls")}</h3>
      {entries.length === 0 ? (
        <p className="muted">{t("noCalls")}</p>
      ) : (
        <ul className="calls">
          {entries.map(([id, n]) => (
            <li key={id}>
              <strong>{id}</strong>: <span className="num">{n}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function ProvidersPage() {
  const t = useT();
  const [name, setName] = useState(fixtures[0]?.name ?? "");
  const [run, setRun] = useState<FixtureRun | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const fixture = fixtures.find((f) => f.name === name);

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
        <h1 tabIndex={-1}>{t("title")}</h1>
        <p className="muted">{t("subtitle")}</p>
        <LoadErrors errors={loadErrors} />
        <label className="field">
          <span>{t("fixture")}</span>
          <select value={name} onChange={(e) => setName(e.target.value)}>
            {fixtures.map((f) => (
              <option key={f.name} value={f.name}>
                {f.name}
              </option>
            ))}
          </select>
        </label>
        {fixture && <Setup fixture={fixture} />}
        {runError !== null && (
          <p role="alert" className="card error">
            {t("runError")}: {runError}
          </p>
        )}
        {run && (
          <>
            <section aria-labelledby="h-timeline" className="card">
              <h2 id="h-timeline">{t("timeline")}</h2>
              <Timeline events={run.events} />
            </section>
            <Result run={run} />
          </>
        )}
      </main>
      <footer className="column muted">{t("footer")}</footer>
    </>
  );
}

