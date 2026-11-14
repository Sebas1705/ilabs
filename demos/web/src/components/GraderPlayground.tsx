import { evaluateGrader } from "@ilabs/evals";
import { useId, useMemo, useState } from "react";
import { DEFAULT_PARAMS, PURE_KINDS, USES, buildGrader, type GraderParams, type PureKind } from "../graderParams";
import { useT } from "../i18n";
import { IssueList } from "./IssueList";
import { StatusBadge } from "./StatusBadge";

/** Try one pure grader on any reply. The verdict is recomputed on every keystroke by `evaluateGrader`. */
export function GraderPlayground() {
  const t = useT();
  const uid = useId();
  const [params, setParams] = useState<GraderParams>(DEFAULT_PARAMS);
  const [reply, setReply] = useState("Hello");
  const set = <K extends keyof GraderParams>(key: K, value: GraderParams[K]) => setParams((p) => ({ ...p, [key]: value }));

  const built = useMemo(() => buildGrader(params), [params]);
  const result = useMemo(() => (built.ok ? evaluateGrader(built.grader, reply) : null), [built, reply]);

  const uses = USES[params.kind];
  const errorId = `${uid}-error`;
  // Which field the parameter error belongs to, so it can be marked invalid and described by the message.
  const bad = !built.ok ? built.error : null;
  const invalidField = (field: "value" | "values" | "bounds" | "schema" | "jsonValue") => {
    if (bad === null || !uses[field]) return {};
    return { "aria-invalid": true as const, "aria-describedby": errorId };
  };

  let status: string;
  if (!built.ok) status = t(built.error);
  else if (result) status = result.passed ? t("eStatusPass") : t("eStatusFail");
  else status = t("ePlayNoVerdict");

  return (
    <section role="region" aria-labelledby={`${uid}-h`} className="card">
      <h2 id={`${uid}-h`}>{t("ePlayHeading")}</h2>
      <p className="muted">{t("ePlaySubtitle")}</p>

      <label className="field">
        <span>{t("ePlayKind")}</span>
        <select value={params.kind} onChange={(e) => set("kind", e.target.value as PureKind)}>
          {PURE_KINDS.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
      </label>

      {uses.value && (
        <label className="field">
          <span>{t("ePlayValue")}</span>
          <input type="text" value={params.value} onChange={(e) => set("value", e.target.value)} spellCheck={false} autoComplete="off" />
        </label>
      )}
      {uses.values && (
        <div className="field">
          <label htmlFor={`${uid}-values`}>{t("ePlayValues")}</label>
          <textarea
            id={`${uid}-values`}
            rows={3}
            value={params.values}
            onChange={(e) => set("values", e.target.value)}
            spellCheck={false}
            {...(bad === null ? { "aria-describedby": `${uid}-values-hint` } : invalidField("values"))}
          />
          <span id={`${uid}-values-hint`} className="hint">{t("ePlayValuesHint")}</span>
        </div>
      )}
      {uses.bounds && (
        <>
          <div className="field-pair">
            <div className="field">
              <label htmlFor={`${uid}-min`}>{t("ePlayMin")}</label>
              <input
                id={`${uid}-min`}
                type="text"
                inputMode="numeric"
                value={params.min}
                onChange={(e) => set("min", e.target.value)}
                {...(bad === null ? { "aria-describedby": `${uid}-bounds-hint` } : invalidField("bounds"))}
              />
            </div>
            <div className="field">
              <label htmlFor={`${uid}-max`}>{t("ePlayMax")}</label>
              <input
                id={`${uid}-max`}
                type="text"
                inputMode="numeric"
                value={params.max}
                onChange={(e) => set("max", e.target.value)}
                {...(bad === null ? { "aria-describedby": `${uid}-bounds-hint` } : invalidField("bounds"))}
              />
            </div>
          </div>
          <p id={`${uid}-bounds-hint`} className="hint">{t("ePlayBoundHint")}</p>
        </>
      )}
      {uses.schema && (
        <label className="field">
          <span>{t("ePlaySchema")}</span>
          <textarea rows={6} className="mono" value={params.schema} onChange={(e) => set("schema", e.target.value)} spellCheck={false} {...invalidField("schema")} />
        </label>
      )}
      {uses.jsonValue && (
        <label className="field">
          <span>{t("ePlayJsonValue")}</span>
          <textarea rows={3} className="mono" value={params.jsonValue} onChange={(e) => set("jsonValue", e.target.value)} spellCheck={false} {...invalidField("jsonValue")} />
        </label>
      )}
      {uses.options && (
        <div className="checks">
          <label className="check">
            <input type="checkbox" checked={params.trim} onChange={(e) => set("trim", e.target.checked)} />
            <span>{t("ePlayTrim")}</span>
          </label>
          <label className="check">
            <input type="checkbox" checked={params.ignoreCase} onChange={(e) => set("ignoreCase", e.target.checked)} />
            <span>{t("ePlayIgnoreCase")}</span>
          </label>
        </div>
      )}

      <label className="field">
        <span>{t("ePlayReply")}</span>
        <textarea rows={4} value={reply} onChange={(e) => setReply(e.target.value)} spellCheck={false} />
      </label>

      <h3>{t("ePlayVerdict")}</h3>
      <div id={errorId} role="status" className={`verdict${built.ok ? "" : " status-warn"}`}>
        {built.ok && result ? (
          <StatusBadge status={result.passed ? "pass" : "fail"} />
        ) : (
          <span>{status}</span>
        )}
        {!built.ok && built.detail && (
          <code className="detail">{built.detail}</code>
        )}
      </div>
      {result?.issues && result.issues.length > 0 && <IssueList issues={result.issues} />}
      <p className="muted">{t("ePlayJudgeNote")}</p>
    </section>
  );
}
