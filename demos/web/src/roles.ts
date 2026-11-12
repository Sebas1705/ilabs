import type { Role } from "@ilabs/providers";
import { useT, type Key } from "./i18n";

const ROLE_KEY: Record<Role, Key> = {
  system: "roleSystem",
  user: "roleUser",
  assistant: "roleAssistant",
};

/** Translated label for each message role, falling back to the raw role for an unknown one. */
export function useRoleLabel(): (role: string) => string {
  const t = useT();
  return (role) => {
    const key = ROLE_KEY[role as Role];
    return key ? t(key) : role;
  };
}
