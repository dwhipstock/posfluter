"use client";

import { Suspense, useMemo, useState } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useI18n, useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { ItemReportRow, ItemsReport } from "@/lib/types";
import { cn } from "@/lib/utils";
import { Card } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <ItemsPage />
    </Suspense>
  );
}

function ItemsPage() {
  const t = useT();
  const fmt = useFmt();
  const { name, nameAlt } = useI18n();
  const range = useRange();
  const meta = useExportMeta();
  const { data, error, isLoading, mutate } = useApi<ItemsReport>(reportKey("/v1/reports/items", range));
  const [metric, setMetric] = useState<"revenue" | "qty">("revenue");
  const [cat, setCat] = useState<string | null>(null);

  const cats = useMemo(() => {
    const seen = new Map<string, string>();
    for (const r of data?.rows ?? []) {
      if (r.categoryId && !seen.has(r.categoryId)) {
        seen.set(r.categoryId, name(r.categoryNameFr, r.categoryNameEn) || r.categoryId);
      }
    }
    return [...seen.entries()];
  }, [data, name]);

  const rows = useMemo(() => {
    const filtered = (data?.rows ?? []).filter((r) => (cat ? r.categoryId === cat : true));
    return [...filtered].sort((a, b) =>
      metric === "qty" ? b.qty - a.qty : b.revenueCents - a.revenueCents
    );
  }, [data, cat, metric]);

  const max = Math.max(1, ...rows.map((r) => (metric === "qty" ? r.qty : r.revenueCents)));

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("items"),
      reportTitle: t("items_title"),
      sections: [
        {
          columns: [
            col.text<ItemReportRow>(t("col_item"), (r) => name(r.nameFr, r.nameEn)),
            col.text<ItemReportRow>(t("col_category"), (r) => name(r.categoryNameFr, r.categoryNameEn) || "—"),
            col.int<ItemReportRow>(t("col_qty"), (r) => r.qty),
            col.money<ItemReportRow>(t("col_revenue"), (r) => r.revenueCents),
          ],
          rows: rows,
          total: [
            T(t("col_total")),
            T(""),
            Int(rows.reduce((n, r) => n + r.qty, 0)),
            Money(rows.reduce((n, r) => n + r.revenueCents, 0)),
          ],
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("items_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={
          <div className="flex items-center gap-2">
            <Tabs value={metric} onValueChange={(v) => setMetric(v as "revenue" | "qty")}>
              <TabsList>
                <TabsTrigger value="revenue">{t("items_tab_revenue")}</TabsTrigger>
                <TabsTrigger value="qty">{t("items_tab_qty")}</TabsTrigger>
              </TabsList>
            </Tabs>
            <ExportMenu build={buildDoc} disabled={rows.length === 0} />
          </div>
        }
      />
      <DateRangePicker />

      {cats.length > 0 && (
        <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4 md:mx-0 md:flex-wrap md:px-0">
          <CatChip label={t("cat_all")} active={cat === null} onClick={() => setCat(null)} />
          {cats.map(([id, label]) => (
            <CatChip key={id} label={label} active={cat === id} onClick={() => setCat(id)} />
          ))}
        </div>
      )}

      <Card>
        {isLoading ? (
          <TableSkeleton rows={8} />
        ) : error ? (
          <ErrorState message={error.message} onRetry={() => mutate()} />
        ) : rows.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-8">#</TableHead>
                <TableHead>{t("col_item")}</TableHead>
                <TableHead className="hidden sm:table-cell">{t("col_category")}</TableHead>
                <TableHead className="text-right">{t("col_qty")}</TableHead>
                <TableHead className="text-right">{t("col_revenue")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r, i) => (
                <TableRow key={r.itemId ?? `open-${i}`}>
                  <TableCell className="text-xs font-semibold text-neutral-400">{i + 1}</TableCell>
                  <TableCell>
                    <div className="min-w-[10rem]">
                      <div className="text-sm font-medium">{name(r.nameFr, r.nameEn)}</div>
                      {nameAlt(r.nameFr, r.nameEn) && (
                        <div className="text-xs text-neutral-500">{nameAlt(r.nameFr, r.nameEn)}</div>
                      )}
                      <div className="mt-1.5 h-0.5 w-full max-w-[8rem] rounded bg-neutral-100">
                        <div
                          className="h-0.5 rounded bg-accent"
                          style={{
                            width: `${Math.max(
                              2,
                              Math.round(((metric === "qty" ? r.qty : r.revenueCents) / max) * 100)
                            )}%`,
                          }}
                        />
                      </div>
                    </div>
                  </TableCell>
                  <TableCell className="hidden text-xs text-neutral-500 sm:table-cell">
                    {name(r.categoryNameFr, r.categoryNameEn) || "—"}
                  </TableCell>
                  <TableCell
                    className={cn("text-right tabular-nums", metric === "qty" && "font-semibold")}
                  >
                    {r.qty}
                  </TableCell>
                  <TableCell
                    className={cn("text-right tabular-nums", metric === "revenue" && "font-semibold")}
                  >
                    {CAD(r.revenueCents)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <EmptyState title={t("items_empty")} hint={t("items_empty_hint")} />
        )}
      </Card>
    </div>
  );
}

function CatChip({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "shrink-0 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors",
        active
          ? "border-accent bg-accent text-black"
          : "border-neutral-200 bg-white text-neutral-600 hover:border-neutral-300 hover:text-ink"
      )}
    >
      {label}
    </button>
  );
}
