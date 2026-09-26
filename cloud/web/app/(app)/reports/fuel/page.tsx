"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { useMoney } from "@/lib/money";
import { FxNote, useScopedKpi } from "@/components/money-scope";
import { useI18n, useT, useFmt } from "@/lib/i18n/context";
import type { Locale, MsgKey } from "@/lib/i18n/messages";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, T, type ExportDoc } from "@/lib/export/doc";
import type { FuelGradeRow, FuelReport } from "@/lib/types";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Kpi } from "@/components/kpi";
import { PageHeader } from "@/components/page-header";
import { StoreSplit } from "@/components/store-breakdown";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <FuelPage />
    </Suspense>
  );
}

const GRADES = new Set(["REG", "MID", "PRE", "DSL"]);

const NUMBER_LOCALE: Record<Locale, string> = { en: "en-US", fr: "fr-CA", es: "es-US" };

/** Thousandths of a gallon → "10.052" (always three decimals, the pump's precision). */
function useGallons() {
  const { locale } = useI18n();
  const nf = new Intl.NumberFormat(NUMBER_LOCALE[locale], { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  return (milli: number) => nf.format(milli / 1000);
}

/**
 * A gas station's day: fuel by grade (gallons and what the pumps dispensed)
 * beside the shop's own sales. Shown in Reports when the client's brand pack
 * turns the fuel feature on.
 */
function FuelPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const gal = useGallons();
  const { data, error, isLoading, mutate } = useApi<FuelReport>(reportKey("/v1/reports/fuel", range));
  const m = useMoney();
  const kpi = useScopedKpi();
  const money = data?.money;
  type V = FuelReport["byVenue"][number];
  const cur = (r: V) => r.currency ?? m.currencyOf(r.venueId);
  const scoped = (cents: number, f: (r: V) => number) => kpi(money, cents, m.perCurrency(data?.byVenue ?? [], cur, f));
  const fuelAmount = data ? scoped(data.fuel.amountCents, (r) => r.fuelAmountCents) : undefined;
  const inStore = data ? scoped(data.inStore.salesCents, (r) => r.inStoreSalesCents) : undefined;
  const gradeCur = (r: FuelGradeRow) => r.currency ?? data?.currency;
  // the four standard grades in the reader's language; any other, as the store named it
  const gradeName = (r: FuelGradeRow) => (GRADES.has(r.grade) ? t(`fuel_grade_${r.grade}` as MsgKey) : r.gradeName);
  // grades in more than one currency can't share one total row
  const oneCurrency = new Set((data?.byGrade ?? []).map(gradeCur)).size <= 1;
  const both = (k?: { value: string; sub?: string }) => [k?.value, k?.sub].filter(Boolean).join(" — ");
  const prepayNote =
    data && data.fuel.prepayCount > 0
      ? t("fuel_prepay", {
          n: data.fuel.prepayCount,
          paid: m.fmtScope(money, data.fuel.prepaidCents),
          back: m.fmtScope(money, data.fuel.prepayRefundCents),
        })
      : null;

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("fuel"),
      reportTitle: t("fuel_title"),
      notes: [t("fuel_note"), ...(prepayNote ? [prepayNote] : [])],
      kpis: [
        { label: t("fuel_gallons"), value: gal(data.fuel.volumeMilli) },
        { label: t("fuel_sales"), value: both(fuelAmount) },
        { label: t("fuel_fills"), value: String(data.fuel.count) },
        { label: t("fuel_in_store"), value: both(inStore) },
      ],
      sections: [
        {
          title: t("fuel_by_grade"),
          columns: [
            col.text<FuelGradeRow>(t("col_grade"), gradeName),
            col.text<FuelGradeRow>(t("fuel_gallons"), (r) => gal(r.volumeMilli)),
            col.int<FuelGradeRow>(t("fuel_fills"), (r) => r.count),
            col.money<FuelGradeRow>(t("col_amount"), (r) => r.amountCents),
          ],
          rows: data.byGrade,
          ...(oneCurrency
            ? {
                total: [
                  T(t("fuel_total")),
                  T(gal(data.fuel.volumeMilli)),
                  Int(data.fuel.count),
                  m.totalCell(data.byGrade, gradeCur, (r) => r.amountCents),
                ],
              }
            : {}),
        },
        ...storeExport.byStore<V>(
          [
            col.text(t("fuel_gallons"), (r) => gal(r.fuelVolumeMilli)),
            col.money(t("fuel_sales"), (r) => r.fuelAmountCents),
            col.money(t("fuel_in_store"), (r) => r.inStoreSalesCents),
          ],
          data.byVenue,
          [
            T(t("col_total")),
            T(gal(data.fuel.volumeMilli)),
            m.totalCell(data.byVenue, cur, (r) => r.fuelAmountCents),
            m.totalCell(data.byVenue, cur, (r) => r.inStoreSalesCents),
          ]
        ),
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("fuel_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || (data.fuel.count === 0 && data.inStore.lineCount === 0)} />}
      />
      <DateRangePicker />
      <FxNote money={money} />
      {data && (
        <StoreSplit
          rows={data.byVenue}
          title={t("fuel_by_store")}
          chartKey="fuel"
          cols={[
            { key: "gallons", label: t("fuel_gallons"), value: (r) => r.fuelVolumeMilli, format: gal },
            { key: "fuel", label: t("fuel_sales"), value: (r) => r.fuelAmountCents, money: true, strong: true },
            { key: "shop", label: t("fuel_in_store"), value: (r) => r.inStoreSalesCents, money: true, hide: "sm" },
          ]}
        />
      )}

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Kpi label={t("fuel_gallons")} value={data && gal(data.fuel.volumeMilli)} loading={isLoading} />
        <Kpi label={t("fuel_sales")} value={fuelAmount?.value} sub={fuelAmount?.sub} loading={isLoading} accent />
        <Kpi label={t("fuel_fills")} value={data && String(data.fuel.count)} loading={isLoading} />
        <Kpi
          label={t("fuel_in_store")}
          value={inStore?.value}
          sub={inStore?.sub ?? (data ? t("fuel_in_store_sub", { n: data.inStore.checkCount }) : undefined)}
          loading={isLoading}
        />
      </div>

      <p className="px-1 text-xs text-neutral-500">{t("fuel_note")}</p>

      {isLoading ? (
        <Card>
          <TableSkeleton rows={4} />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : data && data.byGrade.length > 0 ? (
        <Card>
          <div className="border-b border-neutral-100 px-5 py-3 text-sm font-semibold">{t("fuel_by_grade")}</div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_grade")}</TableHead>
                <TableHead className="text-right">{t("fuel_gallons")}</TableHead>
                <TableHead className="text-right">{t("fuel_fills")}</TableHead>
                <TableHead className="text-right">{t("col_amount")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.byGrade.map((r) => (
                <TableRow key={`${r.grade}/${r.currency ?? ""}`}>
                  <TableCell className="font-medium">{gradeName(r)}</TableCell>
                  <TableCell className="text-right tabular-nums">{gal(r.volumeMilli)}</TableCell>
                  <TableCell className="text-right tabular-nums">{r.count}</TableCell>
                  <TableCell className="text-right font-medium tabular-nums">{m.fmtIn(gradeCur(r), r.amountCents)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell className="font-semibold">{t("fuel_total")}</TableCell>
                <TableCell className="text-right font-semibold tabular-nums">{gal(data.fuel.volumeMilli)}</TableCell>
                <TableCell className="text-right font-semibold tabular-nums">{data.fuel.count}</TableCell>
                <TableCell className="text-right font-semibold tabular-nums">
                  {oneCurrency
                    ? m.fmtIn(gradeCur(data.byGrade[0]), data.fuel.amountCents)
                    : m.joinAmounts(m.perCurrency(data.byGrade, gradeCur, (r) => r.amountCents))}
                </TableCell>
              </TableRow>
            </TableFooter>
          </Table>
          {prepayNote && <div className="border-t border-neutral-100 px-5 py-3 text-xs text-neutral-500">{prepayNote}</div>}
        </Card>
      ) : (
        <Card>
          <EmptyState title={t("fuel_empty")} hint={t("fuel_empty_hint")} />
        </Card>
      )}
    </div>
  );
}
