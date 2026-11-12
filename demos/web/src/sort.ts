/** Orders strings by UTF-16 code unit, like Kotlin's String.compareTo; unlike localeCompare it does not depend on the locale. */
export function compareCodeUnits(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
