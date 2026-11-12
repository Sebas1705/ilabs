import { useT } from "../i18n";
import { ROUTES, type RouteDef } from "../routes";

export function Nav({ current, routes = ROUTES }: { current: RouteDef; routes?: readonly RouteDef[] }) {
  const t = useT();
  return (
    <nav aria-label={t("navLabel")} className="nav">
      {routes.map((r) => (
        <a key={r.id} href={r.hash} aria-current={r.id === current.id ? "page" : undefined}>
          {t(r.navKey)}
        </a>
      ))}
    </nav>
  );
}
