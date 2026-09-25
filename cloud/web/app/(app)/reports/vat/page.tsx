"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { DayRow, VatReport } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { StoreBreakdown } from "@/components/store-breakdown";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <VatPage />
    </Suspense>
  );
}

function VatPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const { data, error, isLoading, mutate } = useApi<VatReport>(reportKey("/v1/reports/vat", range));

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("vat"),
      reportTitle: t("vat_title"),
      notes: [t("vat_note")],
      kpis: [
        { label: t("col_gross"), value: CAD(data.totals.grossCents) },
        { label: t("col_net"), value: CAD(data.totals.netCents) },
        { label: t("col_vat"), value: CAD(data.totals.vatCents) },
        { label: t("col_checks"), value: String(data.totals.checkCount) },
      ],
      sections: [
        {
          columns: [
            col.text<DayRow>(t("col_date"), (r) => fmt.dayYear(r.date)),
            col.money<DayRow>(t("col_gross"), (r) => r.grossCents),
            col.money<DayRow>(t("col_net"), (r) => r.netCents),
            col.money<DayRow>(t("col_vat"), (r) => r.vatCents),
            col.int<DayRow>(t("col_checks"), (r) => r.checkCount),
          ],
          rows: data.rows,
          total: [
            T(t("col_total")),
            Money(data.totals.grossCents),
            Money(data.totals.netCents),
            Money(data.totals.vatCents),
            Int(data.totals.checkCount),
          ],
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("vat_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || data.rows.length === 0} />}
      />
      <DateRangePicker />
      <StoreBreakdown />

      <Card>
        <div className="flex flex-wrap items-center gap-2 border-b border-neutral-100 px-5 py-3">
          <Badge variant="pink">{t("vat_included_badge", { rate: data?.ratePercent ?? 7 })}</Badge>
          <span className="text-xs text-neutral-500">{t("vat_note")}</span>
        </div>
        {isLoading ? (
          <TableSkeleton rows={6} />
        ) : error ? (
          <ErrorState message={error.message} onRetry={() => mutate()} />
        ) : data && data.rows.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_date")}</TableHead>
                <TableHead className="text-right">{t("col_gross")}</TableHead>
                <TableHead className="text-right">{t("col_net")}</TableHead>
                <TableHead className="text-right">{t("col_vat")}</TableHead>
                <TableHead className="text-right">{t("col_checks")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.date}>
                  <TableCell className="font-medium">{fmt.day(r.date)}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.grossCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.netCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.vatCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{r.checkCount}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell>{t("col_total")}</TableCell>
                <TableCell className="text-right tabular-nums">{CAD(data.totals.grossCents)}</TableCell>
                <TableCell className="text-right tabular-nums">{CAD(data.totals.netCents)}</TableCell>
                <TableCell className="text-right tabular-nums text-accent">
                  {CAD(data.totals.vatCents)}
                </TableCell>
                <TableCell className="text-right tabular-nums">{data.totals.checkCount}</TableCell>
              </TableRow>
            </TableFooter>
          </Table>
        ) : (
          <EmptyState title={t("vat_empty")} hint={t("vat_empty_hint")} />
        )}
      </Card>
    </div>
  );
}
