"use client";

import { useMemo } from "react";
import { money } from "./format";
import { useStores, useVenues } from "./store";
import type { Currency, FxRate, MoneyScope } from "./types";
import { Money, T, type Cell } from "./export/doc";

// Money across the tenant's stores. Each store sells in its own currency; a
// figure is always shown in the currency it was taken in. When the tenant has
// more than one currency every dollar names its country (CA$ / US$) so nothing
// reads ambiguously, and a combined "All stores" figure (converted by the API
// at a fixed rate) is marked "≈". Never add two currencies here: sums across
// stores go through `perCurrency` (exact, one entry per currency) or the API's
// converted figures.

export interface CurrencyAmount {
  currency: Currency;
  cents: number;
}

/**
 * The net cash rounding of a report scope, exactly: the API's single figure
 * when every in-scope store shares one currency, else one amount per currency
 * from `byCurrency`. Never added or converted across currencies. Empty when
 * the API sent no rounding at all (an older API) — nothing to show then.
 */
export function cashRoundingAmounts(
  combined: number | null | undefined,
  byCurrency: { currency: Currency; cashRoundingCents?: number | null }[] | undefined,
  scopeCurrency: Currency
): CurrencyAmount[] {
  const rows = byCurrency ?? [];
  const sent = combined !== undefined || rows.some((r) => r.cashRoundingCents != null);
  if (!sent) return [];
  if (rows.length > 1) return rows.map((r) => ({ currency: r.currency, cents: r.cashRoundingCents ?? 0 }));
  if (combined != null) return [{ currency: rows[0]?.currency ?? scopeCurrency, cents: combined }];
  if (rows.length === 1) return [{ currency: rows[0].currency, cents: rows[0].cashRoundingCents ?? 0 }];
  return [];
}

/** Export KPI lines for the cash rounding: one signed figure, or one per currency. */
export function cashRoundingKpis(label: string, amounts: CurrencyAmount[], signedIn: MoneyApi["signedIn"]) {
  return amounts.map((a) => ({
    label: amounts.length > 1 ? `${label} · ${a.currency}` : label,
    value: signedIn(a.currency, a.cents),
  }));
}

export interface MoneyApi {
  /** More than one currency among the tenant's stores. */
  multi: boolean;
  reportingCurrency: Currency;
  rates: FxRate[];
  currencyOf: (venueId: string) => Currency;
  /** The picked store's currency, else the reporting currency. */
  scopeCurrency: Currency;
  /** True in "All stores" with stores in more than one currency. */
  mixedScope: boolean;
  fmtIn: (currency: Currency | undefined, cents: number) => string;
  shortIn: (currency: Currency | undefined, cents: number) => string;
  fmtVenue: (venueId: string, cents: number) => string;
  /** "+$5" / "-$5": over/short style. */
  signedIn: (currency: Currency | undefined, cents: number) => string;
  /** A combined figure: "≈ " when the scope converted currencies. */
  fmtScope: (scope: MoneyScope | undefined, cents: number) => string;
  shortScope: (scope: MoneyScope | undefined, cents: number) => string;
  /** [cents] of [currency] in the reporting currency (null without a rate). */
  toReporting: (cents: number, currency: Currency | undefined) => number | null;
  /** A store's figure for charts that stack stores: converted when the scope is mixed. */
  chartValue: (venueId: string, cents: number) => number;
  /** Exact per-currency sums of rows (never one mixed pile). */
  perCurrency: <R>(rows: R[], currencyOfRow: (r: R) => Currency | undefined, value: (r: R) => number) => CurrencyAmount[];
  /** "CA$1,150 · US$43.80" — exact amounts joined. */
  joinAmounts: (amounts: CurrencyAmount[]) => string;
  /** "+CA$0.15 · -US$0.37" — exact amounts joined, each with its sign. */
  signedAmounts: (amounts: CurrencyAmount[]) => string;
  /** An export totals cell over per-store rows: a sum in one currency, else the exact amounts joined. */
  totalCell: <R>(rows: R[], currencyOfRow: (r: R) => Currency | undefined, value: (r: R) => number) => Cell;
  /** "1 USD = 1.37 CAD" for each rate the scope used. */
  rateText: (scope?: MoneyScope) => string;
}

