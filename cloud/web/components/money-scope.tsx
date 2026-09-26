"use client";

import { Coins, Scale } from "lucide-react";
import { useT } from "@/lib/i18n/context";
import { useMoney, type CurrencyAmount } from "@/lib/money";
import { useStores } from "@/lib/store";
import type { CurrencySummaryRow, MoneyScope, Venue } from "@/lib/types";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

/** The retail store (the bottle shop): its kind, or its venue id for an older API. */
export function isRetail(v?: Pick<Venue, "id" | "kind"> | null): boolean {
  return !!v && (v.kind === "retail" || v.id === "sage-poppy");
}

/**
 * The bottle shop's small sage/poppy badge, next to its name wherever the
 * portal names a store. The portal keeps its own look otherwise.
 */
export function RetailBadge({ venueId, className }: { venueId: string; className?: string }) {
  const t = useT();
  const { venues } = useStores();
  if (!isRetail(venues.find((v) => v.id === venueId) ?? { id: venueId })) return null;
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-semibold leading-none text-white",
        className
      )}
      style={{ backgroundColor: "#5E7F5A" }}
      data-testid="retail-badge"
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: "#E8702A" }} aria-hidden />
      {t("retail_badge")}
    </span>
  );
}

/**
 * Shown on a page whose scope mixes currencies: per-store figures are exact in
 * their own currency; combined ones are converted at a fixed rate (or, with no
 * rate configured, not given at all).
 */
export function FxNote({ money, className }: { money?: MoneyScope; className?: string }) {
  const t = useT();
  const m = useMoney();
  if (!money?.approximate) return null;
  return (
    <div
      className={cn(
        "flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-attention",
        className
      )}
      data-testid="fx-note"
    >
      <Coins className="mt-0.5 h-3.5 w-3.5 shrink-0" />
      <span>
        <span className="font-semibold">{t("fx_mixed_title")}.</span>{" "}
        {money.convertible
          ? t("fx_note", { cur: money.reportingCurrency, rates: m.rateText(money) })
          : t("fx_no_rate")}
      </span>
    </div>
  );
}

/**
 * The net cash rounding (cash payments round to the nearest 5¢), as a modest
 * line: one signed figure for one currency, else one per currency — never a
 * converted or summed figure. Renders nothing when the API sent none.
 */
export function CashRoundingNote({ amounts, className }: { amounts: CurrencyAmount[]; className?: string }) {
  const t = useT();
  const m = useMoney();
  if (amounts.length === 0) return null;
  return (
    <div
      className={cn(
        "flex items-start gap-2 rounded-lg border border-neutral-200 bg-surface-alt px-3 py-2 text-xs text-neutral-600",
        className
      )}
      data-testid="cash-rounding"
    >
      <Scale className="mt-0.5 h-3.5 w-3.5 shrink-0 text-neutral-500" />
      <span>
        <span className="font-semibold text-neutral-700">{t("cash_rounding")}:</span>{" "}
        <span className="font-semibold tabular-nums text-neutral-800">{m.signedAmounts(amounts)}</span>
        <span className="text-neutral-500"> · {t("cash_rounding_note")}</span>
      </span>
    </div>
  );
}

/**
 * A combined KPI value and its sub-line. One currency: the exact figure. Mixed:
 * "≈ CA$…" (converted) with the exact per-currency amounts underneath, or just
 * the exact amounts when no rate is configured.
 */
export function useScopedKpi() {
  const m = useMoney();
  return (money: MoneyScope | undefined, cents: number, exact: CurrencyAmount[]) => {
    if (!money?.approximate) return { value: m.fmtScope(money, cents), sub: undefined as string | undefined };
    if (!money.convertible) return { value: m.joinAmounts(exact), sub: undefined };
    return { value: m.fmtScope(money, cents), sub: m.joinAmounts(exact) };
  };
}

/** A footer total over rows that may mix currencies: exact per currency, never one sum. */
export function useMixedTotal() {
  const m = useMoney();
  return <R,>(rows: R[], currencyOf: (r: R) => string | undefined, value: (r: R) => number) =>
    m.joinAmounts(m.perCurrency(rows, currencyOf, value));
}

/**
 * "All stores" across countries: one exact row per currency (CAD, USD …) and the
 * approximate converted total in the reporting currency with the rate used.
 */
export function ByCurrencyCard({ rows, money }: { rows?: CurrencySummaryRow[]; money?: MoneyScope }) {
  const t = useT();
  const m = useMoney();
  if (!money?.approximate || !rows || rows.length < 2) return null;
  const converted = rows.reduce<number | null>(
    (sum, r) => (sum == null || r.grossReportingCents == null ? null : sum + r.grossReportingCents),
    0
  );
  return (
    <Card data-testid="by-currency">
      <CardHeader>
        <CardTitle>{t("fx_by_currency")}</CardTitle>
        <CardDescription>
          {money.convertible
            ? t("fx_note", { cur: money.reportingCurrency, rates: m.rateText(money) })
            : t("fx_no_rate")}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="-mx-2">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_currency")}</TableHead>
                <TableHead className="text-right">{t("col_gross")}</TableHead>
                <TableHead className="hidden text-right sm:table-cell">{t("col_net")}</TableHead>
                <TableHead className="hidden text-right sm:table-cell">{t("col_tax")}</TableHead>
                <TableHead className="text-right">{t("col_checks")}</TableHead>
                <TableHead className="hidden text-right md:table-cell">{t("kpi_avg_check")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.currency}>
                  <TableCell className="font-medium">
                    {r.currency}
                    <span className="ml-2 text-xs font-normal text-neutral-500">
                      {t("fx_stores_in", { n: r.venueIds.length })}
                    </span>
                  </TableCell>
                  <TableCell className="text-right font-semibold tabular-nums">{m.fmtIn(r.currency, r.grossCents)}</TableCell>
                  <TableCell className="hidden text-right tabular-nums sm:table-cell">{m.fmtIn(r.currency, r.netCents)}</TableCell>
                  <TableCell className="hidden text-right tabular-nums sm:table-cell">{m.fmtIn(r.currency, r.taxCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{r.checkCount}</TableCell>
                  <TableCell className="hidden text-right tabular-nums md:table-cell">
                    {m.fmtIn(r.currency, r.avgCheckCents)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
            {money.convertible && converted != null && (
              <TableFooter>
                <TableRow>
                  <TableCell>
                    {t("fx_converted_total", { cur: money.reportingCurrency })}
                    <div className="text-[11px] font-normal text-neutral-500">
                      {t("fx_converted_sub", { rates: m.rateText(money) })}
                    </div>
                  </TableCell>
                  <TableCell className="text-right font-semibold tabular-nums">
                    {m.fmtScope(money, converted)}
                  </TableCell>
                  <TableCell className="hidden sm:table-cell" />
                  <TableCell className="hidden sm:table-cell" />
                  <TableCell className="text-right tabular-nums">
                    {rows.reduce((n, r) => n + r.checkCount, 0)}
                  </TableCell>
                  <TableCell className="hidden md:table-cell" />
                </TableRow>
              </TableFooter>
            )}
          </Table>
        </div>
      </CardContent>
    </Card>
  );
}
