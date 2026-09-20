"use client";

import { Suspense } from "react";
import { DonutChart } from "@tremor/react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { PaymentRow, PaymentsReport } from "@/lib/types";
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
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";
import { MONO_COLORS, MONO_HEX } from "@/components/chart-colors";
import { Skeleton } from "@/components/ui/skeleton";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <PaymentsPage />
    </Suspense>
  );
}

function PaymentsPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const { data, error, isLoading, mutate } = useApi<PaymentsReport>(
    reportKey("/v1/reports/payments", range)
  );

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("payments"),
      reportTitle: t("payments_title"),
      kpis: [
        { label: t("col_amount"), value: CAD(data.totalCents) },
        { label: t("col_payments"), value: String(data.rows.reduce((n, r) => n + r.count, 0)) },
      ],
      sections: [
        {
          columns: [
            col.text<PaymentRow>(t("col_tender"), (r) => t(`tender_${r.type}` as MsgKey)),
            col.int<PaymentRow>(t("col_payments"), (r) => r.count),
            col.money<PaymentRow>(t("col_amount"), (r) => r.amountCents),
            col.text<PaymentRow>(
              t("col_share"),
              (r) =>
                data.totalCents > 0
                  ? `${Math.round((r.amountCents / data.totalCents) * 100)}%`
                  : "—",
              { align: "right" }
            ),
          ],
          rows: data.rows,
          total: [
            T(t("col_total_short")),
            Int(data.rows.reduce((n, r) => n + r.count, 0)),
            Money(data.totalCents),
            T(""),
          ],
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("payments_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || data.rows.length === 0} />}
      />
      <DateRangePicker />

      {isLoading ? (
        <Card className="p-5">
          <Skeleton className="mx-auto h-56 w-56 rounded-full" />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : data && data.rows.length > 0 ? (
        <>
          <Card className="p-5">
            <DonutChart
              data={data.rows.map((r) => ({ name: t(`tender_${r.type}` as MsgKey), value: r.amountCents }))}
              category="value"
              index="name"
              colors={MONO_COLORS}
              valueFormatter={CAD}
              label={CAD(data.totalCents)}
              showAnimation
              className="h-60"
            />
          </Card>
          <Card>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("col_tender")}</TableHead>
                  <TableHead className="text-right">{t("col_payments")}</TableHead>
                  <TableHead className="text-right">{t("col_amount")}</TableHead>
                  <TableHead className="text-right">{t("col_share")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.rows.map((r, i) => (
                  <TableRow key={r.type}>
                    <TableCell>
                      <span className="flex items-center gap-2 font-medium">
                        <span
                          className="h-2.5 w-2.5 rounded-full"
                          style={{ backgroundColor: MONO_HEX[i % MONO_HEX.length] }}
                        />
                        {t(`tender_${r.type}` as MsgKey)}
                      </span>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{r.count}</TableCell>
                    <TableCell className="text-right tabular-nums">{CAD(r.amountCents)}</TableCell>
                    <TableCell className="text-right tabular-nums text-neutral-500">
                      {data.totalCents > 0
                        ? `${Math.round((r.amountCents / data.totalCents) * 100)}%`
                        : "—"}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
              <TableFooter>
                <TableRow>
                  <TableCell>{t("col_total_short")}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {data.rows.reduce((n, r) => n + r.count, 0)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(data.totalCents)}</TableCell>
                  <TableCell />
                </TableRow>
              </TableFooter>
            </Table>
          </Card>
        </>
      ) : (
        <Card>
          <EmptyState title={t("payments_empty")} hint={t("empty_no_payments_hint")} />
        </Card>
      )}
    </div>
  );
}
