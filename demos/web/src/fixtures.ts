import type { Fixture } from "@ilabs/providers";

const modules = import.meta.glob("../../../contracts/providers/*.json", { eager: true });

export const fixtures: Fixture[] = Object.values(modules)
  .map((m) => (m as { default: Fixture }).default)
  .sort((a, b) => a.name.localeCompare(b.name));
