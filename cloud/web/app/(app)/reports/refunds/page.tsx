"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { useMoney } from "@/lib/money";
import { FxNote, useScopedKpi } from "@/components/money-scope";
import { useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { RefundsReport, RefundReasonRow, RefundListRow } from "@/lib/types";
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
      <RefundsPage />
    </Suspense>
  );
}

function RefundsPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const { data, error, isLoading, mutate } = useApi<RefundsReport>(
    reportKey("/v1/reports/refunds", range)
  );
  const m = useMoney();
  const kpi = useScopedKpi();
  const money = data?.money;
  const fmtC = (n: number) => m.fmtScope(money, n);
  type V = RefundsReport["byVenue"][number];
  const cur = (r: V) => r.currency ?? m.currencyOf(r.venueId);
  const scoped = (cents: number, f: (r: V) => number) => kpi(money, cents, m.perCurrency(data?.byVenue ?? [], cur, f));
  const gross = data ? scoped(data.grossCents, (r) => r.grossCents) : undefined;
  const tax = data ? scoped(data.taxCents, (r) => r.taxCents) : undefined;
  // refunds paid back in cash round to 5¢: the signed difference, per store in its own currency
  const hasRounding = (data?.byVenue ?? []).some((r) => r.roundingAdjustmentCents != null);
  const both = (k?: { value: string; sub?: string }) => [k?.value, k?.sub].filter(Boolean).join(" — ");

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("refunds"),
      reportTitle: t("refunds_title"),
      notes: [t("refunds_note")],
      kpis: [
        { label: t("ref_count"), value: String(data.count) },
        { label: t("ref_amount"), value: both(gross) },
        { label: t("ref_tax"), value: both(tax) },
      ],
      sections: [
        ...storeExport.byStore<RefundsReport["byVenue"][number]>(
          [
            col.int(t("col_checks"), (r) => r.count),
            col.money(t("col_amount"), (r) => r.grossCents),
            col.money(t("col_net"), (r) => r.netCents),
            col.money(t("col_tax"), (r) => r.taxCents),
            ...(hasRounding ? [col.money<V>(t("cash_rounding"), (r) => r.roundingAdjustmentCents ?? 0)] : []),
          ],
          data.byVenue,
          [
            T(t("col_total")),
            Int(data.count),
            m.totalCell(data.byVenue, cur, (r) => r.grossCents),
            m.totalCell(data.byVenue, cur, (r) => r.netCents),
            m.totalCell(data.byVenue, cur, (r) => r.taxCents),
            ...(hasRounding ? [m.totalCell(data.byVenue, cur, (r) => r.roundingAdjustmentCents ?? 0)] : []),
          ]
        ),
        {
          title: t("refunds_by_reason"),
          columns: [
            col.text<RefundReasonRow>(t("col_reason"), (r) => r.reason),
            col.int<RefundReasonRow>(t("col_checks"), (r) => r.count),
            col.money<RefundReasonRow>(t("col_amount"), (r) => r.grossCents),
            col.money<RefundReasonRow>(t("col_net"), (r) => r.netCents),
            col.money<RefundReasonRow>(t("col_tax"), (r) => r.taxCents),
          ],
          rows: data.byReason,
          // by-reason figures combine stores: converted (≈) when they sell in several currencies
          ...(money?.approximate ? { rowCurrency: () => `≈ ${money.currency}` } : {}),
        },
        {
          title: t("refunds_list"),
          columns: storeExport.withStore([
            col.text<RefundListRow>(t("col_check"), (r) => (r.checkId ? `#${r.checkId}` : "—")),
            col.text<RefundListRow>(t("col_time"), (r) => (r.createdAt ? fmt.dateTime(r.createdAt) : "—")),
            col.text<RefundListRow>(t("col_table"), (r) => r.tableLabel ?? "—"),
            col.text<RefundListRow>(t("col_tender"), (r) =>
              r.tenderType ? t(`tender_${r.tenderType}` as MsgKey) : "—"
            ),
            col.text<RefundListRow>(t("col_reason"), (r) => r.reason ?? "—"),
            col.money<RefundListRow>(t("col_amount"), (r) => r.grossCents),
            ...(hasRounding ? [col.money<RefundListRow>(t("cash_rounding"), (r) => r.roundingAdjustmentCents ?? 0)] : []),
          ]),
          rows: data.rows,
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("refunds_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || data.count === 0} />}
      />
      <DateRangePicker />
      <FxNote money={money} />
      {data && (
        <StoreSplit
          rows={data.byVenue}
          title={t("refunds_by_store")}
          chartKey="gross"
          cols={[
            { key: "count", label: t("ref_count"), value: (r) => r.count, format: String },
            { key: "gross", label: t("ref_amount"), value: (r) => r.grossCents, money: true, strong: true },
            { key: "net", label: t("col_net"), value: (r) => r.netCents, money: true, hide: "sm" },
            { key: "tax", label: t("ref_tax"), value: (r) => r.taxCents, money: true, hide: "sm" },
            ...(hasRounding
              ? [
                  {
                    key: "rounding",
                    label: t("cash_rounding"),
                    value: (r: V) => r.roundingAdjustmentCents ?? 0,
                    money: true,
                    signed: true,
                    hide: "md" as const,
                  },
                ]
              : []),
          ]}
        />
      )}

      <div className="grid grid-cols-3 gap-3">
        <Kpi label={t("ref_count")} value={data && String(data.count)} loading={isLoading} />
        <Kpi label={t("ref_amount")} value={gross?.value} sub={gross?.sub} loading={isLoading} accent />
        <Kpi label={t("ref_tax")} value={tax?.value} sub={tax?.sub} loading={isLoading} />
      </div>

      <p className="px-1 text-xs text-neutral-500">{t("refunds_note")}</p>

      {isLoading ? (
        <Card>
          <TableSkeleton rows={4} />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : data && data.count > 0 ? (
        <>
          {/* by reason — net of tax, the owner's "why did we refund" view */}
          <Card>
            <div className="border-b border-neutral-100 px-5 py-3 text-sm font-semibold">
              {t("refunds_by_reason")}
            </div>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("col_reason")}</TableHead>
                  <TableHead className="text-right">{t("col_checks")}</TableHead>
                  <TableHead className="text-right">{t("col_amount")}</TableHead>
                  <TableHead className="text-right">{t("col_net")}</TableHead>
                  <TableHead className="text-right">{t("col_tax")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.byReason.map((r) => (
                  <TableRow key={r.reason}>
                    <TableCell className="font-medium">{r.reason}</TableCell>
                    <TableCell className="text-right tabular-nums">{r.count}</TableCell>
                    <TableCell className="text-right tabular-nums text-red-600">
                      {fmtC(r.grossCents)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{fmtC(r.netCents)}</TableCell>
                    <TableCell className="text-right tabular-nums">{fmtC(r.taxCents)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>

          {/* individual refunds */}
          <Card>
            <div className="border-b border-neutral-100 px-5 py-3 text-sm font-semibold">
              {t("refunds_list")}
            </div>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("col_check")}</TableHead>
                  <TableHead>{t("col_time")}</TableHead>
                  <TableHead>{t("col_table")}</TableHead>
                  <TableHead>{t("col_tender")}</TableHead>
                  <TableHead>{t("col_reason")}</TableHead>
                  <TableHead className="text-right">{t("col_amount")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.rows.map((r) => (
                  <TableRow key={`${r.venueId}/${r.refundId}`}>
                    <TableCell className="font-medium">
                      <span className="flex items-center gap-1.5">
                        {r.checkId ? `#${r.checkId}` : "—"}
                        <StoreTag venueId={r.venueId} />
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-xs text-neutral-500">
                      {r.createdAt ? fmt.dateTime(r.createdAt) : "—"}
                    </TableCell>
                    <TableCell>{r.tableLabel ?? "—"}</TableCell>
                    <TableCell className="text-xs text-neutral-600">
                      {r.tenderType ? t(`tender_${r.tenderType}` as MsgKey) : "—"}
                    </TableCell>
                    <TableCell className="max-w-[14rem] truncate text-xs text-neutral-600">
                      {r.reason ?? "—"}
                    </TableCell>
                    <TableCell className="text-right font-medium tabular-nums text-red-600">
                      {m.fmtIn(r.currency ?? m.currencyOf(r.venueId), r.grossCents)}
                      {!!r.roundingAdjustmentCents && (
                        <div className="text-[11px] font-normal text-neutral-500">
                          {t("cash_rounding")} {m.signedIn(r.currency ?? m.currencyOf(r.venueId), r.roundingAdjustmentCents)}
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        </>
      ) : (
        <Card>
          <EmptyState title={t("refunds_empty")} hint={t("refunds_empty_hint")} />
        </Card>
      )}
    </div>
  );
}
