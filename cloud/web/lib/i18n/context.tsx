"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import type { Locale, MsgKey } from "./messages";
import { dataLocaleOf, pickName, translate, type DataLocale } from "./translate";
import { makeFmt, type Fmt } from "./format";

export type { Locale } from "./messages";

type Vars = Record<string, string | number>;

interface I18n {
  locale: Locale;
  /** The locales this client's portal offers (its brand pack), in display order. */
  available: Locale[];
  /** The language the catalog's `*_fr` slots hold ("fr", or "th" at a Thai + English client). */
  dataLocale: DataLocale;
  setLocale: (l: Locale) => void;
  toggleLocale: () => void;
  t: (key: MsgKey, vars?: Vars) => string;
  fmt: Fmt;
  /**
   * Data-driven names (items, categories, zones): locale first. [fr] is the
   * data-language slot (French, or Thai at a Thai + English client). Spanish,
   * German and Afrikaans read the store’s own name in that language ([names]) when it sent
   * one, else the English name.
   */
  name: (fr?: string | null, en?: string | null, names?: Record<string, string> | null) => string;
  /** The other language — the small secondary line; "" when redundant. */
  nameAlt: (fr?: string | null, en?: string | null) => string;
}

const Ctx = createContext<I18n | null>(null);

// Persist as a cookie (not localStorage) so the server layout can read it and
// render <html lang> correctly on the first paint, before any JS runs.
function writeCookie(name: string, value: string) {
  document.cookie = `${name}=${value}; path=/; max-age=31536000; samesite=lax`;
}

export function LocaleProvider({
  initialLocale,
  available,
  dataLocale: dataLocaleProp,
  children,
}: {
  initialLocale: Locale;
  available: Locale[];
  dataLocale?: DataLocale;
  children: React.ReactNode;
}) {
  const [locale, setLocaleState] = useState<Locale>(initialLocale);
  const dataLocale = dataLocaleProp ?? dataLocaleOf(available);

  const setLocale = useCallback((l: Locale) => {
    setLocaleState(l);
    writeCookie("locale", l);
    document.documentElement.lang = l;
  }, []);

  const t = useCallback(
    (key: MsgKey, vars?: Vars) => {
      return translate(locale, key, vars);
    },
    [locale]
  );

  const fmt = useMemo(() => makeFmt(locale, t), [locale, t]);

  const name = useCallback(
    (fr?: string | null, en?: string | null, names?: Record<string, string> | null) =>
      pickName(locale, fr, en, names, dataLocale),
    [locale, dataLocale]
  );
  const nameAlt = useCallback(
    (fr?: string | null, en?: string | null) => {
      // the second line is the store's other catalog language (French or
      // Thai beside English); a Spanish reader, or a client without its data
      // language, gets no subtitle
      if ((locale !== "en" && locale !== dataLocale) || !available.includes(dataLocale)) return "";
      const primary = locale === "en" ? en : fr;
      const alt = locale === "en" ? fr : en;
      return alt && alt !== primary ? alt : "";
    },
    [locale, available, dataLocale]
  );

  const value = useMemo<I18n>(
    () => ({
      locale,
      available,
      dataLocale,
      setLocale,
      // the next of this client's locales (two locales: a simple flip)
      toggleLocale: () => setLocale(available[(available.indexOf(locale) + 1) % available.length] ?? locale),
      t,
      fmt,
      name,
      nameAlt,
    }),
    [locale, available, dataLocale, setLocale, t, fmt, name, nameAlt]
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useI18n(): I18n {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useI18n must be used within <LocaleProvider>");
  return ctx;
}

export function useT() {
  return useI18n().t;
}

export function useFmt() {
  return useI18n().fmt;
}

/** A message as a node, for shared UI primitives; English outside a provider. */
export function Msg({ k }: { k: MsgKey }) {
  const ctx = useContext(Ctx);
  return <>{ctx ? ctx.t(k) : translate("en", k)}</>;
}
