import type { Status } from "@ilabs/evals";
import { useT } from "../i18n";
import { STATUS } from "./statusLabel";

/** A coloured badge that always carries a glyph and the status word, so colour is never the only cue. */
export function StatusBadge({ status }: { status: Status }) {
  const t = useT();
  const s = STATUS[status];
  return (
    <span className={`badge badge-${s.tone}`} data-status={status}>
      <span aria-hidden="true">{s.glyph}</span>
      <span className="badge-label">{t(s.key)}</span>
    </span>
  );
}
