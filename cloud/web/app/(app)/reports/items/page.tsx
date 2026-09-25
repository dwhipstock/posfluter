"use client";

import { Suspense, useMemo, useState } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useI18n, useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type Cell, type Col, type ExportDoc } from "@/lib/export/doc";
import type { ItemReportRow, ItemsReport, VenueTotalRow } from "@/lib/types";
import { useStores } from "@/lib/store";
import { cn } from "@/lib/utils";
import { Card } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { StoreSplit } from "@/components/store-breakdown";
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
  const storeExport = useStoreExport();
  const { combined, venues, nameOf, colorOf } = useStores();
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

  const metricOf = (v: { qty: number; revenueCents: number }) => (metric === "qty" ? v.qty : v.revenueCents);
  const show = (n: number) => (metric === "qty" ? String(n) : CAD(n));
  const max = Math.max(1, ...rows.map(metricOf));
  const split = (r: ItemReportRow, venueId: string) => r.byVenue.find((v) => v.venueId === venueId);
  const sum = (f: (r: ItemReportRow) => number) => rows.reduce((n, r) => n + f(r), 0);

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    // "All stores": qty and revenue per store beside the combined figures
    const perStore: Col<ItemReportRow>[] = combined
      ? venues.flatMap((v) => [
          col.int<ItemReportRow>(`${t("col_qty")} · ${nameOf(v.id)}`, (r) => split(r, v.id)?.qty ?? 0),
          col.money<ItemReportRow>(`${t("col_revenue")} · ${nameOf(v.id)}`, (r) => split(r, v.id)?.revenueCents ?? 0),
        ])
      : [];
    const perStoreTotal: Cell[] = combined
      ? venues.flatMap((v) => [
          Int(sum((r) => split(r, v.id)?.qty ?? 0)),
          Money(sum((r) => split(r, v.id)?.revenueCents ?? 0)),
        ])
      : [];
    return {
      ...meta("items"),
      reportTitle: t("items_title"),
      sections: [
        ...storeExport.byStore<VenueTotalRow>(
          [col.int(t("col_qty"), (r) => r.qty), col.money(t("col_revenue"), (r) => r.grossCents)],
          data.byVenue,
          [T(t("col_total")), Int(data.byVenue.reduce((n, r) => n + r.qty, 0)), Money(data.byVenue.reduce((n, r) => n + r.grossCents, 0))]
        ),
        {
          title: t("items_title"),
          columns: [
            col.text<ItemReportRow>(t("col_item"), (r) => name(r.nameFr, r.nameEn)),
            col.text<ItemReportRow>(t("col_category"), (r) => name(r.categoryNameFr, r.categoryNameEn) || "—"),
            ...perStore,
            col.int<ItemReportRow>(combined ? `${t("col_qty")} · ${t("store_all")}` : t("col_qty"), (r) => r.qty),
            col.money<ItemReportRow>(combined ? `${t("col_revenue")} · ${t("store_all")}` : t("col_revenue"), (r) => r.revenueCents),
          ],
          rows,
          total: [T(t("col_total")), T(""), ...perStoreTotal, Int(sum((r) => r.qty)), Money(sum((r) => r.revenueCents))],
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
      {data && (
        <StoreSplit
          rows={data.byVenue}
          title={t("items_by_store")}
          chartKey="revenue"
          cols={[
            { key: "qty", label: t("col_qty"), value: (r) => r.qty, format: String },
            { key: "revenue", label: t("col_revenue"), value: (r) => r.grossCents, format: CAD, strong: true },
          ]}
        />
      )}

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
                <TableHead className="hidden lg:table-cell">{t("col_category")}</TableHead>
                {combined &&
                  venues.map((v) => (
                    <TableHead key={v.id} className="hidden text-right sm:table-cell">
                      <span className="inline-flex items-center gap-1.5">
                        <span className="h-2 w-2 rounded-full" style={{ backgroundColor: colorOf(v.id) }} />
                        {nameOf(v.id)}
                      </span>
                    </TableHead>
                  ))}
                <TableHead className="text-right">{combined ? t("col_total") : metric === "qty" ? t("col_qty") : t("col_revenue")}</TableHead>
                {!combined && <TableHead className="text-right">{metric === "qty" ? t("col_revenue") : t("col_qty")}</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r, i) => (
                <TableRow key={r.itemId ?? `open-${i}`}>
                  <TableCell className="text-xs font-semibold text-neutral-500">{i + 1}</TableCell>
                  <TableCell>
                    <div className="min-w-[10rem]">
                      <div className="text-sm font-medium">{name(r.nameFr, r.nameEn)}</div>
                      {nameAlt(r.nameFr, r.nameEn) && (
                        <div className="text-xs text-neutral-500">{nameAlt(r.nameFr, r.nameEn)}</div>
                      )}
                      {/* share bar: one segment per store in "All stores" */}
                      <div className="mt-1.5 flex h-1 w-full max-w-[10rem] gap-px overflow-hidden rounded-full bg-neutral-100">
                        {(combined ? r.byVenue : [{ venueId: "", qty: r.qty, revenueCents: r.revenueCents }]).map((v) => (
                          <div
                            key={v.venueId}
                            className={cn("h-1", !combined && "bg-navy")}
                            style={{
                              width: `${Math.max(2, (metricOf(v) / max) * 100)}%`,
                              backgroundColor: combined ? colorOf(v.venueId) : undefined,
                            }}
                          />
                        ))}
                      </div>
                    </div>
                  </TableCell>
                  <TableCell className="hidden text-xs text-neutral-500 lg:table-cell">
                    {name(r.categoryNameFr, r.categoryNameEn) || "—"}
                  </TableCell>
                  {combined &&
                    venues.map((v) => {
                      const s = split(r, v.id);
                      return (
                        <TableCell key={v.id} className="hidden text-right tabular-nums text-neutral-600 sm:table-cell">
                          {s ? show(metricOf(s)) : "—"}
                        </TableCell>
                      );
                    })}
                  <TableCell className="text-right font-semibold tabular-nums">{show(metricOf(r))}</TableCell>
                  {!combined && (
                    <TableCell className="text-right tabular-nums text-neutral-600">
                      {metric === "qty" ? CAD(r.revenueCents) : r.qty}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell />
                <TableCell>{t("col_total")}</TableCell>
                <TableCell className="hidden lg:table-cell" />
                {combined &&
                  venues.map((v) => (
                    <TableCell key={v.id} className="hidden text-right tabular-nums sm:table-cell">
                      {show(sum((r) => { const s = split(r, v.id); return s ? metricOf(s) : 0; }))}
                    </TableCell>
                  ))}
                <TableCell className="text-right tabular-nums">{show(sum(metricOf))}</TableCell>
                {!combined && (
                  <TableCell className="text-right tabular-nums">
                    {metric === "qty" ? CAD(sum((r) => r.revenueCents)) : sum((r) => r.qty)}
                  </TableCell>
                )}
              </TableRow>
            </TableFooter>
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
      aria-pressed={active}
      className={cn(
        "shrink-0 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors",
        active
          ? "border-navy bg-navy text-white"
          : "border-neutral-200 bg-surface text-neutral-600 hover:border-neutral-300 hover:text-ink"
      )}
    >
      {label}
    </button>
  );
}
