"use client";

import { Suspense } from "react";
import Link from "next/link";
import { AreaChart, BarChart, DonutChart } from "@tremor/react";
import { ChevronRight } from "lucide-react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD, CADShort, hourLabel } from "@/lib/format";
import { useI18n, useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import type { DayRow, HourlyReport, ItemsReport, PaymentsReport, Summary } from "@/lib/types";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Kpi } from "@/components/kpi";
import { PageHeader } from "@/components/page-header";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback } from "@/components/states";
import { MONO_COLORS, MONO_HEX } from "@/components/chart-colors";
import { StoreBreakdown } from "@/components/store-breakdown";
import { useStoreHref } from "@/lib/store";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <Dashboard />
    </Suspense>
  );
}

function Dashboard() {
  const t = useT();
  const fmt = useFmt();
  const { name, nameAlt } = useI18n();
  const range = useRange();
  const meta = useExportMeta();
  const storeHref = useStoreHref();
  const multiDay = range.from !== range.to;
  const summary = useApi<Summary>(reportKey("/v1/reports/summary", range));
  const payments = useApi<PaymentsReport>(reportKey("/v1/reports/payments", range));
  const hourly = useApi<HourlyReport>(reportKey("/v1/reports/hourly", range));
  const items = useApi<ItemsReport>(reportKey("/v1/reports/items", range));

  const s = summary.data;
  const sGross = t("series_gross");
  const sNet = t("series_net");

  const buildDoc = (): ExportDoc | null => {
    if (!s) return null;
    return {
      ...meta("summary"),
      reportTitle: t("dash_title"),
      kpis: [
        { label: t("kpi_gross"), value: CAD(s.grossCents) },
        { label: t("kpi_net"), value: CAD(s.netCents) },
        { label: t("kpi_tax"), value: CAD(s.taxCents) },
        { label: t("kpi_checks"), value: String(s.checkCount) },
        { label: t("kpi_avg_check"), value: CAD(s.avgCheckCents) },
        { label: t("exc_voids"), value: String(s.voidCount) },
        { label: t("exc_void_amount"), value: CAD(s.voidAmountCents) },
        { label: t("ref_count"), value: String(s.refundCount) },
        { label: t("ref_amount"), value: CAD(s.refundAmountCents) },
        { label: t("exc_corkage"), value: CAD(s.corkageCents) },
        { label: t("svc_charge"), value: CAD(s.serviceChargeCents) },
      ],
      sections: [
        {
          title: t("summary_by_day"),
          columns: [
            col.text<DayRow>(t("col_date"), (r) => fmt.dayYear(r.date)),
            col.money<DayRow>(t("col_gross"), (r) => r.grossCents),
            col.money<DayRow>(t("col_net"), (r) => r.netCents),
            col.money<DayRow>(t("col_tax"), (r) => r.taxCents),
            col.int<DayRow>(t("col_checks"), (r) => r.checkCount),
          ],
          rows: s.byDay,
          total: [
            T(t("col_total")),
            Money(s.grossCents),
            Money(s.netCents),
            Money(s.taxCents),
            Int(s.checkCount),
          ],
        },
      ],
    };
  };

  return (
    <div className="space-y-4 md:space-y-6">
      <PageHeader
        title={t("dash_title")}
        sub={fmt.rangeLabel(range)}
        action={<ExportMenu build={buildDoc} disabled={!s} />}
      />
      <DateRangePicker />

      {summary.error ? (
        <Card>
          <ErrorState message={summary.error.message} onRetry={() => summary.mutate()} />
        </Card>
      ) : (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5">
          <Kpi label={t("kpi_gross")} value={s && CAD(s.grossCents)} accent loading={summary.isLoading} />
          <Kpi label={t("kpi_net")} value={s && CAD(s.netCents)} loading={summary.isLoading} />
          <Kpi label={t("kpi_tax")} value={s && CAD(s.taxCents)} loading={summary.isLoading} />
          <Kpi label={t("kpi_checks")} value={s && String(s.checkCount)} loading={summary.isLoading} />
          <Kpi
            label={t("kpi_avg_check")}
            value={s && CAD(s.avgCheckCents)}
            loading={summary.isLoading}
            className="col-span-2 md:col-span-1"
          />
        </div>
      )}

      {s && <StoreBreakdown rows={s.byVenue} chart />}

      {multiDay && (
        <Card>
          <CardHeader>
            <CardTitle>{t("dash_daily_trend")}</CardTitle>
          </CardHeader>
          <CardContent>
            {summary.isLoading ? (
              <Skeleton className="h-64 w-full" />
            ) : s && s.byDay.some((d) => d.grossCents > 0) ? (
              <AreaChart
                data={s.byDay.map((d) => ({ date: fmt.day(d.date), [sGross]: d.grossCents, [sNet]: d.netCents }))}
                index="date"
                categories={[sGross, sNet]}
                colors={["pink", "fuchsia"]}
                valueFormatter={CADShort}
                yAxisWidth={52}
                showAnimation
                className="h-64"
              />
            ) : (
              <EmptyState title={t("empty_no_sales_range")} />
            )}
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{t("dash_payment_mix")}</CardTitle>
          </CardHeader>
          <CardContent>
            {payments.isLoading ? (
              <Skeleton className="h-56 w-full" />
            ) : payments.error ? (
              <ErrorState message={payments.error.message} onRetry={() => payments.mutate()} />
            ) : payments.data && payments.data.rows.length > 0 ? (
              <div className="flex flex-col items-center gap-4 sm:flex-row">
                <DonutChart
                  data={payments.data.rows.map((r) => ({ name: t(`tender_${r.type}` as MsgKey), value: r.amountCents }))}
                  category="value"
                  index="name"
                  colors={MONO_COLORS}
                  valueFormatter={CAD}
                  label={CAD(payments.data.totalCents)}
                  showAnimation
                  className="h-48 w-48 shrink-0"
                />
                <div className="w-full space-y-2">
                  {payments.data.rows.map((r, i) => (
                    <div key={r.type} className="flex items-center gap-2 text-sm">
                      <span
                        className="h-2.5 w-2.5 shrink-0 rounded-full"
                        style={{ backgroundColor: MONO_HEX[i % MONO_HEX.length] }}
                      />
                      <span className="text-neutral-600">{t(`tender_${r.type}` as MsgKey)}</span>
                      <span className="ml-auto font-medium tabular-nums">{CAD(r.amountCents)}</span>
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <EmptyState title={t("empty_no_payments")} hint={t("empty_no_payments_hint")} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("dash_sales_by_hour")}</CardTitle>
          </CardHeader>
          <CardContent>
            {hourly.isLoading ? (
              <Skeleton className="h-56 w-full" />
            ) : hourly.error ? (
              <ErrorState message={hourly.error.message} onRetry={() => hourly.mutate()} />
            ) : hourly.data && hourly.data.rows.some((r) => r.grossCents > 0) ? (
              <BarChart
                data={hourly.data.rows.map((r) => ({ hour: hourLabel(r.hour), [sGross]: r.grossCents }))}
                index="hour"
                categories={[sGross]}
                colors={["pink"]}
                valueFormatter={CADShort}
                yAxisWidth={52}
                showLegend={false}
                showAnimation
                className="h-56"
              />
            ) : (
              <EmptyState title={t("empty_quiet")} hint={t("empty_quiet_hint")} />
            )}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle>{t("dash_top_items")}</CardTitle>
          <Link
            href={storeHref("/reports/items")}
            className="flex items-center gap-0.5 text-xs font-medium text-neutral-500 hover:text-ink"
          >
            {t("dash_all_items")} <ChevronRight className="h-3.5 w-3.5" />
          </Link>
        </CardHeader>
        <CardContent>
          {items.isLoading ? (
            <div className="space-y-2.5">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-10 w-full" />
              ))}
            </div>
          ) : items.error ? (
            <ErrorState message={items.error.message} onRetry={() => items.mutate()} />
          ) : items.data && items.data.rows.length > 0 ? (
            <div className="divide-y divide-neutral-100">
              {items.data.rows.slice(0, 5).map((r, i) => (
                <div key={r.itemId ?? `open-${i}`} className="flex items-center gap-3 py-2.5">
                  <span className="w-5 text-center text-xs font-semibold text-neutral-400">{i + 1}</span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium">{name(r.nameFr, r.nameEn)}</div>
                    {nameAlt(r.nameFr, r.nameEn) && (
                      <div className="truncate text-xs text-neutral-500">{nameAlt(r.nameFr, r.nameEn)}</div>
                    )}
                  </div>
                  <span className="text-xs text-neutral-500">×{r.qty}</span>
                  <span className="w-20 text-right text-sm font-semibold tabular-nums">
                    {CAD(r.revenueCents)}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title={t("empty_nothing_sold")} hint={t("empty_nothing_sold_hint")} />
          )}
        </CardContent>
      </Card>
    </div>
  );
}
