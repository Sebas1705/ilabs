import { useT, type Key } from "./i18n";

/** Replaces each `{name}` in a dictionary template; an unknown name is left as written. */
export function fillText(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
}

export function useFill(): (key: Key, vars: Record<string, string | number>) => string {
  const t = useT();
  return (key, vars) => fillText(t(key), vars);
}
