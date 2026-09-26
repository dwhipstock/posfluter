// Money + numeric helpers. Cents only when nonzero, matching the POS client.
// English: "$1,234.50"; French (Québec): "1 234,50 $" — pass the locale to
// `money` (useMoney does). Dates and tender labels live in lib/i18n/.

export function hourLabel(h: number): string {
  return `${String(h).padStart(2, "0")}:00`;
}

// "$1,234" — cents shown only when nonzero.
export function cad(cents: number): string {
  const neg = cents < 0;
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100).toLocaleString("en-US");
  const frac = abs % 100;
  return `${neg ? "-" : ""}$${whole}${frac ? "." + String(frac).padStart(2, "0") : ""}`;
}

/** Backward-compatible component formatter name. */
export const CAD = cad;

export function CADSigned(cents: number): string {
  return cents > 0 ? `+${cad(cents)}` : cad(cents);
}

export function CADShort(cents: number): string {
  const b = cents / 100;
  const abs = Math.abs(b);
  if (abs >= 1_000_000) return `$${(b / 1_000_000).toFixed(1)}M`;
  if (abs >= 10_000) return `$${Math.round(b / 1000)}k`;
  if (abs >= 1_000) return `$${(b / 1000).toFixed(1)}k`;
  return `$${Math.round(b)}`;
}

// CSV / tax filing wants plain decimal CAD.
export function CADPlain(cents: number): string {
  return (cents / 100).toFixed(2);
}

/** The symbol a currency is shown with. Dollars name their country when [unambiguous]. */
export function currencySymbol(currency: string = "CAD", unambiguous = false): string {
  switch ((currency || "CAD").toUpperCase()) {
    case "CAD":
      return unambiguous ? "CA$" : "$";
    case "USD":
      return unambiguous ? "US$" : "$";
    case "EUR":
      return "€";
    default:
      return `${currency.toUpperCase()} `;
  }
}

const NBSP = "\u00A0";
const NNBSP = "\u202F"; // narrow no-break space: French thousands

function groupThousands(n: number, sep: string): string {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, sep);
}

/** French (Québec) dollar suffix: "$", or "$ CA" / "$ US" when several currencies are shown. */
function frSuffix(currency: string, unambiguous: boolean): string {
  switch (currency.toUpperCase()) {
    case "CAD":
      return unambiguous ? `$${NBSP}CA` : "$";
    case "USD":
      return unambiguous ? `$${NBSP}US` : "$";
    case "EUR":
      return "€";
    default:
      return currency.toUpperCase();
  }
}

export type MoneyLocale = "fr" | "en";

/**
 * Currency-aware house style: cents only when nonzero. English puts the
 * symbol first with comma grouping ("$1,234", "US$12.99"); French (Québec)
 * puts it after, with a decimal comma and a narrow no-break space between
 * thousands ("1 234 $", "12,99 $ US"). For a CAD-only tenant in English
 * `money(c)` is exactly `cad(c)`. [unambiguous] is set by callers when the
 * tenant has several currencies, so a dollar always says whose. [short] is the
 * chart-axis form ("$1.2k" / "1,2 k$").
 */
export function money(
  cents: number,
  currency: string = "CAD",
  opts?: { unambiguous?: boolean; short?: boolean; locale?: MoneyLocale }
): string {
  const cur = currency || "CAD";
  const fr = opts?.locale === "fr";
  const unambiguous = !!opts?.unambiguous;
  if (opts?.short) {
    const b = cents / 100;
    const abs = Math.abs(b);
    const sign = b < 0 ? "-" : "";
    const [num, unit] =
      abs >= 1_000_000
        ? [(abs / 1_000_000).toFixed(1), "M"]
        : abs >= 10_000
          ? [String(Math.round(abs / 1000)), "k"]
          : abs >= 1_000
            ? [(abs / 1000).toFixed(1), "k"]
            : [String(Math.round(abs)), ""];
    if (fr) return `${sign}${num.replace(".", ",")}${NBSP}${unit}${frSuffix(cur, unambiguous)}`;
    return `${sign}${currencySymbol(cur, unambiguous)}${num}${unit}`;
  }
  const neg = cents < 0;
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100);
  const frac = abs % 100;
  const cc = String(frac).padStart(2, "0");
  if (fr) {
    return `${neg ? "-" : ""}${groupThousands(whole, NNBSP)}${frac ? "," + cc : ""}${NBSP}${frSuffix(cur, unambiguous)}`;
  }
  return `${neg ? "-" : ""}${currencySymbol(cur, unambiguous)}${groupThousands(whole, ",")}${frac ? "." + cc : ""}`;
}

/**
 * Always two decimals, for PDF tables: "$1,234.50" / "1 234,50 $". French
 * thousands use a full no-break space here: the PDF's bundled font is sure to
 * have it.
 */
export function moneyCents(
  cents: number,
  currency: string = "CAD",
  opts?: { unambiguous?: boolean; locale?: MoneyLocale }
): string {
  const cur = currency || "CAD";
  const neg = cents < 0;
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100);
  const cc = String(abs % 100).padStart(2, "0");
  if (opts?.locale === "fr") {
    return `${neg ? "-" : ""}${groupThousands(whole, NBSP)},${cc}${NBSP}${frSuffix(cur, !!opts?.unambiguous)}`;
  }
  return `${neg ? "-" : ""}${currencySymbol(cur, opts?.unambiguous)}${groupThousands(whole, ",")}.${cc}`;
}

/** An integer count in the locale's grouping ("1,234" / "1 234"). */
export function count(n: number, locale: MoneyLocale = "en"): string {
  return groupThousands(Math.round(n), locale === "fr" ? NNBSP : ",");
}

/** A plain decimal in the locale's style ("1.25" / "1,25"). */
export function decimal(n: number, digits: number, locale: MoneyLocale = "en"): string {
  const s = n.toFixed(digits);
  return locale === "fr" ? s.replace(".", ",") : s;
}

export function centsToInput(s: number): string {
  return s % 100 === 0 ? String(s / 100) : (s / 100).toFixed(2);
}

export function inputToCents(v: string): number | null {
  if (v.trim() === "") return null; // Number("") is 0 — a blank field must not price an item at $0
  const n = Number(v);
  if (!Number.isFinite(n) || n < 0) return null;
  return Math.round(n * 100);
}
