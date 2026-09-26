"use client";

import { Suspense } from "react";
import { ArrowDownLeft, ArrowUpRight } from "lucide-react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { useMoney } from "@/lib/money";
import { FxNote, useScopedKpi } from "@/components/money-scope";
import { useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { CashMovementRow, CashMovementsReport } from "@/lib/types";
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
      <CashMovementsPage />
    </Suspense>
  );
}

function CashMovementsPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const { data, error, isLoading, mutate } = useApi<CashMovementsReport>(
    reportKey("/v1/reports/cash-movements", range)
  );
  const m = useMoney();
  const kpi = useScopedKpi();
  type V = CashMovementsReport["byVenue"][number];
  const cur = (r: V) => r.currency ?? m.currencyOf(r.venueId);
  const scoped = (cents: number, f: (r: V) => number) => kpi(data?.money, cents, m.perCurrency(data?.byVenue ?? [], cur, f));
  const paidIn = data ? scoped(data.paidInCents, (r) => r.paidInCents) : undefined;
  const paidOut = data ? scoped(data.paidOutCents, (r) => r.paidOutCents) : undefined;
  const net = data ? scoped(data.netCents, (r) => r.netCents) : undefined;
  const both = (k?: { value: string; sub?: string }) => [k?.value, k?.sub].filter(Boolean).join(" — ");

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("cash-movements"),
      reportTitle: t("cash_title"),
      notes: [t("cash_note")],
      kpis: [
        { label: t("cash_paid_in"), value: both(paidIn) },
        { label: t("cash_paid_out"), value: both(paidOut) },
        { label: t("cash_net"), value: both(net) },
      ],
      sections: [
        ...storeExport.byStore<CashMovementsReport["byVenue"][number]>(
          [
            col.money(t("cash_paid_in"), (r) => r.paidInCents),
            col.money(t("cash_paid_out"), (r) => r.paidOutCents),
            col.money(t("cash_net"), (r) => r.netCents),
            col.int(t("cash_movements_n"), (r) => r.inCount + r.outCount),
          ],
          data.byVenue,
          [
            T(t("col_total")),
            m.totalCell(data.byVenue, cur, (r) => r.paidInCents),
            m.totalCell(data.byVenue, cur, (r) => r.paidOutCents),
            m.totalCell(data.byVenue, cur, (r) => r.netCents),
            Int(data.inCount + data.outCount),
          ]
        ),
        {
          title: t("cash_title"),
          columns: storeExport.withStore([
            col.text<CashMovementRow>(t("col_time"), (r) => (r.createdAt ? fmt.dateTime(r.createdAt) : "—")),
            col.text<CashMovementRow>(t("col_direction"), (r) =>
              r.direction === "IN" ? t("cash_in_label") : t("cash_out_label")
            ),
            col.text<CashMovementRow>(t("col_reason"), (r) => r.reason ?? "—"),
            col.text<CashMovementRow>(t("col_by"), (r) => r.user ?? "—"),
            col.money<CashMovementRow>(t("col_amount"), (r) =>
              r.direction === "IN" ? r.amountCents : -r.amountCents
            ),
          ]),
          rows: data.rows,
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("cash_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || data.rows.length === 0} />}
      />
      <DateRangePicker />
      <FxNote money={data?.money} />
      {data && (
        <StoreSplit
          rows={data.byVenue}
          title={t("cash_by_store")}
          cols={[
            { key: "in", label: t("cash_paid_in"), value: (r) => r.paidInCents, money: true },
            { key: "out", label: t("cash_paid_out"), value: (r) => r.paidOutCents, money: true },
            { key: "net", label: t("cash_net"), value: (r) => r.netCents, money: true, signed: true, strong: true },
            { key: "n", label: t("cash_movements_n"), value: (r) => r.inCount + r.outCount, format: String, hide: "sm" },
          ]}
        />
      )}

      <div className="grid grid-cols-3 gap-3">
        <Kpi label={t("cash_paid_in")} value={paidIn?.value} sub={paidIn?.sub} loading={isLoading} />
        <Kpi label={t("cash_paid_out")} value={paidOut?.value} sub={paidOut?.sub} loading={isLoading} />
        <Kpi label={t("cash_net")} value={net?.value} sub={net?.sub} loading={isLoading} accent />
      </div>

      <p className="px-1 text-xs text-neutral-500">{t("cash_note")}</p>

      <Card>
        {isLoading ? (
          <TableSkeleton rows={4} />
        ) : error ? (
          <ErrorState message={error.message} onRetry={() => mutate()} />
        ) : data && data.rows.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_time")}</TableHead>
                <TableHead>{t("col_direction")}</TableHead>
                <TableHead>{t("col_reason")}</TableHead>
                <TableHead>{t("col_by")}</TableHead>
                <TableHead className="text-right">{t("col_amount")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.rows.map((r) => {
                const isIn = r.direction === "IN";
                return (
                  <TableRow key={`${r.venueId}/${r.movementId}`}>
                    <TableCell className="whitespace-nowrap text-xs text-neutral-500">
                      <span className="flex items-center gap-1.5">
                        {r.createdAt ? fmt.dateTime(r.createdAt) : "—"}
                        <StoreTag venueId={r.venueId} />
                      </span>
                    </TableCell>
                    <TableCell>
                      <span
                        className={`inline-flex items-center gap-1 text-xs font-medium ${
                          isIn ? "text-emerald-600" : "text-red-600"
                        }`}
                      >
                        {isIn ? (
                          <ArrowDownLeft className="h-3.5 w-3.5" />
                        ) : (
                          <ArrowUpRight className="h-3.5 w-3.5" />
                        )}
                        {isIn ? t("cash_in_label") : t("cash_out_label")}
                      </span>
                    </TableCell>
                    <TableCell className="max-w-[16rem] truncate text-xs text-neutral-600">
                      {r.reason ?? "—"}
                    </TableCell>
                    <TableCell className="text-xs text-neutral-500">{r.user ?? "—"}</TableCell>
                    <TableCell
                      className={`text-right font-medium tabular-nums ${
                        isIn ? "text-emerald-600" : "text-red-600"
                      }`}
                    >
                      {isIn ? "+" : "−"}
                      {m.fmtIn(r.currency ?? m.currencyOf(r.venueId), r.amountCents)}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        ) : (
          <EmptyState title={t("cash_empty")} hint={t("cash_empty_hint")} />
        )}
      </Card>
    </div>
  );
}
