import { useT } from "../i18n";

/** Non-blocking note listing the fixture files that could not be loaded; renders nothing when there are none. */
export function LoadErrors({ errors }: { errors: string[] }) {
  const t = useT();
  if (errors.length === 0) return null;
  return (
    <div className="card note" data-testid="load-errors">
      <p>{t("cLoadWarning")}</p>
      <ul>
        {errors.map((e) => (
          <li key={e} className="message">
            <code>{e}</code>
          </li>
        ))}
      </ul>
    </div>
  );
}
