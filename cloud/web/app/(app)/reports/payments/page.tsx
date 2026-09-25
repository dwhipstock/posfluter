"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD, CADShort } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { PaymentRow, PaymentsReport } from "@/lib/types";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { useStores } from "@/lib/store";
import { SERIES } from "@/lib/theme";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback } from "@/components/states";
import { BarChart, Donut } from "@/components/charts";
import { useStoreSeries } from "@/components/store-breakdown";
import { Skeleton } from "@/components/ui/skeleton";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <PaymentsPage />
    </Suspense>
  );
}

type VenuePay = PaymentsReport["byVenue"][number];

function PaymentsPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const { combined } = useStores();
  const series = useStoreSeries(t("col_amount"));
  const { data, error, isLoading, mutate } = useApi<PaymentsReport>(reportKey("/v1/reports/payments", range));
  const tender = (type: string) => t(`tender_${type}` as MsgKey);
  const share = (cents: number) =>
    data && data.totalCents > 0 ? `${Math.round((cents / data.totalCents) * 100)}%` : "—";

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    const types = data.rows.map((r) => r.type);
    const amountOf = (v: VenuePay, type: string) => v.rows.find((r) => r.type === type)?.amountCents ?? 0;
    return {
      ...meta("payments"),
      reportTitle: t("payments_title"),
      kpis: [
        { label: t("col_amount"), value: CAD(data.totalCents) },
        { label: t("col_payments"), value: String(data.rows.reduce((n, r) => n + r.count, 0)) },
      ],
      sections: [
        ...storeExport.byStore<VenuePay>(
          [
            ...types.map((type) => col.money<VenuePay>(tender(type), (v) => amountOf(v, type))),
            col.int<VenuePay>(t("col_payments"), (v) => v.rows.reduce((n, r) => n + r.count, 0)),
            col.money<VenuePay>(t("col_total_short"), (v) => v.totalCents),
          ],
          data.byVenue,
          [
            T(t("col_total_short")),
            ...data.rows.map((r) => Money(r.amountCents)),
            Int(data.rows.reduce((n, r) => n + r.count, 0)),
            Money(data.totalCents),
          ]
        ),
        {
          title: t("payments_title"),
          columns: [
            col.text<PaymentRow>(t("col_tender"), (r) => tender(r.type)),
            col.int<PaymentRow>(t("col_payments"), (r) => r.count),
            col.money<PaymentRow>(t("col_amount"), (r) => r.amountCents),
            col.text<PaymentRow>(t("col_share"), (r) => share(r.amountCents), { align: "right" }),
          ],
          rows: data.rows,
          total: [T(t("col_total_short")), Int(data.rows.reduce((n, r) => n + r.count, 0)), Money(data.totalCents), T("")],
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
      {data && data.rows.length > 0 && <PaymentsByStore report={data} />}

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
          <Card>
            <CardHeader>
              <CardTitle>{t("dash_payment_mix")}</CardTitle>
              {combined && <CardDescription>{t("chart_per_store_stacked")}</CardDescription>}
            </CardHeader>
            <CardContent>
              {combined ? (
                <BarChart
                  data={data.rows.map((r) => ({
                    label: tender(r.type),
                    values: Object.fromEntries(
                      data.byVenue.map((v) => [v.venueId, v.rows.find((x) => x.type === r.type)?.amountCents ?? 0])
                    ),
                  }))}
                  series={series}
                  format={CAD}
                  axisFormat={CADShort}
                  ariaLabel={t("dash_payment_mix")}
                />
              ) : (
                <div className="flex justify-center">
                  <Donut
                    items={data.rows.map((r, i) => ({ key: r.type, label: tender(r.type), value: r.amountCents, color: SERIES[i % SERIES.length] }))}
                    format={CAD}
                    center={CAD(data.totalCents)}
                    size={208}
                    ariaLabel={t("dash_payment_mix")}
                  />
                </div>
              )}
            </CardContent>
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
                        {!combined && (
                          <span className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: SERIES[i % SERIES.length] }} />
                        )}
                        {tender(r.type)}
                      </span>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{r.count}</TableCell>
                    <TableCell className="text-right tabular-nums">{CAD(r.amountCents)}</TableCell>
                    <TableCell className="text-right tabular-nums text-neutral-500">{share(r.amountCents)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
              <TableFooter>
                <TableRow>
                  <TableCell>{t("col_total_short")}</TableCell>
                  <TableCell className="text-right tabular-nums">{data.rows.reduce((n, r) => n + r.count, 0)}</TableCell>
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

/** "All stores": the tender mix of each store side by side, with the combined row. */
function PaymentsByStore({ report }: { report: PaymentsReport }) {
  const t = useT();
  const { combined, nameOf, colorOf } = useStores();
  if (!combined) return null;
  const types = report.rows.map((r) => r.type);
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("payments_by_store")}</CardTitle>
        <CardDescription>{t("store_breakdown_sub")}</CardDescription>
      </CardHeader>
      <div className="px-2 pb-2">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("col_store")}</TableHead>
              {types.map((type) => (
                <TableHead key={type} className="text-right">
                  {t(`tender_${type}` as MsgKey)}
                </TableHead>
              ))}
              <TableHead className="text-right">{t("col_total_short")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {report.byVenue.map((v) => (
              <TableRow key={v.venueId}>
                <TableCell className="font-medium">
                  <span className="flex items-center gap-2">
                    <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: colorOf(v.venueId) }} />
                    {nameOf(v.venueId)}
                  </span>
                </TableCell>
                {types.map((type) => (
                  <TableCell key={type} className="text-right tabular-nums">
                    {CAD(v.rows.find((r) => r.type === type)?.amountCents ?? 0)}
                  </TableCell>
                ))}
                <TableCell className="text-right font-semibold tabular-nums">{CAD(v.totalCents)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
          <TableFooter>
            <TableRow>
              <TableCell>{t("col_total")}</TableCell>
              {report.rows.map((r) => (
                <TableCell key={r.type} className="text-right tabular-nums">
                  {CAD(r.amountCents)}
                </TableCell>
              ))}
              <TableCell className="text-right tabular-nums">{CAD(report.totalCents)}</TableCell>
            </TableRow>
          </TableFooter>
        </Table>
      </div>
    </Card>
  );
}