export function useMoney(): MoneyApi {
  const { data } = useVenues();
  const { storeId } = useStores();
  return useMemo(() => {
    const venues = data?.venues ?? [];
    const reportingCurrency = (data?.reportingCurrency ?? "CAD").toUpperCase();
    const rates = data?.rates ?? [];
    const byId = new Map(venues.map((v) => [v.id, (v.currency ?? "CAD").toUpperCase()]));
    const currencies = Array.from(new Set(venues.map((v) => (v.currency ?? "CAD").toUpperCase())));
    const multi = currencies.length > 1;
    const currencyOf = (id: string) => byId.get(id) ?? "CAD";
    const scopeCurrency = storeId ? currencyOf(storeId) : currencies.length === 1 ? currencies[0] : reportingCurrency;
    const mixedScope = !storeId && multi;
    const fmtIn = (c: Currency | undefined, cents: number) => money(cents, c ?? "CAD", { unambiguous: multi });
    const shortIn = (c: Currency | undefined, cents: number) =>
      money(cents, c ?? "CAD", { unambiguous: multi, short: true });
    const rateOf = (from: string, to: string): number | null => {
      if (from === to) return 1;
      const direct = rates.find((r) => r.from === from && r.to === to);
      if (direct) return Number(direct.rate);
      const reverse = rates.find((r) => r.from === to && r.to === from);
      return reverse && Number(reverse.rate) > 0 ? 1 / Number(reverse.rate) : null;
    };
    const toReporting = (cents: number, c: Currency | undefined) => {
      const r = rateOf((c ?? "CAD").toUpperCase(), reportingCurrency);
      return r == null ? null : Math.round(cents * r);
    };
    const scopeCur = (scope?: MoneyScope) => scope?.currency ?? scopeCurrency;
    const perCurrency = <R,>(rows: R[], cur: (r: R) => Currency | undefined, value: (r: R) => number) => {
      const sums = new Map<string, number>();
      for (const r of rows) {
        const c = (cur(r) ?? "CAD").toUpperCase();
        sums.set(c, (sums.get(c) ?? 0) + value(r));
      }
      return Array.from(sums.entries())
        .sort(([a], [b]) => (a === reportingCurrency ? -1 : b === reportingCurrency ? 1 : a.localeCompare(b)))
        .map(([currency, cents]) => ({ currency, cents }));
    };
    return {
      multi,
      reportingCurrency,
      rates,
      currencyOf,
      scopeCurrency,
      mixedScope,
      fmtIn,
      shortIn,
      fmtVenue: (id, cents) => fmtIn(currencyOf(id), cents),
      signedIn: (c, cents) => (cents > 0 ? `+${fmtIn(c, cents)}` : fmtIn(c, cents)),
      fmtScope: (scope, cents) => `${scope?.approximate ? "≈ " : ""}${fmtIn(scopeCur(scope), cents)}`,
      shortScope: (scope, cents) => `${scope?.approximate ? "≈" : ""}${shortIn(scopeCur(scope), cents)}`,
      toReporting,
      chartValue: (id, cents) => (mixedScope ? toReporting(cents, currencyOf(id)) ?? 0 : cents),
      perCurrency,
      joinAmounts: (amounts) => amounts.map((a) => fmtIn(a.currency, a.cents)).join(" · "),
      signedAmounts: (amounts) =>
        amounts.map((a) => (a.cents > 0 ? `+${fmtIn(a.currency, a.cents)}` : fmtIn(a.currency, a.cents))).join(" · "),
      totalCell: (rows, cur, value) => {
        const amounts = perCurrency(rows, cur, value);
        if (amounts.length <= 1) return Money(amounts[0]?.cents ?? 0, amounts[0]?.currency ?? scopeCurrency);
        return T(amounts.map((a) => fmtIn(a.currency, a.cents)).join(" · "));
      },
      rateText: (scope) =>
        (scope?.rates ?? rates.filter((r) => r.to === reportingCurrency)).map((r) => `1 ${r.from} = ${r.rate} ${r.to}`).join(", "),
    };
  }, [data, storeId]);
}
