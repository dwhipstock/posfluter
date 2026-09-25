"use client";

import { CAD, CADShort } from "@/lib/format";
import { reportKey, useApi, useRange } from "@/lib/hooks";
import { useT } from "@/lib/i18n/context";
import { useStores } from "@/lib/store";
import type { ByVenueReport, VenueSummaryRow } from "@/lib/types";
import { BarChart } from "@/components/charts";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

/** One figure of a per-store table. */
export interface SplitCol<R> {
  key: string;
  label: string;
  value: (r: R) => number;
  format: (n: number) => string;
  /** Hidden below this breakpoint (keeps phones to the essential columns). */
  hide?: "sm" | "md";
  /** How the footer totals it: sum (default), or a custom figure, or none. */
  total?: ((rows: R[]) => number) | false;
  strong?: boolean;
}

/**
 * "All stores" per-store breakdown of whatever the report measures (voids by
 * store, refunds by store, cash by store …): one row per store with its colour,
 * a combined total row, and optionally a bar per store. Renders nothing when
 * one store is picked — the whole page is that store then.
 */
export function StoreSplit<R extends { venueId: string }>({
  rows,
  cols,
  title,
  sub,
  chartKey,
  className,
}: {
  rows: R[];
  cols: SplitCol<R>[];
  title?: string;
  sub?: string;
  /** Draw a bar per store for this column. */
  chartKey?: string;
  className?: string;
}) {
  const t = useT();
  const { combined, nameOf, colorOf } = useStores();
  if (!combined) return null;
  const hide = (c: SplitCol<R>) => (c.hide === "sm" ? "hidden sm:table-cell" : c.hide === "md" ? "hidden md:table-cell" : "");
  const chartCol = chartKey ? cols.find((c) => c.key === chartKey) : undefined;
  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>{title ?? t("store_breakdown_title")}</CardTitle>
        <CardDescription>{sub ?? t("store_breakdown_sub")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {chartCol && rows.some((r) => chartCol.value(r) > 0) && (
          <BarChart
            data={rows.map((r) => ({ label: nameOf(r.venueId), values: { [r.venueId]: chartCol.value(r) } }))}
            series={rows.map((r) => ({ key: r.venueId, label: nameOf(r.venueId), color: colorOf(r.venueId) }))}
            format={chartCol.format}
            axisFormat={chartCol.format === CAD ? CADShort : chartCol.format}
            height={170}
            ariaLabel={`${title ?? t("store_breakdown_title")}: ${chartCol.label}`}
          />
        )}
        <div className="-mx-2">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_store")}</TableHead>
                {cols.map((c) => (
                  <TableHead key={c.key} className={cn("text-right", hide(c))}>
                    {c.label}
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.venueId}>
                  <TableCell className="font-medium">
                    <span className="flex items-center gap-2">
                      <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: colorOf(r.venueId) }} />
                      {nameOf(r.venueId)}
                    </span>
                  </TableCell>
                  {cols.map((c) => (
                    <TableCell key={c.key} className={cn("text-right tabular-nums", c.strong && "font-semibold", hide(c))}>
                      {c.format(c.value(r))}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell>{t("col_total")}</TableCell>
                {cols.map((c) => (
                  <TableCell key={c.key} className={cn("text-right tabular-nums", hide(c))}>
                    {c.total === false
                      ? ""
                      : c.format(c.total ? c.total(rows) : rows.reduce((s, r) => s + c.value(r), 0))}
                  </TableCell>
                ))}
              </TableRow>
            </TableFooter>
          </Table>
        </div>
      </CardContent>
    </Card>
  );
}

/** Sales by store (gross / net / tax / checks / average), the summary figures. */
export function StoreBreakdown({ rows, chart = false }: { rows?: VenueSummaryRow[]; chart?: boolean }) {
  const { combined } = useStores();
  if (!combined) return null;
  return rows ? <SalesSplit rows={rows} chart={chart} /> : <LoadedBreakdown chart={chart} />;
}

function LoadedBreakdown({ chart }: { chart: boolean }) {
  const range = useRange();
  const { data, isLoading } = useApi<ByVenueReport>(reportKey("/v1/reports/by-venue", range));
  if (isLoading || !data) {
    return (
      <Card>
        <CardContent className="pt-5">
          <Skeleton className="h-28 w-full" />
        </CardContent>
      </Card>
    );
  }
  return <SalesSplit rows={data.venues} chart={chart} />;
}

const count = (n: number) => String(n);

function SalesSplit({ rows, chart }: { rows: VenueSummaryRow[]; chart: boolean }) {
  const t = useT();
  const avg = (rs: VenueSummaryRow[]) => {
    const checks = rs.reduce((s, r) => s + r.checkCount, 0);
    return checks ? Math.round(rs.reduce((s, r) => s + r.avgCheckCents * r.checkCount, 0) / checks) : 0;
  };
  return (
    <StoreSplit
      rows={rows}
      title={t("store_sales_title")}
      chartKey={chart ? "gross" : undefined}
      cols={[
        { key: "gross", label: t("col_gross"), value: (r) => r.grossCents, format: CAD, strong: true },
        { key: "net", label: t("col_net"), value: (r) => r.netCents, format: CAD, hide: "sm" },
        { key: "tax", label: t("col_tax"), value: (r) => r.taxCents, format: CAD, hide: "sm" },
        { key: "checks", label: t("col_checks"), value: (r) => r.checkCount, format: count },
        { key: "avg", label: t("kpi_avg_check"), value: (r) => r.avgCheckCents, format: CAD, hide: "md", total: avg },
      ]}
    />
  );
}

/** A small store tag for list rows in the combined view: the store's colour and name. */
export function StoreTag({ venueId }: { venueId: string }) {
  const { combined, nameOf, colorOf } = useStores();
  if (!combined) return null;
  return (
    <span className="inline-flex shrink-0 items-center gap-1 rounded-md border border-neutral-200 bg-surface-alt px-1.5 py-0.5 text-[10px] font-semibold text-neutral-700">
      <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: colorOf(venueId) }} />
      {nameOf(venueId)}
    </span>
  );
}

/** Chart series: one per in-scope store in "All stores", else a single named series. */
export function useStoreSeries(singleLabel: string, singleKey = "value") {
  const { combined, venues, nameOf, colorOf, storeId } = useStores();
  if (combined) return venues.map((v) => ({ key: v.id, label: nameOf(v.id), color: colorOf(v.id) }));
  return [{ key: singleKey, label: singleLabel, color: storeId ? colorOf(storeId) : "#1F5F99" }];
}
