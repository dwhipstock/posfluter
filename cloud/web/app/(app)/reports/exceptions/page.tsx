"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, type ExportDoc } from "@/lib/export/doc";
import type { ExceptionsReport, VoidRow } from "@/lib/types";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Kpi } from "@/components/kpi";
import { PageHeader } from "@/components/page-header";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <ExceptionsPage />
    </Suspense>
  );
}

function ExceptionsPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const { data, error, isLoading, mutate } = useApi<ExceptionsReport>(
    reportKey("/v1/reports/exceptions", range)
  );

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("exceptions"),
      reportTitle: t("exceptions_title"),
      kpis: [
        { label: t("exc_voids"), value: String(data.voidCount) },
        { label: t("exc_void_amount"), value: CAD(data.voidAmountCents) },
        { label: t("exc_corkage"), value: CAD(data.corkageCents) },
      ],
      sections: [
        {
          columns: [
            col.text<VoidRow>(t("col_check"), (v) => `#${v.checkId}`),
            col.text<VoidRow>(t("col_voided"), (v) => fmt.dateTime(v.voidedAt)),
            col.text<VoidRow>(t("col_table"), (v) => v.tableLabel),
            col.money<VoidRow>(t("col_amount"), (v) => v.amountCents),
            col.text<VoidRow>(t("col_reason"), (v) => v.reason),
            col.text<VoidRow>(t("col_by"), (v) => v.voidedBy),
          ],
          rows: data.voids,
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("exceptions_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={
          <ExportMenu
            build={buildDoc}
            disabled={!data || (data.voids.length === 0 && data.corkageCents === 0)}
          />
        }
      />
      <DateRangePicker />

      <div className="grid grid-cols-3 gap-3">
        <Kpi label={t("exc_voids")} value={data && String(data.voidCount)} loading={isLoading} />
        <Kpi label={t("exc_void_amount")} value={data && CAD(data.voidAmountCents)} loading={isLoading} />
        <Kpi label={t("exc_corkage")} value={data && CAD(data.corkageCents)} loading={isLoading} />
      </div>

      <Card>
        {isLoading ? (
          <TableSkeleton rows={4} />
        ) : error ? (
          <ErrorState message={error.message} onRetry={() => mutate()} />
        ) : data && data.voids.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_check")}</TableHead>
                <TableHead>{t("col_voided")}</TableHead>
                <TableHead>{t("col_table")}</TableHead>
                <TableHead className="text-right">{t("col_amount")}</TableHead>
                <TableHead>{t("col_reason")}</TableHead>
                <TableHead>{t("col_by")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.voids.map((v) => (
                <TableRow key={v.checkId}>
                  <TableCell className="font-medium">#{v.checkId}</TableCell>
                  <TableCell className="whitespace-nowrap text-xs text-neutral-500">
                    {fmt.dateTime(v.voidedAt)}
                  </TableCell>
                  <TableCell>{v.tableLabel}</TableCell>
                  <TableCell className="text-right font-medium tabular-nums text-red-600">
                    {CAD(v.amountCents)}
                  </TableCell>
                  <TableCell className="max-w-[14rem] truncate text-xs text-neutral-600">
                    {v.reason}
                  </TableCell>
                  <TableCell className="text-xs text-neutral-500">{v.voidedBy}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <EmptyState title={t("exc_empty")} hint={t("exc_empty_hint")} />
        )}
      </Card>
    </div>
  );
}
