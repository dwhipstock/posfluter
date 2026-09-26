"use client";

// Per-page glue: assembles the parts of an ExportDoc that every report shares
// — scope (one store or all stores), locale-aware range label, filename stem,
// and a "generated at" stamp — from the same hooks the page already uses. A
// report page spreads the result and adds its own title / notes / kpis /
// sections; `withStore` adds the Store column to a list in "All stores".

import { useCallback } from "react";
import { useMe, useRange } from "@/lib/hooks";
import { shortStoreName, slugify, useStores } from "@/lib/store";
import { useI18n, useFmt, useT } from "@/lib/i18n/context";
import { useBrand } from "@/lib/brand/context";
import { col, type Col, type ExportDoc, type Section } from "./doc";

export type DocMeta = Pick<
  ExportDoc,
  "filenameBase" | "venue" | "scopeLabel" | "rangeLabel" | "generatedLabel" | "locale" | "colors" | "producer"
>;

// Local wall-clock "now" as a naive ISO string, so the export fmt (which never
// routes through Date) can render it in the active locale.
function nowNaiveISO(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(
    d.getMinutes()
  )}`;
}

export function useExportMeta() {
  const { locale } = useI18n();
  const fmt = useFmt();
  const t = useT();
  const me = useMe();
  const range = useRange();
  const { store, venues } = useStores();
  const brand = useBrand();
  // the picked store, else the group across all its stores
  const venue = store?.name ?? me.data?.tenantName ?? "";
  const scopeLabel = store
    ? t("scope_single", { store: shortStoreName(store.name) })
    : t("scope_all_n", { n: venues.length });
  const storeSlug = store ? slugify(shortStoreName(store.name)) : "all-stores";
  return useCallback(
    (slug: string): DocMeta => {
      const iso = nowNaiveISO();
      return {
        filenameBase: `${slug}_${storeSlug}_${range.from}_${range.to}`,
        venue,
        scopeLabel,
        rangeLabel: fmt.rangeLabel(range),
        generatedLabel: t("export_generated", { when: `${fmt.dayYear(iso)} ${fmt.time(iso)}` }),
        locale,
        producer: brand.name,
        colors: {
          accent: brand.palette.primary,
          text: brand.palette.text,
          muted: brand.palette.textMuted,
          faint: brand.neutral["400"],
          headerFill: brand.palette.surfaceAlt,
          rule: brand.palette.border,
        },
      };
    },
    [locale, fmt, t, venue, scopeLabel, storeSlug, range, brand]
  );
}

/**
 * Export helpers bound to the current scope. In "All stores" every list gets a
 * leading Store column, and `byStore` builds the per-store section; with one
 * store picked both are no-ops (the store is in the header and filename).
 */
export function useStoreExport() {
  const t = useT();
  const { combined, nameOf } = useStores();
  return {
    combined,
    withStore: <R extends { venueId: string }>(columns: Col<R>[]): Col<R>[] =>
      combined ? [col.text<R>(t("col_store"), (r) => nameOf(r.venueId), { width: 16 }), ...columns] : columns,
    byStore: <R extends { venueId: string }>(columns: Col<R>[], rows: R[], total?: Section["total"]): Section[] =>
      combined
        ? [
            {
              title: t("store_breakdown_title"),
              columns: [col.text<R>(t("col_store"), (r) => nameOf(r.venueId), { width: 18 }), ...columns],
              rows,
              total,
            },
          ]
        : [],
  };
}
