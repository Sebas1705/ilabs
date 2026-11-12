import { useEffect, useRef, useState, type MouseEvent } from "react";
import { Nav } from "./components/Nav";
import { LangContext, detectLang, saveLang, useT, type Lang } from "./i18n";
import { useRoute } from "./route";
import { ROUTES, type RouteDef } from "./routes";
import { useTheme, type Theme } from "./theme";

/** Focus main in JS: following the #main hash would change the route. */
function skipToMain(e: MouseEvent<HTMLAnchorElement>) {
  e.preventDefault();
  const main = document.getElementById("main");
  main?.focus();
  main?.scrollIntoView?.();
}

function Shell({ lang, setLang, routes }: { lang: Lang; setLang: (l: Lang) => void; routes: readonly RouteDef[] }) {
  const t = useT();
  const route = useRoute(routes);
  const [theme, setTheme] = useTheme();

  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  useEffect(() => {
    document.title = `iLabs · ${t(route.titleKey)}`;
  }, [lang, route, t]);

  const Page = route.component;
  // After a page change (not on first load) move focus to the new page's heading and back to the top.
  const shownId = useRef(route.id);
  useEffect(() => {
    if (shownId.current === route.id) return;
    shownId.current = route.id;
    document.querySelector<HTMLElement>("main h1")?.focus({ preventScroll: true });
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    window.scrollTo?.({ top: 0, left: 0, behavior: reduced ? "instant" : "smooth" });
  }, [route.id]);

  const other: Lang = lang === "en" ? "es" : "en";
  return (
    <>
      <a className="skip" href="#main" onClick={skipToMain}>
        {t("skip")}
      </a>
      <header className="top">
        <div className="bar">
          <span className="brand">iLabs</span>
          <Nav current={route} routes={routes} />
          <div className="controls">
            <label className="inline">
              <span>{t("theme")}</span>
              <select value={theme} onChange={(e) => setTheme(e.target.value as Theme)}>
                <option value="auto">{t("themeAuto")}</option>
                <option value="light">{t("themeLight")}</option>
                <option value="dark">{t("themeDark")}</option>
              </select>
            </label>
            <button type="button" className="btn" aria-label={`${lang.toUpperCase()} → ${other.toUpperCase()}, ${t("languageSwitch")}`} onClick={() => setLang(other)}>
              {lang.toUpperCase()} {"→"} {other.toUpperCase()}
            </button>
          </div>
        </div>
      </header>
      <Page />
    </>
  );
}

export function App({ routes = ROUTES }: { routes?: readonly RouteDef[] }) {
  const [lang, setLangState] = useState<Lang>(detectLang);
  const setLang = (l: Lang) => {
    setLangState(l);
    saveLang(l);
  };
  return (
    <LangContext.Provider value={lang}>
      <Shell lang={lang} setLang={setLang} routes={routes} />
    </LangContext.Provider>
  );
}
