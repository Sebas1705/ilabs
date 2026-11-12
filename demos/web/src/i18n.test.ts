import { dictionary } from "./i18n";

describe("dictionary", () => {
  it("has identical key sets in en and es", () => {
    expect(Object.keys(dictionary.es).sort()).toEqual(Object.keys(dictionary.en).sort());
  });

  it("has a non-empty value for every key in both languages", () => {
    for (const lang of ["en", "es"] as const) {
      for (const [key, value] of Object.entries(dictionary[lang])) {
        expect(value.trim(), `${lang}.${key}`).not.toBe("");
      }
    }
  });
});
