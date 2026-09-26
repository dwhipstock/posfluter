"use client";

import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { useApi, reportKey } from "@/lib/hooks";
import type { DateRange } from "@/lib/range";
import { useMoney } from "@/lib/money";
import { useScopedKpi } from "@/components/money-scope";
import { useFuelFmt } from "@/lib/fuel";
import { useT } from "@/lib/i18n/context";
import { useStoreHref } from "@/lib/store";
import type { FuelReport } from "@/lib/types";
import { Kpi } from "@/components/kpi";

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
