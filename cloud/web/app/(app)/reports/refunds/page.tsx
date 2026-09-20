"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, type ExportDoc } from "@/lib/export/doc";
import type { RefundsReport, RefundReasonRow, RefundListRow } from "@/lib/types";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Kpi } from "@/components/kpi";
import { PageHeader } from "@/components/page-header";
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
  const { data, error, isLoading, mutate } = useApi<RefundsReport>(
    reportKey("/v1/reports/refunds", range)
  );

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("refunds"),
      reportTitle: t("refunds_title"),
      notes: [t("refunds_note")],
      kpis: [
        { label: t("ref_count"), value: String(data.count) },
        { label: t("ref_amount"), value: CAD(data.grossCents) },
        { label: t("ref_vat"), value: CAD(data.vatCents) },
      ],
      sections: [
        {
          title: t("refunds_by_reason"),
          columns: [
            col.text<RefundReasonRow>(t("col_reason"), (r) => r.reason),
            col.int<RefundReasonRow>(t("col_checks"), (r) => r.count),
            col.money<RefundReasonRow>(t("col_amount"), (r) => r.grossCents),
            col.money<RefundReasonRow>(t("col_net"), (r) => r.netCents),
            col.money<RefundReasonRow>(t("col_vat"), (r) => r.vatCents),
          ],
          rows: data.byReason,
        },
        {
          title: t("refunds_list"),
          columns: [
            col.text<RefundListRow>(t("col_check"), (r) => (r.checkId ? `#${r.checkId}` : "—")),
            col.text<RefundListRow>(t("col_time"), (r) => (r.createdAt ? fmt.dateTime(r.createdAt) : "—")),
            col.text<RefundListRow>(t("col_table"), (r) => r.tableLabel ?? "—"),
            col.text<RefundListRow>(t("col_tender"), (r) =>
              r.tenderType ? t(`tender_${r.tenderType}` as MsgKey) : "—"
            ),
            col.text<RefundListRow>(t("col_reason"), (r) => r.reason ?? "—"),
            col.money<RefundListRow>(t("col_amount"), (r) => r.grossCents),
          ],
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

      <div className="grid grid-cols-3 gap-3">
        <Kpi label={t("ref_count")} value={data && String(data.count)} loading={isLoading} />
        <Kpi label={t("ref_amount")} value={data && CAD(data.grossCents)} loading={isLoading} accent />
        <Kpi label={t("ref_vat")} value={data && CAD(data.vatCents)} loading={isLoading} />
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
          {/* by reason — net of VAT, the owner's "why did we refund" view */}
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
                  <TableHead className="text-right">{t("col_vat")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.byReason.map((r) => (
                  <TableRow key={r.reason}>
                    <TableCell className="font-medium">{r.reason}</TableCell>
                    <TableCell className="text-right tabular-nums">{r.count}</TableCell>
                    <TableCell className="text-right tabular-nums text-red-600">
                      {CAD(r.grossCents)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{CAD(r.netCents)}</TableCell>
                    <TableCell className="text-right tabular-nums">{CAD(r.vatCents)}</TableCell>
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
                  <TableRow key={r.refundId}>
                    <TableCell className="font-medium">{r.checkId ? `#${r.checkId}` : "—"}</TableCell>
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
                      {CAD(r.grossCents)}
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
