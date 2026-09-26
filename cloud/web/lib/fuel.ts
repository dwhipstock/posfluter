"use client";

import { useMemo } from "react";
import { useI18n, useT } from "./i18n/context";
import type { Locale } from "./i18n/messages";

// Number formats for a gas station's figures (GET /v1/reports/fuel): gallons
// to the pump's three decimals, margin in cents per gallon (the API sends
// mills — tenths of a cent — per gallon), margin as a percentage (the API
// sends basis points). A null ratio means no cost was known: shown as "—",
// never as 0 or 100%.

const NUMBER_LOCALE: Record<Locale, string> = { en: "en-US", fr: "fr-CA", es: "es-US" };

export interface FuelFmt {
  /** 10052 → "10.052" */
  gal: (milli: number) => string;
  /** 300 mills → "30.0¢/gal"; null → "—" */
  perGallon: (mills: number | null | undefined) => string;
  /** 2750 basis points → "27.5%"; null → "—" */
  pct: (bp: number | null | undefined) => string;
}

export function useFuelFmt(): FuelFmt {
  const { locale } = useI18n();
  const t = useT();
  return useMemo(() => {
    const loc = NUMBER_LOCALE[locale];
    const g = new Intl.NumberFormat(loc, { minimumFractionDigits: 3, maximumFractionDigits: 3 });
    const one = new Intl.NumberFormat(loc, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    const pc = new Intl.NumberFormat(loc, { style: "percent", minimumFractionDigits: 1, maximumFractionDigits: 1 });
    return {
      gal: (milli) => g.format(milli / 1000),
      perGallon: (mills) => (mills == null ? "—" : t("fuel_cents_per_gal", { v: one.format(mills / 10) })),
      pct: (bp) => (bp == null ? "—" : pc.format(bp / 10000)),
    };
  }, [locale, t]);
}
