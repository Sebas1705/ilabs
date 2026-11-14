import type { CaseResult, TargetSummary } from "./types.js";

/** One entry per target in order of first appearance; `passRate = passed / total`. */
export function summarize(results: CaseResult[]): TargetSummary[] {
  const byTarget = new Map<string, TargetSummary>();
  for (const r of results) {
    let s = byTarget.get(r.targetId);
    if (s === undefined) {
      s = { targetId: r.targetId, total: 0, passed: 0, failed: 0, errored: 0, passRate: 0 };
      byTarget.set(r.targetId, s);
    }
    s.total++;
    if (r.status === "pass") s.passed++;
    else if (r.status === "fail") s.failed++;
    else s.errored++;
  }
  const out = [...byTarget.values()];
  for (const s of out) s.passRate = s.passed / s.total;
  return out;
}
