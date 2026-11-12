import type { Issue } from "@ilabs/structured";
import { useT, type Key } from "../i18n";

const ISSUE_KEY: Record<Issue["code"], Key> = {
  type: "issueType",
  enum: "issueEnum",
  minimum: "issueMinimum",
  maximum: "issueMaximum",
  minLength: "issueMinLength",
  maxLength: "issueMaxLength",
  minItems: "issueMinItems",
  maxItems: "issueMaxItems",
  required: "issueRequired",
  additionalProperties: "issueAdditional",
  no_json: "extractNoJson",
  invalid_json: "extractInvalidJson",
};

/** Each issue as path, translated label and raw code; the root path "" reads as "(root)". */
export function IssueList({ issues }: { issues: Issue[] }) {
  const t = useT();
  return (
    <ul className="issues" aria-label={t("issues")}>
      {issues.map((issue, i) => (
        <li key={i}>
          {issue.path === "" ? <span className="path">{t("rootPath")}</span> : <code className="path">{issue.path}</code>}
          <span className="issue-label">{t(ISSUE_KEY[issue.code])}</span>
          <code className="issue-code">{issue.code}</code>
        </li>
      ))}
    </ul>
  );
}
