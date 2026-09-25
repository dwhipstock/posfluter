"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD, CADShort, hourLabel } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type Cell, type ExportDoc } from "@/lib/export/doc";
import type { HourlyReport, HourlyRow, VenueTotalRow } from "@/lib/types";
import { useStores } from "@/lib/store";
import { cn } from "@/lib/utils";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { StoreSplit, useStoreSeries } from "@/components/store-breakdown";
import { BarChart } from "@/components/charts";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";
import { Skeleton } from "@/components/ui/skeleton";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <HourlyPage />
    </Suspense>
  );
}

function HourlyPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const { combined, venues, nameOf, colorOf } = useStores();
  const series = useStoreSeries(t("series_gross"));
  const { data, error, isLoading, mutate } = useApi<HourlyReport>(reportKey("/v1/reports/hourly", range));
  const hasSales = data?.rows.some((r) => r.grossCents > 0) ?? false;
  const cell = (r: HourlyRow, venueId: string) => r.byVenue.find((v) => v.venueId === venueId);

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    const rows = data.rows;
    return {
      ...meta("hourly"),
      reportTitle: t("hourly_title"),
      sections: [
        ...storeExport.byStore<VenueTotalRow>(
          [col.int(t("col_checks"), (r) => r.checkCount), col.money(t("col_gross"), (r) => r.grossCents)],
          data.byVenue
        ),
        {
          title: t("hourly_title"),
          columns: [
            col.text<HourlyRow>(t("col_hour"), (r) => hourLabel(r.hour)),
            ...(combined
              ? venues.flatMap((v) => [
                  col.int<HourlyRow>(`${t("col_checks")} · ${nameOf(v.id)}`, (r) => cell(r, v.id)?.checkCount ?? 0),
                  col.money<HourlyRow>(`${t("col_gross")} · ${nameOf(v.id)}`, (r) => cell(r, v.id)?.grossCents ?? 0),
                ])
              : []),
            col.int<HourlyRow>(t("col_checks"), (r) => r.checkCount),
            col.money<HourlyRow>(t("col_gross"), (r) => r.grossCents),
          ],
          rows,
          total: [
            T(t("col_total")),
            ...(combined
              ? venues.flatMap((v): Cell[] => [
                  Int(rows.reduce((n, r) => n + (cell(r, v.id)?.checkCount ?? 0), 0)),
                  Money(rows.reduce((n, r) => n + (cell(r, v.id)?.grossCents ?? 0), 0)),
                ])
              : []),
            Int(rows.reduce((n, r) => n + r.checkCount, 0)),
            Money(rows.reduce((n, r) => n + r.grossCents, 0)),
          ],
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("hourly_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || !hasSales} />}
      />
      <DateRangePicker />
      {data && (
        <StoreSplit
          rows={data.byVenue}
          cols={[
            { key: "checks", label: t("col_checks"), value: (r) => r.checkCount, format: String },
            { key: "gross", label: t("col_gross"), value: (r) => r.grossCents, format: CAD, strong: true },
          ]}
        />
      )}

      <Card>
        <CardHeader>
          <CardTitle>{t("hourly_chart")}</CardTitle>
          {combined && <CardDescription>{t("chart_per_store_stacked")}</CardDescription>}
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-56 w-full" />
          ) : error ? (
            <ErrorState message={error.message} onRetry={() => mutate()} />
          ) : hasSales ? (
            <BarChart
              data={data!.rows.map((r) => ({
                label: hourLabel(r.hour),
                values: combined ? Object.fromEntries(r.byVenue.map((v) => [v.venueId, v.grossCents])) : { value: r.grossCents },
              }))}
              series={series}
              format={CAD}
              axisFormat={CADShort}
              ariaLabel={t("hourly_chart")}
            />
          ) : (
            <EmptyState title={t("hourly_empty")} />
          )}
        </CardContent>
      </Card>

      <Card>
        {isLoading ? (
          <TableSkeleton rows={8} />
        ) : !error && data ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_hour")}</TableHead>
                {combined &&
                  venues.map((v) => (
                    <TableHead key={v.id} className="hidden text-right sm:table-cell">
                      <span className="inline-flex items-center gap-1.5">
                        <span className="h-2 w-2 rounded-full" style={{ backgroundColor: colorOf(v.id) }} />
                        {nameOf(v.id)}
                      </span>
                    </TableHead>
                  ))}
                <TableHead className="text-right">{t("col_checks")}</TableHead>
                <TableHead className="text-right">{t("col_gross")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.hour} className={cn(r.grossCents === 0 && "text-neutral-500")}>
                  <TableCell className="font-medium tabular-nums">{hourLabel(r.hour)}</TableCell>
                  {combined &&
                    venues.map((v) => (
                      <TableCell key={v.id} className="hidden text-right tabular-nums sm:table-cell">
                        {CAD(cell(r, v.id)?.grossCents ?? 0)}
                      </TableCell>
                    ))}
                  <TableCell className="text-right tabular-nums">{r.checkCount}</TableCell>
                  <TableCell className="text-right font-medium tabular-nums">{CAD(r.grossCents)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell>{t("col_total")}</TableCell>
                {combined &&
                  venues.map((v) => (
                    <TableCell key={v.id} className="hidden text-right tabular-nums sm:table-cell">
                      {CAD(data.rows.reduce((n, r) => n + (cell(r, v.id)?.grossCents ?? 0), 0))}
                    </TableCell>
                  ))}
                <TableCell className="text-right tabular-nums">{data.rows.reduce((n, r) => n + r.checkCount, 0)}</TableCell>
                <TableCell className="text-right tabular-nums">{CAD(data.rows.reduce((n, r) => n + r.grossCents, 0))}</TableCell>
              </TableRow>
            </TableFooter>
          </Table>
        ) : null}
      </Card>
    </div>
  );
}
