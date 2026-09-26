"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { useMoney } from "@/lib/money";
import { FxNote } from "@/components/money-scope";
import { useFuelKpis } from "@/components/fuel-kpis";
import { useCategoryName, useFuelFmt } from "@/lib/fuel";
import { useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, T, type ExportDoc } from "@/lib/export/doc";
import type { FuelGradeRow, FuelReport, InStoreCategoryRow } from "@/lib/types";
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

function SectionTitle({ children }: { children: React.ReactNode }) {
  return <h2 className="px-1 pt-2 text-xs font-semibold uppercase tracking-wider text-neutral-500">{children}</h2>;
}

/**
 * A gas station's day: the shop first (net sales, cost, margin — where the
 * money is made), then the pumps (gallons, revenue, cost, margin in cents per
 * gallon, by grade). Margins cover only what the store sent a cost for; the
 * rest is counted, never guessed. Shown when the brand pack turns the fuel
 * feature on.
 */
function FuelPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const f = useFuelFmt();
  const { data, error, isLoading, mutate } = useApi<FuelReport>(reportKey("/v1/reports/fuel", range));
  const m = useMoney();
  const money = data?.money;
  const scoped = useFuelKpis(data);
  type V = FuelReport["byVenue"][number];
  const cur = (r: V) => r.currency ?? m.currencyOf(r.venueId);

  const shop = data?.inStore;
  const pumps = data?.fuel;
  const shopSales = scoped(shop?.salesCents, (r) => r.inStoreSalesCents);
  const shopCost = shop?.costCents != null ? m.fmtScope(money, shop.costCents) : undefined;
  const shopMargin = shop?.marginCents != null ? m.fmtScope(money, shop.marginCents) : undefined;
  const shopCosted = (shop?.costedLineCount ?? 0) > 0;
  const fuelAmount = scoped(pumps?.amountCents, (r) => r.fuelAmountCents);
  const fuelCosted = (pumps?.costedCount ?? 0) > 0;
  const fuelMargin = pumps?.marginCents != null ? m.fmtScope(money, pumps.marginCents) : undefined;

  const gradeCur = (r: FuelGradeRow) => r.currency ?? data?.currency;
  const catCur = (r: InStoreCategoryRow) => r.currency ?? data?.currency;
  // the four standard grades in the reader's language; any other, as the store named it
  const gradeName = (r: FuelGradeRow) => (GRADES.has(r.grade) ? t(`fuel_grade_${r.grade}` as MsgKey) : r.gradeName);
  const catName = useCategoryName();
  const oneCurrency = new Set((data?.byGrade ?? []).map(gradeCur)).size <= 1;
  const costed = (r: FuelGradeRow) => (r.costedCount ?? 0) > 0;
  const both = (k?: { value: string; sub?: string }) => [k?.value, k?.sub].filter(Boolean).join(" — ");
  // every category, best margin first: a thin-margin one (tobacco) is part of the picture too
  const categories = data?.inStoreByCategory ?? [];
  const uncostedLines = shop?.uncostedLineCount ?? 0;
  const uncostedFills = pumps?.uncostedCount ?? 0;
  const prepayNote =
    pumps && pumps.prepayCount > 0
      ? t("fuel_prepay", {
          n: pumps.prepayCount,
          paid: m.fmtScope(money, pumps.prepaidCents),
          back: m.fmtScope(money, pumps.prepayRefundCents),
        })
      : null;
  const notes = [
    ...(uncostedLines > 0 ? [t("margin_uncosted_lines", { n: uncostedLines })] : []),
    ...(uncostedFills > 0 ? [t("margin_uncosted_fills", { n: uncostedFills })] : []),
  ];

  const buildDoc = (): ExportDoc | null => {
    if (!data || !shop || !pumps) return null;
    return {
      ...meta("fuel"),
      reportTitle: t("fuel_title"),
      notes: [t("fuel_note"), ...notes, ...(prepayNote ? [prepayNote] : [])],
      kpis: [
        { label: t("fuel_in_store"), value: both(shopSales) },
        { label: t("col_cost"), value: shopCost ?? "—" },
        { label: t("instore_margin"), value: shopCosted ? `${shopMargin} · ${f.pct(shop.marginBasisPoints)}` : "—" },
        { label: t("fuel_gallons"), value: f.gal(pumps.volumeMilli) },
        { label: t("fuel_sales"), value: both(fuelAmount) },
        { label: t("fuel_margin"), value: fuelCosted ? `${fuelMargin} · ${f.perGallon(pumps.marginMillsPerGallon)}` : "—" },
      ],
      sections: [
        {
          title: t("instore_categories"),
          columns: [
            col.text<InStoreCategoryRow>(t("col_category"), catName),
            col.money<InStoreCategoryRow>(t("fuel_in_store"), (r) => r.salesCents),
            col.money<InStoreCategoryRow>(t("col_cost"), (r) => r.costCents),
            col.money<InStoreCategoryRow>(t("col_margin"), (r) => r.marginCents),
            col.text<InStoreCategoryRow>(t("col_margin_pct"), (r) => f.pct(r.marginBasisPoints)),
          ],
          rows: data.inStoreByCategory ?? [],
        },
        {
          title: t("fuel_by_grade"),
          columns: [
            col.text<FuelGradeRow>(t("col_grade"), gradeName),
            col.text<FuelGradeRow>(t("fuel_gallons"), (r) => f.gal(r.volumeMilli)),
            col.int<FuelGradeRow>(t("fuel_fills"), (r) => r.count),
            col.money<FuelGradeRow>(t("col_amount"), (r) => r.amountCents),
            col.money<FuelGradeRow>(t("col_cost"), (r) => r.costCents ?? 0),
            col.money<FuelGradeRow>(t("col_margin"), (r) => r.marginCents ?? 0),
            col.text<FuelGradeRow>(t("col_per_gal"), (r) => f.perGallon(r.marginMillsPerGallon)),
          ],
          rows: data.byGrade,
          ...(oneCurrency
            ? {
                total: [
                  T(t("fuel_total")),
                  T(f.gal(pumps.volumeMilli)),
                  Int(pumps.count),
                  m.totalCell(data.byGrade, gradeCur, (r) => r.amountCents),
                  m.totalCell(data.byGrade, gradeCur, (r) => r.costCents ?? 0),
                  m.totalCell(data.byGrade, gradeCur, (r) => r.marginCents ?? 0),
                  T(f.perGallon(pumps.marginMillsPerGallon)),
                ],
              }
            : {}),
        },
        ...storeExport.byStore<V>(
          [
            col.money(t("fuel_in_store"), (r) => r.inStoreSalesCents),
            col.money(t("instore_margin"), (r) => r.inStoreMarginCents ?? 0),
            col.text(t("fuel_gallons"), (r) => f.gal(r.fuelVolumeMilli)),
            col.money(t("fuel_sales"), (r) => r.fuelAmountCents),
            col.money(t("fuel_margin"), (r) => r.fuelMarginCents ?? 0),
          ],
          data.byVenue,
          [
            T(t("col_total")),
            m.totalCell(data.byVenue, cur, (r) => r.inStoreSalesCents),
            m.totalCell(data.byVenue, cur, (r) => r.inStoreMarginCents ?? 0),
            T(f.gal(pumps.volumeMilli)),
            m.totalCell(data.byVenue, cur, (r) => r.fuelAmountCents),
            m.totalCell(data.byVenue, cur, (r) => r.fuelMarginCents ?? 0),
          ]
        ),
      ],
    };
  };

  const loadingCard = (
    <Card>
      <TableSkeleton rows={4} />
    </Card>
  );

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
          chartKey="shop"
          cols={[
            { key: "shop", label: t("fuel_in_store"), value: (r) => r.inStoreSalesCents, money: true, strong: true },
            { key: "shopMargin", label: t("instore_margin"), value: (r) => r.inStoreMarginCents ?? 0, money: true, hide: "sm" },
            { key: "gallons", label: t("fuel_gallons"), value: (r) => r.fuelVolumeMilli, format: f.gal },
            { key: "fuelMargin", label: t("fuel_margin"), value: (r) => r.fuelMarginCents ?? 0, money: true, hide: "sm" },
          ]}
        />
      )}

      {error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : (
        <>
          {/* ── the shop ─────────────────────────────────────────────── */}
          <SectionTitle>{t("instore_section")}</SectionTitle>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Kpi
              label={t("fuel_in_store")}
              value={shopSales?.value}
              sub={shopSales?.sub ?? (shop ? t("fuel_in_store_sub", { n: shop.checkCount }) : undefined)}
              loading={isLoading}
              accent
            />
            <Kpi
              label={t("col_cost")}
              value={shopCosted ? shopCost : shop ? "—" : undefined}
              sub={shop && (shop.discountCents ?? 0) > 0 ? t("instore_promotions", { amount: m.fmtScope(money, shop.discountCents ?? 0) }) : undefined}
              loading={isLoading}
            />
            <Kpi label={t("col_margin")} value={shopCosted ? shopMargin : shop ? "—" : undefined} loading={isLoading} />
            <Kpi label={t("col_margin_pct")} value={shop ? f.pct(shop.marginBasisPoints) : undefined} loading={isLoading} />
          </div>

          {isLoading ? (
            loadingCard
          ) : categories.length > 0 ? (
            <Card>
              <div className="border-b border-neutral-100 px-5 py-3 text-sm font-semibold">{t("instore_categories")}</div>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("col_category")}</TableHead>
                    <TableHead className="text-right">{t("fuel_in_store")}</TableHead>
                    <TableHead className="hidden text-right sm:table-cell">{t("col_cost")}</TableHead>
                    <TableHead className="text-right">{t("col_margin")}</TableHead>
                    <TableHead className="text-right">{t("col_margin_pct")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {categories.map((r) => (
                    <TableRow key={`${r.categoryId ?? ""}/${r.currency ?? ""}`}>
                      <TableCell className="font-medium">{catName(r)}</TableCell>
                      <TableCell className="text-right tabular-nums">{m.fmtIn(catCur(r), r.salesCents)}</TableCell>
                      <TableCell className="hidden text-right tabular-nums sm:table-cell">
                        {r.costedSalesCents > 0 ? m.fmtIn(catCur(r), r.costCents) : "—"}
                      </TableCell>
                      <TableCell className="text-right font-medium tabular-nums">
                        {r.costedSalesCents > 0 ? m.fmtIn(catCur(r), r.marginCents) : "—"}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{f.pct(r.marginBasisPoints)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {uncostedLines > 0 && (
                <div className="border-t border-neutral-100 px-5 py-3 text-xs text-neutral-500">
                  {t("margin_uncosted_lines", { n: uncostedLines })}
                </div>
              )}
            </Card>
          ) : (
            <Card>
              <EmptyState title={t("instore_empty")} />
            </Card>
          )}

          {/* ── the pumps ────────────────────────────────────────────── */}
          <SectionTitle>{t("fuel_section")}</SectionTitle>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Kpi label={t("fuel_gallons")} value={pumps && f.gal(pumps.volumeMilli)} loading={isLoading} />
            <Kpi
              label={t("fuel_sales")}
              value={fuelAmount?.value}
              sub={fuelAmount?.sub ?? (pumps ? `${pumps.count} ${t("fuel_fills").toLowerCase()}` : undefined)}
              loading={isLoading}
            />
            <Kpi label={t("col_margin")} value={fuelCosted ? fuelMargin : pumps ? "—" : undefined} loading={isLoading} />
            <Kpi label={t("col_per_gal")} value={pumps ? f.perGallon(pumps.marginMillsPerGallon) : undefined} loading={isLoading} />
          </div>

          {isLoading ? (
            loadingCard
          ) : data && data.byGrade.length > 0 ? (
            <Card>
              <div className="border-b border-neutral-100 px-5 py-3 text-sm font-semibold">{t("fuel_by_grade")}</div>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("col_grade")}</TableHead>
                    <TableHead className="text-right">{t("fuel_gallons")}</TableHead>
                    <TableHead className="hidden text-right md:table-cell">{t("fuel_fills")}</TableHead>
                    <TableHead className="text-right">{t("col_amount")}</TableHead>
                    <TableHead className="hidden text-right md:table-cell">{t("col_cost")}</TableHead>
                    <TableHead className="hidden text-right sm:table-cell">{t("col_margin")}</TableHead>
                    <TableHead className="text-right">{t("col_per_gal")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.byGrade.map((r) => (
                    <TableRow key={`${r.grade}/${r.currency ?? ""}`}>
                      <TableCell className="font-medium">{gradeName(r)}</TableCell>
                      <TableCell className="text-right tabular-nums">{f.gal(r.volumeMilli)}</TableCell>
                      <TableCell className="hidden text-right tabular-nums md:table-cell">{r.count}</TableCell>
                      <TableCell className="text-right tabular-nums">{m.fmtIn(gradeCur(r), r.amountCents)}</TableCell>
                      <TableCell className="hidden text-right tabular-nums md:table-cell">
                        {costed(r) ? m.fmtIn(gradeCur(r), r.costCents ?? 0) : "—"}
                      </TableCell>
                      <TableCell className="hidden text-right font-medium tabular-nums sm:table-cell">
                        {costed(r) ? m.fmtIn(gradeCur(r), r.marginCents ?? 0) : "—"}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{f.perGallon(r.marginMillsPerGallon)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
                {pumps && (
                  <TableFooter>
                    <TableRow>
                      <TableCell className="font-semibold">{t("fuel_total")}</TableCell>
                      <TableCell className="text-right font-semibold tabular-nums">{f.gal(pumps.volumeMilli)}</TableCell>
                      <TableCell className="hidden text-right font-semibold tabular-nums md:table-cell">{pumps.count}</TableCell>
                      <TableCell className="text-right font-semibold tabular-nums">
                        {oneCurrency
                          ? m.fmtIn(gradeCur(data.byGrade[0]), pumps.amountCents)
                          : m.joinAmounts(m.perCurrency(data.byGrade, gradeCur, (r) => r.amountCents))}
                      </TableCell>
                      <TableCell className="hidden text-right font-semibold tabular-nums md:table-cell">
                        {fuelCosted ? m.fmtScope(money, pumps.costCents ?? 0) : "—"}
                      </TableCell>
                      <TableCell className="hidden text-right font-semibold tabular-nums sm:table-cell">
                        {fuelCosted ? fuelMargin : "—"}
                      </TableCell>
                      <TableCell className="text-right font-semibold tabular-nums">
                        {f.perGallon(pumps.marginMillsPerGallon)}
                      </TableCell>
                    </TableRow>
                  </TableFooter>
                )}
              </Table>
              {(uncostedFills > 0 || prepayNote) && (
                <div className="space-y-1 border-t border-neutral-100 px-5 py-3 text-xs text-neutral-500">
                  {uncostedFills > 0 && <p>{t("margin_uncosted_fills", { n: uncostedFills })}</p>}
                  {prepayNote && <p>{prepayNote}</p>}
                </div>
              )}
            </Card>
          ) : (
            <Card>
              <EmptyState title={t("fuel_empty")} hint={t("fuel_empty_hint")} />
            </Card>
          )}

          <p className="px-1 text-xs text-neutral-500">{t("fuel_note")}</p>
        </>
      )}
    </div>
  );
}
