"use client";

import { Suspense, useMemo } from "react";
import { BarChart } from "@tremor/react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD, CADShort } from "@/lib/format";
import { useI18n, useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, type ExportDoc } from "@/lib/export/doc";
import type { TablesReport, ZoneRow, ZoneTableRow } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { StoreBreakdown, StoreTag } from "@/components/store-breakdown";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";
import { Skeleton } from "@/components/ui/skeleton";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <TablesPage />
    </Suspense>
  );
}

function TablesPage() {
  const t = useT();
  const fmt = useFmt();
  const { name } = useI18n();
  const range = useRange();
  const meta = useExportMeta();
  const { data, error, isLoading, mutate } = useApi<TablesReport>(reportKey("/v1/reports/tables", range));

  const byTable = useMemo(
    () => [...(data?.byTable ?? [])].sort((a, b) => b.grossCents - a.grossCents),
    [data]
  );

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    const zoneSection = {
      title: t("tables_by_zone"),
      columns: [
        col.text<ZoneRow>(t("col_zone"), (z) => name(z.zoneNameFr, z.zoneNameEn)),
        col.int<ZoneRow>(t("col_checks"), (z) => z.checkCount),
        col.money<ZoneRow>(t("col_gross"), (z) => z.grossCents),
      ],
      rows: data.byZone,
    };
    const tableSection = {
      title: t("tables_by_table"),
      columns: [
        col.text<ZoneTableRow>(t("col_table"), (r) => r.tableLabel),
        col.text<ZoneTableRow>(t("col_zone"), (r) => r.zoneNameEn),
        col.int<ZoneTableRow>(t("col_checks"), (r) => r.checkCount),
        col.money<ZoneTableRow>(t("col_gross"), (r) => r.grossCents),
      ],
      rows: byTable,
    };
    return {
      ...meta("tables"),
      reportTitle: t("tables_title"),
      sections: [
        ...(data.byZone.length ? [zoneSection] : []),
        ...(byTable.length ? [tableSection] : []),
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("tables_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={
          <ExportMenu
            build={buildDoc}
            disabled={!data || (data.byZone.length === 0 && byTable.length === 0)}
          />
        }
      />
      <DateRangePicker />
      <StoreBreakdown />

      <Card>
        <CardHeader>
          <CardTitle>{t("tables_by_zone")}</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-52 w-full" />
          ) : error ? (
            <ErrorState message={error.message} onRetry={() => mutate()} />
          ) : data && data.byZone.length > 0 ? (
            <BarChart
              data={data.byZone.map((z) => ({ zone: name(z.zoneNameFr, z.zoneNameEn), [t("series_gross")]: z.grossCents }))}
              index="zone"
              categories={[t("series_gross")]}
              colors={["pink"]}
              valueFormatter={CADShort}
              yAxisWidth={52}
              showLegend={false}
              showAnimation
              className="h-52"
            />
          ) : (
            <EmptyState title={t("tables_zone_empty")} />
          )}
        </CardContent>
      </Card>

      <Card>
        {isLoading ? (
          <TableSkeleton rows={6} />
        ) : !error && byTable.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_table")}</TableHead>
                <TableHead>{t("col_zone")}</TableHead>
                <TableHead className="text-right">{t("col_checks")}</TableHead>
                <TableHead className="text-right">{t("col_gross")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {byTable.map((t) => (
                <TableRow key={`${t.venueId}/${t.tableId}`}>
                  <TableCell className="font-medium">
                    <span className="flex items-center gap-1.5">
                      {t.tableLabel}
                      <StoreTag venueId={t.venueId} />
                    </span>
                  </TableCell>
                  <TableCell className="text-xs text-neutral-500">{t.zoneNameEn}</TableCell>
                  <TableCell className="text-right tabular-nums">{t.checkCount}</TableCell>
                  <TableCell className="text-right font-medium tabular-nums">
                    {CAD(t.grossCents)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : !error ? (
          <EmptyState title={t("tables_table_empty")} hint={t("tables_table_empty_hint")} />
        ) : null}
      </Card>
    </div>
  );
}
