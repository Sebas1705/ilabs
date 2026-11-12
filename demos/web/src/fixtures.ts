import type { Fixture } from "@ilabs/providers";
import { compareCodeUnits } from "./sort";

const modules = import.meta.glob("../../../contracts/providers/*.json", { eager: true });

export const fixtures: Fixture[] = Object.values(modules)
  .map((m) => (m as { default: Fixture }).default)
  .sort((a, b) => compareCodeUnits(a.name, b.name));
