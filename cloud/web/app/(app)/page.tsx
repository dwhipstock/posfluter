"use client";

import { Suspense } from "react";
import Link from "next/link";
import { ChevronRight, Wifi, WifiOff } from "lucide-react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { hourLabel } from "@/lib/format";
import { cashRoundingAmounts, cashRoundingKpis, useMoney } from "@/lib/money";
import { ByCurrencyCard, CashRoundingNote, FxNote, RetailBadge, useScopedKpi } from "@/components/money-scope";
import { useI18n, useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import type {
  ByVenueReport,
  DayRow,
  HourlyReport,
  ItemsReport,
  PaymentsReport,
  StoreLinkStatus,
  StorePosResponse,
  Summary,
  VenueSummaryRow,
} from "@/lib/types";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import { todayISO } from "@/lib/range";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Kpi } from "@/components/kpi";
import { PageHeader } from "@/components/page-header";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback } from "@/components/states";
import { BarChart, Donut, LineChart } from "@/components/charts";
import { StoreBreakdown, useStoreSeries } from "@/components/store-breakdown";
import { useStoreHref, useStores } from "@/lib/store";
import { useBrand, useChartTheme } from "@/lib/brand/context";
import { FuelDashboardKpis, InStoreCategoryMix } from "@/components/fuel-kpis";
import { cn } from "@/lib/utils";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <Dashboard />
    </Suspense>
  );
}

