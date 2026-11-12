import type { AttemptTrace } from "@ilabs/structured";
import { formatJson } from "../json";
import { useT, type Key } from "../i18n";
import { useRoleLabel } from "../roles";
import { IssueList } from "./IssueList";

const EXTRACT_KEY: Record<string, Key> = {
  ok: "extractOk",
  no_json: "extractNoJson",
  invalid_json: "extractInvalidJson",
};

/** Extraction outcome: a text label plus the raw code, never colour alone. */
function ExtractionBadge({ code }: { code: string }) {
  const t = useT();
  const key = EXTRACT_KEY[code];
  return (
    <span className={`badge badge-${code === "ok" ? "ok" : "err"}`}>
      <span aria-hidden="true">{code === "ok" ? "✓" : "✕"}</span>
      <code>{code}</code>
      {key && <span className="badge-label">{t(key)}</span>}
    </span>
  );
}

function Reply({ text }: { text: string }) {
  const t = useT();
  if (text.length === 0) return <p className="muted">{t("replyEmpty")}</p>;
  return (
    <>
      {text.trim() === "" && <p className="muted">{t("replyBlank")}</p>}
      <pre className="raw">{text}</pre>
    </>
  );
}

function Attempt({ n, trace }: { n: number; trace: AttemptTrace }) {
  const t = useT();
  const accepted = trace.extracted.ok && trace.issues.length === 0;
  return (
    <>
      <div className="attempt-head">
        <h3>
          {t("attempt")} {n}
        </h3>
        <span className={`badge badge-${accepted ? "ok" : "err"}`}>
          <span aria-hidden="true">{accepted ? "✓" : "✕"}</span>
          <span className="badge-label">{t(accepted ? "verdictAccepted" : "verdictRejected")}</span>
        </span>
      </div>
      <h4>{t("reply")}</h4>
      <Reply text={trace.reply} />
      <h4>{t("extraction")}</h4>
      <p>
        <ExtractionBadge code={trace.extracted.ok ? "ok" : trace.extracted.code} />
      </p>
      {trace.extracted.ok && (
        <>
          <h4>{t("extractedJson")}</h4>
          <pre className="raw json" tabIndex={0} role="region" aria-label={`${t("regionExtracted")} (${t("attempt")} ${n})`}>
            {formatJson(trace.extracted.value)}
          </pre>
        </>
      )}
      <h4>{t("issues")}</h4>
      {trace.issues.length === 0 ? <p className="muted">{t("noIssues")}</p> : <IssueList issues={trace.issues} />}
    </>
  );
}

const PROVIDER_KIND_KEY: Record<string, Key> = {
  rate_limit: "outcomeRateLimit",
  timeout: "outcomeTimeout",
  invalid_output: "outcomeInvalid",
  unavailable: "outcomeUnavailable",
};

/** The call that raised a provider error: it has no reply, so it is a row of its own, in text (never colour alone). */
function ProviderFailure({ n, kind }: { n: number; kind: string }) {
  const t = useT();
  const key = PROVIDER_KIND_KEY[kind];
  return (
    <div className="attempt attempt-failed">
      <h3>
        <span aria-hidden="true">✕ </span>
        {t("attempt")} {n}: {t("providerFailed")} {key ? `${t(key)} (${kind})` : kind}
      </h3>
    </div>
  );
}

/** Messages appended after one attempt: the assistant reply and the repair request, in the run's own roles. */
const MESSAGES_PER_REPAIR = 2;

/**
 * `failure` is the provider error raised by call `failure.attempt`, which has no trace entry.
 * `appended` holds the roles of every message appended for the last call, in order; the repair
 * after attempt k is the k-th group of them.
 */
export function AttemptTimeline({
  trace,
  failure,
  appended = [],
}: {
  trace: AttemptTrace[];
  failure?: { attempt: number; kind: string };
  appended?: readonly string[];
}) {
  const t = useT();
  const roleLabel = useRoleLabel();
  if (trace.length === 0 && !failure) return <p className="muted">{t("noAttempts")}</p>;
  const repair = (index: number) => {
    const roles = appended.slice(index * MESSAGES_PER_REPAIR, (index + 1) * MESSAGES_PER_REPAIR).map(roleLabel);
    return (
      <div className="repair">
        <strong>{t("repairRequested")}</strong>
        <span className="muted">
          {" "}
          {t("repairDetail")}
          {roles.length > 0 && ` ${roles.join(", ")}`}
        </span>
      </div>
    );
  };
  return (
    <ol className="attempts" aria-label={t("attempts")}>
      {trace.map((a, i) => (
        <li key={i} className="attempt-item">
          <div className="attempt">
            <Attempt n={i + 1} trace={a} />
          </div>
          {(i < trace.length - 1 || failure) && repair(i)}
        </li>
      ))}
      {failure && (
        <li className="attempt-item">
          <ProviderFailure n={failure.attempt} kind={failure.kind} />
        </li>
      )}
    </ol>
  );
}
