"use client";

import { Suspense } from "react";
import { BarChart } from "@tremor/react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD, CADShort, hourLabel } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { HourlyReport, HourlyRow } from "@/lib/types";
import { cn } from "@/lib/utils";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { StoreBreakdown } from "@/components/store-breakdown";
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
  const { data, error, isLoading, mutate } = useApi<HourlyReport>(reportKey("/v1/reports/hourly", range));
  const hasSales = data?.rows.some((r) => r.grossCents > 0) ?? false;

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("hourly"),
      reportTitle: t("hourly_title"),
      sections: [
        {
          columns: [
            col.text<HourlyRow>(t("col_hour"), (r) => hourLabel(r.hour)),
            col.int<HourlyRow>(t("col_checks"), (r) => r.checkCount),
            col.money<HourlyRow>(t("col_gross"), (r) => r.grossCents),
          ],
          rows: data.rows,
          total: [
            T(t("col_total")),
            Int(data.rows.reduce((n, r) => n + r.checkCount, 0)),
            Money(data.rows.reduce((n, r) => n + r.grossCents, 0)),
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
      <StoreBreakdown />

      <Card>
        <CardHeader>
          <CardTitle>{t("hourly_chart")}</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-56 w-full" />
          ) : error ? (
            <ErrorState message={error.message} onRetry={() => mutate()} />
          ) : hasSales ? (
            <BarChart
              data={data!.rows.map((r) => ({ hour: hourLabel(r.hour), [t("series_gross")]: r.grossCents }))}
              index="hour"
              categories={[t("series_gross")]}
              colors={["pink"]}
              valueFormatter={CADShort}
              yAxisWidth={52}
              showLegend={false}
              showAnimation
              className="h-56"
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
                <TableHead className="text-right">{t("col_checks")}</TableHead>
                <TableHead className="text-right">{t("col_gross")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.hour} className={cn(r.grossCents === 0 && "text-neutral-400")}>
                  <TableCell className="font-medium tabular-nums">{hourLabel(r.hour)}</TableCell>
                  <TableCell className="text-right tabular-nums">{r.checkCount}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.grossCents)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : null}
      </Card>
    </div>
  );
}
