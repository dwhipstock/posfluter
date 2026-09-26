// One UI string in one locale — shared by the client i18n context and server
// code (root layout metadata, the /staff-app fallback page).
import { messages, type Locale, type MsgKey } from "./messages";
import { es } from "./messages.es";

type Vars = Record<string, string | number>;

/** [key] in [locale], with {placeholders} filled from [vars]; English when a table lacks it. */
export function translate(locale: Locale, key: MsgKey, vars?: Vars): string {
  let s: string;
  if (locale === "es") s = es[key] ?? messages[key]?.en ?? key;
  else s = messages[key]?.[locale] ?? key;
  if (vars) s = s.replace(/\{(\w+)\}/g, (_, k: string) => String(vars[k] ?? `{${k}}`));
  return s;
}
