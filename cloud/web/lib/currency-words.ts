// Strings that name money in words or examples ("round to the nearest 5¢",
// "add a Caesar salad for $14") follow the store's currency. Dollar stores
// (CAD, USD) keep their exact original text; any other currency (THB) reads a
// "_cur" twin of the message whose amounts are filled in with money() in that
// currency, at an amount that is natural there.

import { money } from "./format";
import type { MsgKey } from "./i18n/messages";

/** Currencies whose cash rounds to the nickel and whose examples say "$14". */
const DOLLARS = new Set(["CAD", "USD"]);

export const isDollar = (currency: string | undefined): boolean =>
  DOLLARS.has((currency ?? "CAD").toUpperCase());

/**
 * The cash rounding note for the currencies in a report: the nickel note for
 * dollar stores (today's text), the whole-unit note for THB (cash rounds down
 * to the whole baht), and a note naming both when a scope mixes them.
 */
export function roundingNoteKey(currencies: (string | undefined)[]): MsgKey {
  const list = currencies.length > 0 ? currencies : [undefined];
  const dollars = list.filter(isDollar).length;
  if (dollars === list.length) return "cash_rounding_note";
  if (dollars === 0) return "cash_rounding_note_unit";
  return "cash_rounding_note_mixed";
}

/** Example amounts, in cents of each currency: what a person would type. */
const EXAMPLES: Record<string, { price: number; off: number; step: number }> = {
  THB: { price: 15_000, off: 1_000, step: 500 }, // ฿150, ฿10, ฿5
};

/** Messages that quote an amount, with their currency-neutral twin. */
const CURRENCY_TWINS = {
  ai_placeholder: "ai_placeholder_cur",
  ai_example_1: "ai_example_1_cur",
  ai_example_sales: "ai_example_sales_cur",
  ai_sales_note_size_refused: "ai_sales_note_size_refused_cur",
  ai_sales_note_item_refused: "ai_sales_note_item_refused_cur",
} as const satisfies Partial<Record<MsgKey, MsgKey>>;

/**
 * [key] in [currency]: dollar stores get [key] as it is; another currency
 * gets its "_cur" twin and the amounts to fill in ({price}, {off}, {step}, {zero}).
 */
export function inCurrency(
  key: MsgKey,
  currency: string | undefined
): { key: MsgKey; vars: Record<string, string> } {
  const twin = (CURRENCY_TWINS as Partial<Record<MsgKey, MsgKey>>)[key];
  if (!twin || isDollar(currency)) return { key, vars: {} };
  const cur = (currency ?? "CAD").toUpperCase();
  const ex = EXAMPLES[cur] ?? { price: 1_400, off: 200, step: 50 };
  return {
    key: twin,
    vars: { price: money(ex.price, cur), off: money(ex.off, cur), step: money(ex.step, cur), zero: money(0, cur) },
  };
}
