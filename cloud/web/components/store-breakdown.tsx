"use client";

import { BarChart } from "@tremor/react";
import { CAD, CADShort } from "@/lib/format";
import { reportKey, useApi, useRange } from "@/lib/hooks";
import { useT } from "@/lib/i18n/context";
import { shortStoreName, useStores } from "@/lib/store";
import type { ByVenueReport, VenueSummaryRow } from "@/lib/types";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * "All stores" per-store comparison: headline figures side by side (each store
 * over its own business days). Renders nothing when a single store is picked.
 * Pass [rows] when the page already has them (e.g. summary.byVenue); otherwise
 * it loads /v1/reports/by-venue for the page's date range.
 */
export function StoreBreakdown({ rows, chart = false }: { rows?: VenueSummaryRow[]; chart?: boolean }) {
  const { combined } = useStores();
  if (!combined) return null;
  return rows ? <BreakdownCard rows={rows} chart={chart} /> : <LoadedBreakdown chart={chart} />;
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
  return <BreakdownCard rows={data.venues} chart={chart} />;
}

function BreakdownCard({ rows, chart }: { rows: VenueSummaryRow[]; chart: boolean }) {
  const t = useT();
  const sGross = t("series_gross");
  const total = (f: (r: VenueSummaryRow) => number) => rows.reduce((s, r) => s + f(r), 0);
  const checks = total((r) => r.checkCount);
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("store_breakdown_title")}</CardTitle>
        <CardDescription>{t("store_breakdown_sub")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {chart && rows.some((r) => r.grossCents > 0) && (
          <BarChart
            data={rows.map((r) => ({ store: shortStoreName(r.venueName), [sGross]: r.grossCents }))}
            index="store"
            categories={[sGross]}
            colors={["pink"]}
            valueFormatter={CADShort}
            yAxisWidth={52}
            showLegend={false}
            showAnimation
            className="h-44"
          />
        )}
        <div className="-mx-2 overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_store")}</TableHead>
                <TableHead className="text-right">{t("col_gross")}</TableHead>
                <TableHead className="hidden text-right sm:table-cell">{t("col_net")}</TableHead>
                <TableHead className="hidden text-right sm:table-cell">{t("col_tax")}</TableHead>
                <TableHead className="text-right">{t("col_checks")}</TableHead>
                <TableHead className="hidden text-right md:table-cell">{t("kpi_avg_check")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.venueId}>
                  <TableCell className="font-medium">{shortStoreName(r.venueName)}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.grossCents)}</TableCell>
                  <TableCell className="hidden text-right tabular-nums sm:table-cell">{CAD(r.netCents)}</TableCell>
                  <TableCell className="hidden text-right tabular-nums sm:table-cell">{CAD(r.taxCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{r.checkCount}</TableCell>
                  <TableCell className="hidden text-right tabular-nums md:table-cell">{CAD(r.avgCheckCents)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell>{t("col_total")}</TableCell>
                <TableCell className="text-right tabular-nums">{CAD(total((r) => r.grossCents))}</TableCell>
                <TableCell className="hidden text-right tabular-nums sm:table-cell">{CAD(total((r) => r.netCents))}</TableCell>
                <TableCell className="hidden text-right tabular-nums sm:table-cell">{CAD(total((r) => r.taxCents))}</TableCell>
                <TableCell className="text-right tabular-nums">{checks}</TableCell>
                <TableCell className="hidden text-right tabular-nums md:table-cell">
                  {checks ? CAD(Math.round(rows.reduce((s, r) => s + r.avgCheckCents * r.checkCount, 0) / checks)) : "—"}
                </TableCell>
              </TableRow>
            </TableFooter>
          </Table>
        </div>
      </CardContent>
    </Card>
  );
}

/** A small store tag for list rows in the combined view. */
export function StoreTag({ venueId }: { venueId: string }) {
  const { combined, nameOf } = useStores();
  if (!combined) return null;
  return (
    <span className="inline-flex shrink-0 items-center rounded-md bg-neutral-100 px-1.5 py-0.5 text-[10px] font-medium text-neutral-600">
      {nameOf(venueId)}
    </span>
  );
}
