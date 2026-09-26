"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { useMoney } from "@/lib/money";
import { FxNote, useScopedKpi } from "@/components/money-scope";
import { useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { ExceptionsReport, VoidRow } from "@/lib/types";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Kpi } from "@/components/kpi";
import { PageHeader } from "@/components/page-header";
import { StoreSplit, StoreTag } from "@/components/store-breakdown";
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
  const storeExport = useStoreExport();
  const { data, error, isLoading, mutate } = useApi<ExceptionsReport>(
    reportKey("/v1/reports/exceptions", range)
  );
  const m = useMoney();
  const kpi = useScopedKpi();
  type V = ExceptionsReport["byVenue"][number];
  const cur = (r: V) => r.currency ?? m.currencyOf(r.venueId);
  const scoped = (cents: number, f: (r: V) => number) =>
    kpi(data?.money, cents, m.perCurrency(data?.byVenue ?? [], cur, f));
  const voidAmt = data ? scoped(data.voidAmountCents, (r) => r.voidAmountCents) : undefined;
  const cork = data ? scoped(data.corkageCents, (r) => r.corkageCents) : undefined;

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("exceptions"),
      reportTitle: t("exceptions_title"),
      kpis: [
        { label: t("exc_voids"), value: String(data.voidCount) },
        { label: t("exc_void_amount"), value: [voidAmt?.value, voidAmt?.sub].filter(Boolean).join(" — ") },
        { label: t("exc_corkage"), value: [cork?.value, cork?.sub].filter(Boolean).join(" — ") },
      ],
      sections: [
        ...storeExport.byStore<ExceptionsReport["byVenue"][number]>(
          [
            col.int(t("exc_voids"), (r) => r.voidCount),
            col.money(t("exc_void_amount"), (r) => r.voidAmountCents),
            col.money(t("exc_corkage"), (r) => r.corkageCents),
          ],
          data.byVenue,
          [
            T(t("col_total")),
            Int(data.voidCount),
            m.totalCell(data.byVenue, cur, (r) => r.voidAmountCents),
            m.totalCell(data.byVenue, cur, (r) => r.corkageCents),
          ]
        ),
        {
          title: t("exceptions_title"),
          columns: storeExport.withStore([
            col.text<VoidRow>(t("col_check"), (v) => `#${v.checkId}`),
            col.text<VoidRow>(t("col_voided"), (v) => fmt.dateTime(v.voidedAt)),
            col.text<VoidRow>(t("col_table"), (v) => v.tableLabel),
            col.money<VoidRow>(t("col_amount"), (v) => v.amountCents),
            col.text<VoidRow>(t("col_reason"), (v) => v.reason),
            col.text<VoidRow>(t("col_by"), (v) => v.voidedBy),
          ]),
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
      <FxNote money={data?.money} />
      {data && (
        <StoreSplit
          rows={data.byVenue}
          title={t("exc_by_store")}
          chartKey="amount"
          cols={[
            { key: "voids", label: t("exc_voids"), value: (r) => r.voidCount, format: String },
            { key: "amount", label: t("exc_void_amount"), value: (r) => r.voidAmountCents, money: true, strong: true },
            { key: "corkage", label: t("exc_corkage"), value: (r) => r.corkageCents, money: true, hide: "sm" },
          ]}
        />
      )}

      <div className="grid grid-cols-3 gap-3">
        <Kpi label={t("exc_voids")} value={data && String(data.voidCount)} loading={isLoading} />
        <Kpi label={t("exc_void_amount")} value={voidAmt?.value} sub={voidAmt?.sub} loading={isLoading} />
        <Kpi label={t("exc_corkage")} value={cork?.value} sub={cork?.sub} loading={isLoading} />
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
                <TableRow key={`${v.venueId}/${v.checkId}`}>
                  <TableCell className="font-medium">
                    <span className="flex items-center gap-1.5">
                      #{v.checkId}
                      <StoreTag venueId={v.venueId} />
                    </span>
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-xs text-neutral-500">
                    {fmt.dateTime(v.voidedAt)}
                  </TableCell>
                  <TableCell>{v.tableLabel}</TableCell>
                  <TableCell className="text-right font-medium tabular-nums text-red-600">
                    {m.fmtIn(v.currency ?? m.currencyOf(v.venueId), v.amountCents)}
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
