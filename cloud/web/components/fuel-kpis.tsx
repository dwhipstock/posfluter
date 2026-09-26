"use client";

import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { useApi, reportKey } from "@/lib/hooks";
import type { DateRange } from "@/lib/range";
import { useMoney } from "@/lib/money";
import { useScopedKpi } from "@/components/money-scope";
import { useCategoryName, useFuelFmt } from "@/lib/fuel";
import { useT } from "@/lib/i18n/context";
import { useStoreHref } from "@/lib/store";
import type { FuelReport } from "@/lib/types";
import { Kpi } from "@/components/kpi";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/states";

/** The report's combined money figures, with their exact per-currency amounts when mixed. */
export function useFuelKpis(data: FuelReport | undefined) {
  const m = useMoney();
  const kpi = useScopedKpi();
  type V = FuelReport["byVenue"][number];
  const cur = (r: V) => r.currency ?? m.currencyOf(r.venueId);
  return (cents: number | undefined, f: (r: V) => number) =>
    data && cents != null ? kpi(data.money, cents, m.perCurrency(data.byVenue, cur, f)) : undefined;
}

/**
 * A gas station's headline row (brand pack `features.fuel`): in-store sales
 * and margin first — the shop is where the money is — then fuel gallons and
 * the (thin) fuel margin per gallon. Margins cover only what has a known cost.
 */
export function FuelDashboardKpis({ range }: { range: DateRange }) {
  const t = useT();
  const f = useFuelFmt();
  const storeHref = useStoreHref();
  const { data, isLoading } = useApi<FuelReport>(reportKey("/v1/reports/fuel", range));
  const money = useFuelKpis(data);
  const inStore = money(data?.inStore.salesCents, (r) => r.inStoreSalesCents);
  const shopMargin = money(data?.inStore.marginCents ?? 0, (r) => r.inStoreMarginCents ?? 0);
  const fuelSales = money(data?.fuel.amountCents, (r) => r.fuelAmountCents);
  const fuelMargin = money(data?.fuel.marginCents ?? 0, (r) => r.fuelMarginCents ?? 0);
  const shopCosted = (data?.inStore.costedLineCount ?? 0) > 0;
  const fuelCosted = (data?.fuel.costedCount ?? 0) > 0;
  return (
    <section aria-label={t("dash_fuel_details")}>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-neutral-500">{t("dash_fuel_details")}</h2>
        <Link
          href={storeHref("/reports/fuel")}
          className="inline-flex items-center gap-1 text-xs font-semibold text-copper-text hover:underline"
        >
          {t("dash_fuel_details")}
          <ChevronRight className="h-3.5 w-3.5" />
        </Link>
      </div>
      <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        <Kpi
          label={t("fuel_in_store")}
          value={inStore?.value}
          sub={inStore?.sub ?? (data ? t("fuel_in_store_sub", { n: data.inStore.checkCount }) : undefined)}
          accent
          loading={isLoading}
        />
        <Kpi
          label={t("instore_margin")}
          value={data ? f.pct(data.inStore.marginBasisPoints) : undefined}
          sub={shopCosted && shopMargin ? t("margin_amount", { amount: shopMargin.value }) : undefined}
          loading={isLoading}
        />
        <Kpi
          label={t("fuel_gallons")}
          value={data ? f.gal(data.fuel.volumeMilli) : undefined}
          sub={fuelSales ? t("fuel_sales_amount", { amount: fuelSales.value }) : undefined}
          loading={isLoading}
        />
        <Kpi
          label={t("fuel_margin")}
          value={data ? f.perGallon(data.fuel.marginMillsPerGallon) : undefined}
          sub={fuelCosted && fuelMargin ? t("margin_amount", { amount: fuelMargin.value }) : undefined}
          loading={isLoading}
        />
      </div>
    </section>
  );
}

/**
 * The shop's sales mix (fuel-flag clients' dashboard): every in-store category
 * by net sales, with its share and margin % — so a big seller with a thin
 * margin (tobacco) is seen, not just the best margins.
 */
export function InStoreCategoryMix({ range }: { range: DateRange }) {
  const t = useT();
  const f = useFuelFmt();
  const m = useMoney();
  const catName = useCategoryName();
  const storeHref = useStoreHref();
  const { data, isLoading } = useApi<FuelReport>(reportKey("/v1/reports/fuel", range));
  const rows = [...(data?.inStoreByCategory ?? [])].sort(
    (a, b) => (m.toReporting(b.salesCents, b.currency) ?? b.salesCents) - (m.toReporting(a.salesCents, a.currency) ?? a.salesCents)
  );
  const top = Math.max(1, ...rows.map((r) => m.toReporting(r.salesCents, r.currency) ?? r.salesCents));
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <div>
          <CardTitle>{t("instore_by_category")}</CardTitle>
          <CardDescription>{t("instore_by_category_sub")}</CardDescription>
        </div>
        <Link
          href={storeHref("/reports/fuel")}
          className="flex shrink-0 items-center gap-0.5 text-xs font-medium text-navy hover:text-navy-deep"
        >
          {t("dash_fuel_details")} <ChevronRight className="h-3.5 w-3.5" />
        </Link>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <div className="space-y-2.5">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-8 w-full" />
            ))}
          </div>
        ) : rows.length > 0 ? (
          <ul className="divide-y divide-neutral-200/70">
            {rows.map((r) => {
              const share = (m.toReporting(r.salesCents, r.currency) ?? r.salesCents) / top;
              return (
                <li key={`${r.categoryId ?? ""}/${r.currency ?? ""}`} className="flex items-center gap-3 py-2">
                  <span className="w-36 shrink-0 truncate text-sm font-medium sm:w-44">{catName(r)}</span>
                  <span className="hidden h-2 flex-1 overflow-hidden rounded-full bg-surface-alt sm:block" aria-hidden>
                    <span className="block h-full rounded-full bg-navy" style={{ width: `${Math.max(2, share * 100)}%` }} />
                  </span>
                  <span className="ml-auto w-24 text-right text-sm font-semibold tabular-nums">{m.fmtIn(r.currency, r.salesCents)}</span>
                  <span className="w-16 text-right text-xs tabular-nums text-neutral-500">{f.pct(r.marginBasisPoints)}</span>
                </li>
              );
            })}
          </ul>
        ) : (
          <EmptyState title={t("instore_empty")} />
        )}
      </CardContent>
    </Card>
  );
}
