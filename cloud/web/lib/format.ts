// Money + numeric helpers. One house style for both locales ($ prefix, comma
// grouping, cents only when nonzero), matching the POS client — so nothing
// here depends on locale. Date + tender labels, which DO
// change with locale, live in lib/i18n/.

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

export function centsToInput(s: number): string {
  return s % 100 === 0 ? String(s / 100) : (s / 100).toFixed(2);
}

export function inputToCents(v: string): number | null {
  if (v.trim() === "") return null; // Number("") is 0 — a blank field must not price an item at $0
  const n = Number(v);
  if (!Number.isFinite(n) || n < 0) return null;
  return Math.round(n * 100);
}
