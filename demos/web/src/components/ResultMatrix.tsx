import type { CaseResult } from "@ilabs/evals";
import { useT } from "../i18n";
import { useFill } from "../fill";
import { StatusBadge } from "./StatusBadge";
import { useStatusLabel } from "./statusLabel";

export interface CellRef {
  caseId: string;
  targetId: string;
}

const cellKey = (caseId: string, targetId: string): string => `${caseId}\u0000${targetId}`;

/**
 * Cases (rows) by targets (columns); every cell is a button that selects it. The table sits in a focusable,
 * named scroll region so a wide matrix scrolls inside it and never makes the page scroll sideways.
 */
export function ResultMatrix({
  caseIds,
  targetIds,
  results,
  selected,
  onSelect,
}: {
  caseIds: string[];
  targetIds: string[];
  results: CaseResult[];
  selected: CellRef | null;
  onSelect: (cell: CellRef) => void;
}) {
  const t = useT();
  const fill = useFill();
  const statusLabel = useStatusLabel();
  const byCell = new Map(results.map((r) => [cellKey(r.caseId, r.targetId), r]));
  return (
    <div className="matrix-scroll" role="region" aria-label={t("eMatrix")} tabIndex={0}>
      <table className="matrix">
        <thead>
          <tr>
            <th scope="col">{t("eCaseHeader")}</th>
            {targetIds.map((id) => (
              <th key={id} scope="col">
                <code>{id}</code>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {caseIds.map((caseId) => (
            <tr key={caseId}>
              <th scope="row">
                <code>{caseId}</code>
              </th>
              {targetIds.map((targetId) => {
                const result = byCell.get(cellKey(caseId, targetId));
                if (!result) return <td key={targetId} />;
                const isSelected = selected?.caseId === caseId && selected.targetId === targetId;
                return (
                  <td key={targetId}>
                    <button
                      type="button"
                      className="cell"
                      aria-pressed={isSelected}
                      aria-label={fill("eCellName", { case: caseId, target: targetId, status: statusLabel(result.status) })}
                      onClick={() => onSelect({ caseId, targetId })}
                    >
                      <StatusBadge status={result.status} />
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
