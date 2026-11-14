/** A decorative bar sized by token cost. The cost is always also written as text next to it. */
export function TokenBar({ cost, max }: { cost: number; max: number }) {
  const pct = max > 0 ? Math.min(100, Math.round((cost / max) * 100)) : 0;
  return (
    <span className="tokenbar" aria-hidden="true">
      <span style={{ width: `${pct}%` }} />
    </span>
  );
}
