import { createContext, useContext } from "react";

export type Lang = "en" | "es";

export const dictionary = {
  en: {
    title: "Providers",
    subtitle: "Retry and fallback over AI providers, replayed from the shared contract fixtures. Nothing leaves your browser.",
    fixture: "Fixture",
    languageSwitch: "Language: switch to Spanish",
    theme: "Theme",
    themeAuto: "Auto",
    themeLight: "Light",
    themeDark: "Dark",
    setup: "Setup",
    kind: "Strategy",
    kindRetry: "Retry",
    kindFallback: "Fallback",
    maxAttempts: "Max attempts",
    baseDelay: "Base delay",
    maxDelay: "Max delay",
    request: "Request",
    scripts: "Provider scripts",
    scriptHint: "Call N consumes entry N. After the script ends, every call fails with unavailable.",
    noProviders: "No providers configured.",
    emptyScript: "Empty script",
    entryResponse: "responds",
    entryError: "fails with",
    retryAfter: "retry after",
    timeline: "Timeline",
    callTo: "call",
    wait: "wait",
    outcomeOk: "success",
    outcomeRateLimit: "rate limited",
    outcomeTimeout: "timed out",
    outcomeInvalid: "invalid output",
    outcomeUnavailable: "unavailable",
    result: "Result",
    answeredBy: "answered by",
    failedWith: "Failed with",
    calls: "Calls per provider",
    noCalls: "No calls were made.",
    skip: "Skip to content",
    runError: "Could not run the fixture",
    footer: "Fixtures live in contracts/providers and are shared with the Kotlin implementation.",
  },
  es: {
    title: "Proveedores",
    subtitle: "Reintentos y respaldo sobre proveedores de IA, reproducidos desde los escenarios del contrato compartido. Nada sale de tu navegador.",
    fixture: "Escenario",
    languageSwitch: "Idioma: cambiar a inglés",
    theme: "Tema",
    themeAuto: "Auto",
    themeLight: "Claro",
    themeDark: "Oscuro",
    setup: "Configuración",
    kind: "Estrategia",
    kindRetry: "Reintento",
    kindFallback: "Respaldo",
    maxAttempts: "Intentos máximos",
    baseDelay: "Espera base",
    maxDelay: "Espera máxima",
    request: "Petición",
    scripts: "Guiones de los proveedores",
    scriptHint: "La llamada N consume la entrada N. Al acabar el guion, toda llamada falla con unavailable.",
    noProviders: "Sin proveedores configurados.",
    emptyScript: "Guion vacío",
    entryResponse: "responde",
    entryError: "falla con",
    retryAfter: "reintentar tras",
    timeline: "Cronología",
    callTo: "llamada",
    wait: "espera",
    outcomeOk: "correcta",
    outcomeRateLimit: "límite de peticiones",
    outcomeTimeout: "tiempo agotado",
    outcomeInvalid: "salida no válida",
    outcomeUnavailable: "no disponible",
    result: "Resultado",
    answeredBy: "respondió",
    failedWith: "Falló con",
    calls: "Llamadas por proveedor",
    noCalls: "No hubo llamadas.",
    skip: "Saltar al contenido",
    runError: "No se pudo ejecutar el escenario",
    footer: "Los escenarios viven en contracts/providers y se comparten con la implementación de Kotlin.",
  },
} as const;

export type Dict = (typeof dictionary)["en"];
export type Key = keyof Dict;

const STORAGE_KEY = "ilabs.lang";

export function detectLang(): Lang {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === "en" || saved === "es") return saved;
  } catch {
    /* storage unavailable */
  }
  const nav = typeof navigator === "undefined" ? "en" : navigator.language;
  return nav.toLowerCase().startsWith("es") ? "es" : "en";
}

export function saveLang(lang: Lang): void {
  try {
    localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    /* storage unavailable */
  }
}

export const LangContext = createContext<Lang>("en");

export function useT(): (key: Key) => string {
  const lang = useContext(LangContext);
  return (key) => dictionary[lang][key];
}
