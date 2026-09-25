"use client";

// Per-page glue: assembles the parts of an ExportDoc that every report shares
// — venue, locale-aware range label, filename stem, and a "generated at"
// stamp — from the same hooks the page already uses. A report page spreads the
// result and adds its own title / notes / kpis / sections.

import { useCallback } from "react";
import { useMe, useRange } from "@/lib/hooks";
import { useI18n, useFmt, useT } from "@/lib/i18n/context";
import type { ExportDoc } from "./doc";

export type DocMeta = Pick<
  ExportDoc,
  "filenameBase" | "venue" | "rangeLabel" | "generatedLabel" | "locale"
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
  return useCallback(
    (slug: string): DocMeta => {
      const iso = nowNaiveISO();
      return {
        filenameBase: `${slug}_${range.from}_${range.to}`,
        venue: me.data?.venueName ?? "",
        rangeLabel: fmt.rangeLabel(range),
        generatedLabel: t("export_generated", { when: `${fmt.dayYear(iso)} ${fmt.time(iso)}` }),
        locale,
      };
    },
    [locale, fmt, t, me.data?.venueName, range]
  );
}
