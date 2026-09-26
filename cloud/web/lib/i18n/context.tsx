"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { messages, type Locale, type MsgKey } from "./messages";
import { makeFmt, type Fmt } from "./format";

export type { Locale } from "./messages";

type Vars = Record<string, string | number>;

interface I18n {
  locale: Locale;
  setLocale: (l: Locale) => void;
  toggleLocale: () => void;
  t: (key: MsgKey, vars?: Vars) => string;
  fmt: Fmt;
  /** Data-driven bilingual names (items, categories, zones): locale first. */
  name: (fr?: string | null, en?: string | null) => string;
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
  children,
}: {
  initialLocale: Locale;
  children: React.ReactNode;
}) {
  const [locale, setLocaleState] = useState<Locale>(initialLocale);

  const setLocale = useCallback((l: Locale) => {
    setLocaleState(l);
    writeCookie("locale", l);
    document.documentElement.lang = l;
  }, []);

  const t = useCallback(
    (key: MsgKey, vars?: Vars) => {
      const entry = messages[key];
      let s: string = entry ? entry[locale] : key;
      if (vars) s = s.replace(/\{(\w+)\}/g, (_, k: string) => String(vars[k] ?? `{${k}}`));
      return s;
    },
    [locale]
  );

  const fmt = useMemo(() => makeFmt(locale, t), [locale, t]);

  const name = useCallback(
    (fr?: string | null, en?: string | null) => (locale === "en" ? en || fr || "" : fr || en || ""),
    [locale]
  );
  const nameAlt = useCallback(
    (fr?: string | null, en?: string | null) => {
      const primary = locale === "en" ? en : fr;
      const alt = locale === "en" ? fr : en;
      return alt && alt !== primary ? alt : "";
    },
    [locale]
  );

  const value = useMemo<I18n>(
    () => ({
      locale,
      setLocale,
      toggleLocale: () => setLocale(locale === "fr" ? "en" : "fr"),
      t,
      fmt,
      name,
      nameAlt,
    }),
    [locale, setLocale, t, fmt, name, nameAlt]
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
  return <>{ctx ? ctx.t(k) : messages[k].en}</>;
}
