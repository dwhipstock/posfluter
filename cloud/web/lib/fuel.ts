"use client";

import { useMemo } from "react";
import { useI18n, useT } from "./i18n/context";
import type { MsgKey } from "./i18n/messages";
import type { InStoreCategoryRow } from "./types";

// Number formats for a gas station's figures (GET /v1/reports/fuel): gallons
// to the pump's three decimals, margin in cents per gallon (the API sends
// mills — tenths of a cent — per gallon), margin as a percentage (the API
// sends basis points). A null ratio means no cost was known: shown as "—",
// never as 0 or 100%.

// North American in every UI language, like money (the owner's rule):
// "1,234.567 gal", "30.5¢/gal", "27.5%" — never "1 234,567" or "30,5".
const NUMBER_LOCALE = "en-US";

/** A gas station's category ids (CONTRACT §2, Fuel) with portal names in every language. */
const STORE_CATEGORIES = new Set([
  "drinks", "beer", "snacks", "candy", "hot-food", "grocery", "automotive", "health", "general", "tobacco", "ice", "fuel",
]);

/** A category's name: the known ids in the reader's language, else the store's own name, else its id. */
export function useCategoryName(): (r: Pick<InStoreCategoryRow, "categoryId" | "nameFr" | "nameEn">) => string {
  const { name } = useI18n();
  const t = useT();
  return (r) =>
    r.categoryId && STORE_CATEGORIES.has(r.categoryId)
      ? t(`store_cat_${r.categoryId.replace(/-/g, "_")}` as MsgKey)
      : name(r.nameFr, r.nameEn) || r.categoryId || "—";
}

export interface FuelFmt {
  /** 10052 → "10.052" */
  gal: (milli: number) => string;
  /** 300 mills → "30.0¢/gal"; null → "—" */
  perGallon: (mills: number | null | undefined) => string;
  /** 2750 basis points → "27.5%"; null → "—" */
  pct: (bp: number | null | undefined) => string;
}

/** Gallons for an export cell: 10052 milli-gallons → 10.052 (a number, so a spreadsheet can sum it). */
export const gallons = (milli: number): number => Math.round(milli) / 1000;

export function useFuelFmt(): FuelFmt {
  const t = useT();
  return useMemo(() => {
    const loc = NUMBER_LOCALE;
    const g = new Intl.NumberFormat(loc, { minimumFractionDigits: 3, maximumFractionDigits: 3 });
    const one = new Intl.NumberFormat(loc, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    const pc = new Intl.NumberFormat(loc, { style: "percent", minimumFractionDigits: 1, maximumFractionDigits: 1 });
    return {
      gal: (milli) => g.format(milli / 1000),
      perGallon: (mills) => (mills == null ? "—" : t("fuel_cents_per_gal", { v: one.format(mills / 10) })),
      pct: (bp) => (bp == null ? "—" : pc.format(bp / 10000)),
    };
  }, [t]);
}
