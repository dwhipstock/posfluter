// One UI string in one locale — shared by the client i18n context and server
// code (root layout metadata, the /staff-app fallback page).
import { messages, type Locale, type MsgKey } from "./messages";
import { es } from "./messages.es";
import { de } from "./messages.de";

type Vars = Record<string, string | number>;

/** [key] in [locale], with {placeholders} filled from [vars]; English when a table lacks it. */
export function translate(locale: Locale, key: MsgKey, vars?: Vars): string {
  let s: string;
  if (locale === "es") s = es[key] ?? messages[key]?.en ?? key;
  else if (locale === "de") s = de[key] ?? messages[key]?.en ?? key;
  else s = messages[key]?.[locale] ?? key;
  if (vars) s = s.replace(/\{(\w+)\}/g, (_, k: string) => String(vars[k] ?? `{${k}}`));
  return s;
}

/**
 * A data-driven name (item, category, zone): French reads French; any other
 * locale reads the store's own name in it ([names], e.g. es/de from the
 * tablet's translations) when there is one, else English, else French.
 */
export function pickName(
  locale: Locale,
  fr?: string | null,
  en?: string | null,
  names?: Record<string, string> | null
): string {
  if (locale === "fr") return fr || en || "";
  const own = locale === "en" ? undefined : names?.[locale]?.trim();
  return own || en || fr || "";
}
