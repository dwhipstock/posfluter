// One UI string in one locale — shared by the client i18n context and server
// code (root layout metadata, the /staff-app fallback page).
import { messages, type Locale, type MsgKey } from "./messages";
import { es } from "./messages.es";
import { de } from "./messages.de";
import { af } from "./messages.af";
import { th } from "./messages.th";

type Vars = Record<string, string | number>;

const tables: Partial<Record<Locale, Record<MsgKey, string>>> = { es, de, af, th };

/** [key] in [locale], with {placeholders} filled from [vars]; English when a table lacks it. */
export function translate(locale: Locale, key: MsgKey, vars?: Vars): string {
  const table = tables[locale];
  let s: string = table ? (table[key] ?? messages[key]?.en ?? key) : (messages[key]?.[locale as "fr" | "en"] ?? key);
  if (vars) s = s.replace(/\{(\w+)\}/g, (_, k: string) => String(vars[k] ?? `{${k}}`));
  return s;
}

/**
 * The languages that can live in the catalog's second name slot (`name_fr`,
 * `label_fr`, …). The slot is named for French, but it holds the store's
 * DATA language: French at a French + English store, Thai at a Thai +
 * English store. English always has its own slot.
 */
export const DATA_SLOT_LOCALES = ["fr", "th"] as const;
export type DataLocale = (typeof DATA_SLOT_LOCALES)[number];

/**
 * The data language of a client or store: an explicit setting when it is a
 * slot language, else the first of its [locales] that is one, else French
 * (the historical meaning — every existing store).
 */
export function dataLocaleOf(locales: readonly string[] | null | undefined, explicit?: string | null): DataLocale {
  const slots = DATA_SLOT_LOCALES as readonly string[];
  if (explicit && slots.includes(explicit)) return explicit as DataLocale;
  const first = (locales ?? []).find((l) => slots.includes(l));
  return (first as DataLocale | undefined) ?? "fr";
}

/**
 * A data-driven name (item, category, zone). [fr] is the data-language slot
 * (French unless [dataLocale] says otherwise): the data language reads it;
 * English reads English; any other locale reads the store's own name in it
 * ([names], e.g. es/de from the tablet's translations) when there is one,
 * else English, else the data-language slot.
 */
export function pickName(
  locale: Locale,
  fr?: string | null,
  en?: string | null,
  names?: Record<string, string> | null,
  dataLocale: DataLocale = "fr"
): string {
  if (locale === dataLocale) return fr || en || "";
  const own = locale === "en" ? undefined : names?.[locale]?.trim();
  return own || en || fr || "";
}