function Dashboard() {
  const SERIES = useChartTheme().series;
  const t = useT();
  const fmt = useFmt();
  const { name, nameAlt } = useI18n();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const storeHref = useStoreHref();
  const { combined, nameOf, colorOf } = useStores();
  const m = useMoney();
  const kpi = useScopedKpi();
  // a gas station leads with its shop and its pumps (brand pack features.fuel)
  const fuel = useBrand().features.fuel;
  const multiDay = range.from !== range.to;
  const today = todayISO();
  const isToday = range.from === today && range.to === today;
  const summary = useApi<Summary>(reportKey("/v1/reports/summary", range));
  const payments = useApi<PaymentsReport>(reportKey("/v1/reports/payments", range));
  const hourly = useApi<HourlyReport>(reportKey("/v1/reports/hourly", range));
  const items = useApi<ItemsReport>(reportKey("/v1/reports/items", range));

  // a gas station's fuel has its own figures: top items are the shop's
  const topItems = (items.data?.rows ?? []).filter((r) => !fuel || r.categoryId !== "fuel");
  const s = summary.data;
  const money = s?.money;
  // a combined figure + (mixed currencies) its exact amounts per currency
  const exact = (pick: (r: NonNullable<Summary["byCurrency"]>[number]) => number) =>
    (s?.byCurrency ?? []).map((r) => ({ currency: r.currency, cents: pick(r) }));
  const k = {
    gross: s ? kpi(money, s.grossCents, exact((r) => r.grossCents)) : undefined,
    net: s ? kpi(money, s.netCents, exact((r) => r.netCents)) : undefined,
    tax: s ? kpi(money, s.taxCents, exact((r) => r.taxCents)) : undefined,
    avg: s ? kpi(money, s.avgCheckCents, exact((r) => r.avgCheckCents)) : undefined,
  };
  // exact net cash rounding: one figure, or one per currency (never converted)
  const rounding = s ? cashRoundingAmounts(s.cashRoundingCents, s.byCurrency, money?.currency ?? m.scopeCurrency) : [];
  const fmtC = (n: number) => m.fmtScope(money, n);
  const axisC = (n: number) => m.shortScope(money, n);
  const chartNote = money?.approximate
    ? ` · ${t("fx_chart_converted", { cur: money.reportingCurrency, rates: m.rateText(money) })}`
    : "";
  const sGross = t("series_gross");
  const storeSeries = useStoreSeries(sGross);

  const buildDoc = (): ExportDoc | null => {
    if (!s) return null;
    // "All stores": each day per store (store column), plus the store totals
    const dayRows: (DayRow & { venueId: string })[] = storeExport.combined
      ? s.byDay.flatMap((d) => d.byVenue.map((v) => ({ ...d, ...v, date: d.date, byVenue: [] })))
      : s.byDay.map((d) => ({ ...d, venueId: "" }));
    return {
      ...meta("summary"),
      reportTitle: t("dash_title"),
      ...(rounding.length > 0 ? { notes: [t("cash_rounding_note")] } : {}),
      kpis: [
        { label: t("kpi_gross"), value: fmtC(s.grossCents) },
        { label: t("kpi_net"), value: fmtC(s.netCents) },
        { label: t("kpi_tax"), value: fmtC(s.taxCents) },
        { label: t("kpi_checks"), value: String(s.checkCount) },
        { label: t("kpi_avg_check"), value: fmtC(s.avgCheckCents) },
        { label: t("exc_voids"), value: String(s.voidCount) },
        { label: t("exc_void_amount"), value: fmtC(s.voidAmountCents) },
        { label: t("ref_count"), value: String(s.refundCount) },
        { label: t("ref_amount"), value: fmtC(s.refundAmountCents) },
        { label: t("exc_corkage"), value: fmtC(s.corkageCents) },
        { label: t("svc_charge"), value: fmtC(s.serviceChargeCents) },
        // exact, one per currency, when the stores sell in several
        ...(money?.approximate
          ? (s.byCurrency ?? []).map((r) => ({
              label: `${t("kpi_gross")} · ${r.currency}`,
              value: m.fmtIn(r.currency, r.grossCents),
            }))
          : []),
        ...cashRoundingKpis(t("cash_rounding"), rounding, m.signedIn),
      ],
      sections: [
        ...storeExport.byStore<VenueSummaryRow>(
          [
            col.money(t("col_gross"), (r) => r.grossCents),
            col.money(t("col_net"), (r) => r.netCents),
            col.money(t("col_tax"), (r) => r.taxCents),
            col.int(t("col_checks"), (r) => r.checkCount),
            col.money(t("kpi_avg_check"), (r) => r.avgCheckCents),
            // each store in its own currency; the total is exact per currency
            ...(rounding.length > 0 ? [col.money<VenueSummaryRow>(t("cash_rounding"), (r) => r.cashRoundingCents ?? 0)] : []),
          ],
          s.byVenue,
          [
            T(t("col_total")),
            Money(s.grossCents),
            Money(s.netCents),
            Money(s.taxCents),
            Int(s.checkCount),
            Money(s.avgCheckCents),
            ...(rounding.length > 0
              ? [m.totalCell(s.byVenue, (r) => r.currency ?? m.currencyOf(r.venueId), (r) => r.cashRoundingCents ?? 0)]
              : []),
          ]
        ),
        {
          title: t("summary_by_day"),
          columns: [
            col.text<DayRow & { venueId: string }>(t("col_date"), (r) => fmt.dayYear(r.date)),
            ...storeExport.withStore<DayRow & { venueId: string }>([
              col.money(t("col_gross"), (r) => r.grossCents),
              col.money(t("col_net"), (r) => r.netCents),
              col.money(t("col_tax"), (r) => r.taxCents),
              col.int(t("col_checks"), (r) => r.checkCount),
            ]),
          ],
          rows: dayRows,
          total: [
            T(t("col_total")),
            ...(storeExport.combined ? [T("")] : []),
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
      <FxNote money={money} />

      {fuel && <FuelDashboardKpis range={range} />}

      {combined && <StoreCompare />}

      {summary.error ? (
        <Card>
          <ErrorState message={summary.error.message} onRetry={() => summary.mutate()} />
        </Card>
      ) : (
        <div>
          {combined && (
            <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-neutral-500">
              {t("dash_combined_range", { range: fmt.rangeLabel(range) })}
            </h2>
          )}
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5">
            <Kpi label={t("kpi_gross")} value={k.gross?.value} sub={k.gross?.sub} accent loading={summary.isLoading} />
            <Kpi label={t("kpi_net")} value={k.net?.value} sub={k.net?.sub} loading={summary.isLoading} />
            <Kpi label={t("kpi_tax")} value={k.tax?.value} sub={k.tax?.sub} loading={summary.isLoading} />
            <Kpi label={t("kpi_checks")} value={s && String(s.checkCount)} loading={summary.isLoading} />
            <Kpi
              label={t("kpi_avg_check")}
              value={k.avg?.value}
              sub={k.avg?.sub}
              loading={summary.isLoading}
              className="col-span-2 md:col-span-1"
            />
          </div>
          <CashRoundingNote amounts={rounding} className="mt-3" />
          {s && (
            <div className="mt-3">
              <ByCurrencyCard rows={s.byCurrency} money={money} />
            </div>
          )}
        </div>
      )}

      {s && !isToday && <StoreBreakdown rows={s.byVenue} chart />}

      {multiDay && (
        <Card>
          <CardHeader>
            <CardTitle>{t("dash_daily_trend")}</CardTitle>
            {combined && <CardDescription>{t("chart_per_store_gross")}{chartNote}</CardDescription>}
          </CardHeader>
          <CardContent>
            {summary.isLoading ? (
              <Skeleton className="h-64 w-full" />
            ) : s && s.byDay.some((d) => d.grossCents > 0) ? (
              <LineChart
                data={s.byDay.map((d) => ({
                  label: fmt.day(d.date),
                  values: combined
                    ? Object.fromEntries(d.byVenue.map((v) => [v.venueId, m.chartValue(v.venueId, v.grossCents)]))
                    : { value: d.grossCents, net: d.netCents },
                }))}
                series={
                  combined
                    ? storeSeries
                    : [...storeSeries, { key: "net", label: t("series_net"), color: storeSeries[0].color === SERIES[1] ? SERIES[0] : SERIES[1] }]
                }
                format={fmtC}
                axisFormat={axisC}
                ariaLabel={t("dash_daily_trend")}
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
            {combined && <CardDescription>{t("chart_per_store_stacked")}{chartNote}</CardDescription>}
          </CardHeader>
          <CardContent>
            {payments.isLoading ? (
              <Skeleton className="h-56 w-full" />
            ) : payments.error ? (
              <ErrorState message={payments.error.message} onRetry={() => payments.mutate()} />
            ) : payments.data && payments.data.rows.length > 0 ? (
              combined ? (
                <BarChart
                  data={payments.data.rows.map((r) => ({
                    label: t(`tender_${r.type}` as MsgKey),
                    values: Object.fromEntries(
                      payments.data!.byVenue.map((v) => [
                        v.venueId,
                        m.chartValue(v.venueId, v.rows.find((x) => x.type === r.type)?.amountCents ?? 0),
                      ])
                    ),
                  }))}
                  series={storeSeries}
                  format={fmtC}
                  axisFormat={axisC}
                  ariaLabel={t("dash_payment_mix")}
                />
              ) : (
                <TenderDonut report={payments.data} />
              )
            ) : (
              <EmptyState title={t("empty_no_payments")} hint={t("empty_no_payments_hint")} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("dash_sales_by_hour")}</CardTitle>
            {combined && <CardDescription>{t("chart_per_store_stacked")}{chartNote}</CardDescription>}
          </CardHeader>
          <CardContent>
            {hourly.isLoading ? (
              <Skeleton className="h-56 w-full" />
            ) : hourly.error ? (
              <ErrorState message={hourly.error.message} onRetry={() => hourly.mutate()} />
            ) : hourly.data && hourly.data.rows.some((r) => r.grossCents > 0) ? (
              <BarChart
                data={hourly.data.rows.map((r) => ({
                  label: hourLabel(r.hour),
                  values: combined
                    ? Object.fromEntries(r.byVenue.map((v) => [v.venueId, m.chartValue(v.venueId, v.grossCents)]))
                    : { value: r.grossCents },
                }))}
                series={storeSeries}
                format={fmtC}
                axisFormat={axisC}
                ariaLabel={t("dash_sales_by_hour")}
              />
            ) : (
              <EmptyState title={t("empty_quiet")} hint={t("empty_quiet_hint")} />
            )}
          </CardContent>
        </Card>
      </div>

      {fuel && <InStoreCategoryMix range={range} />}

      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle>{t("dash_top_items")}</CardTitle>
          <Link
            href={storeHref("/reports/items")}
            className="flex items-center gap-0.5 text-xs font-medium text-navy hover:text-navy-deep"
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
          ) : topItems.length > 0 ? (
            <div className="divide-y divide-neutral-200/70">
              {topItems.slice(0, 5).map((r, i) => (
                <div key={`${r.itemId ?? `open-${i}`}-${r.currency ?? ""}`} className="flex items-center gap-3 py-2.5">
                  <span className="w-5 text-center text-xs font-semibold text-neutral-500">{i + 1}</span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium">{name(r.nameFr, r.nameEn)}</div>
                    {combined ? (
                      <div className="mt-1 flex flex-wrap gap-x-3 text-xs text-neutral-500">
                        {r.byVenue.map((v) => (
                          <span key={v.venueId} className="inline-flex items-center gap-1">
                            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: colorOf(v.venueId) }} />
                            {nameOf(v.venueId)} <span className="tabular-nums text-neutral-600">{m.fmtVenue(v.venueId, v.revenueCents)}</span>
                          </span>
                        ))}
                      </div>
                    ) : (
                      nameAlt(r.nameFr, r.nameEn) && (
                        <div className="truncate text-xs text-neutral-500">{nameAlt(r.nameFr, r.nameEn)}</div>
                      )
                    )}
                  </div>
                  <span className="text-xs text-neutral-500">×{r.qty}</span>
                  <span className="w-24 text-right text-sm font-semibold tabular-nums">{m.fmtIn(r.currency, r.revenueCents)}</span>
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

/** The single-store tender mix: a ring plus a labelled legend (never colour alone). */
function TenderDonut({ report }: { report: PaymentsReport }) {
  const SERIES = useChartTheme().series;
  const t = useT();
  const m = useMoney();
  const fmtC = (n: number) => m.fmtScope(report.money, n);
  const items = report.rows.map((r, i) => ({
    key: r.type,
    label: t(`tender_${r.type}` as MsgKey),
    value: r.amountCents,
    color: SERIES[i % SERIES.length],
  }));
  return (
    <div className="flex flex-col items-center gap-5 sm:flex-row">
      <Donut items={items} format={fmtC} center={fmtC(report.totalCents)} ariaLabel={t("dash_payment_mix")} />
      <div className="w-full space-y-2">
        {items.map((r) => (
          <div key={r.key} className="flex items-center gap-2 text-sm">
            <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ backgroundColor: r.color }} />
            <span className="text-neutral-600">{r.label}</span>
            <span className="ml-auto font-medium tabular-nums">{fmtC(r.value)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

const STATUS_KEY: Record<StoreLinkStatus, MsgKey> = {
  online: "devices_pos_online",
  stale: "devices_pos_stale",
  offline: "devices_pos_offline",
};

/**
 * "All stores": one card per store with TODAY's sales, checks and average
 * check (each store's own business day) and whether its POS tablet is online,
 * plus the combined card. Independent of the range picker below it.
 */
function StoreCompare() {
  const t = useT();
  const { venues, nameOf, colorOf } = useStores();
  const m = useMoney();
  const kpi = useScopedKpi();
  const today = todayISO();
  const todayRange = { from: today, to: today };
  const byVenue = useApi<ByVenueReport>(reportKey("/v1/reports/by-venue", todayRange), { refreshInterval: 60_000 });
  const pos = useApi<StorePosResponse>("/v1/devices", { refreshInterval: 30_000 });
  const statusOf = (id: string) => pos.data?.stores.find((s) => s.venueId === id)?.status;
  const rows = byVenue.data?.venues ?? [];
  const checks = rows.reduce((n, r) => n + r.checkCount, 0);
  // combined across currencies: the API's converted figure (≈), exact per currency below it
  const money = byVenue.data?.money;
  const gross = byVenue.data?.grossCents ?? 0;
  const combinedGross = kpi(money, gross, m.perCurrency(rows, (r) => r.currency, (r) => r.grossCents));
  const avg = checks ? Math.round(money?.approximate ? gross / checks : rows.reduce((n, r) => n + r.avgCheckCents * r.checkCount, 0) / checks) : 0;
  const online = venues.filter((v) => statusOf(v.id) === "online").length;

  return (
    <section aria-labelledby="store-compare">
      <h2 id="store-compare" className="mb-2 text-xs font-semibold uppercase tracking-wider text-neutral-500">
        {t("dash_today_by_store")}
      </h2>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {venues.map((v) => {
          const r = rows.find((x) => x.venueId === v.id);
          const status = statusOf(v.id);
          return (
            <Card key={v.id} className="relative overflow-hidden p-4" data-testid="store-card">
              <span className="absolute inset-y-0 left-0 w-1" style={{ backgroundColor: colorOf(v.id) }} aria-hidden />
              {/* long store names wrap instead of truncating */}
              <div className="flex items-start gap-2">
                <span className="min-w-0 break-words text-sm font-semibold leading-snug text-navy" data-testid="store-card-name">{nameOf(v.id)}</span>
                <RetailBadge venueId={v.id} />
                {status && <StatusPill status={status} label={t(STATUS_KEY[status])} />}
              </div>
              {byVenue.isLoading && !r ? (
                <Skeleton className="mt-3 h-12 w-full" />
              ) : (
                <div className="mt-3 grid grid-cols-3 gap-2">
                  <Mini label={t("kpi_gross")} value={m.fmtVenue(v.id, r?.grossCents ?? 0)} strong />
                  <Mini label={t("kpi_checks")} value={String(r?.checkCount ?? 0)} />
                  <Mini label={t("kpi_avg_check")} value={m.fmtVenue(v.id, r?.avgCheckCents ?? 0)} />
                </div>
              )}
            </Card>
          );
        })}
        <Card className="bg-navy p-4 text-white" data-testid="store-card-combined">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold">{t("dash_combined_today")}</span>
            {pos.data && (
              <span className="ml-auto text-[11px] font-medium text-navy-muted">
                {t("dash_online_n", { n: online, total: venues.length })}
              </span>
            )}
          </div>
          {/* the converted gross ("≈ CA$1,234.56") needs the wider first column */}
          <div className="mt-3 grid grid-cols-[1.6fr_1fr_1.2fr] gap-2">
            <Mini label={t("kpi_gross")} value={combinedGross.value} strong dark />
            <Mini label={t("kpi_checks")} value={String(checks)} dark />
            <Mini label={t("kpi_avg_check")} value={m.fmtScope(money, avg)} dark />
          </div>
          {combinedGross.sub && (
            <div className="mt-2 truncate text-[11px] tabular-nums text-navy-muted" data-testid="combined-by-currency">
              {combinedGross.sub}
            </div>
          )}
        </Card>
      </div>
    </section>
  );
}

function StatusPill({ status, label }: { status: StoreLinkStatus; label: string }) {
  const Icon = status === "offline" ? WifiOff : Wifi;
  return (
    <span
      className={cn(
        "ml-auto inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold",
        status === "online" && "bg-emerald-50 text-emerald-700",
        status === "stale" && "bg-amber-50 text-attention",
        status === "offline" && "bg-red-50 text-red-700"
      )}
    >
      <Icon className="h-3 w-3" />
      {label}
    </span>
  );
}

function Mini({ label, value, strong = false, dark = false }: { label: string; value: string; strong?: boolean; dark?: boolean }) {
  return (
    <div className="min-w-0">
      <div className={cn("truncate text-[10px] font-medium uppercase tracking-wider", dark ? "text-navy-muted" : "text-neutral-500")}>
        {label}
      </div>
      <div className={cn("mt-0.5 truncate tabular-nums", strong ? "text-lg font-semibold" : "text-sm font-medium")}>{value}</div>
    </div>
  );
}
