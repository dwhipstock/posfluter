// Menu specials in the portal's menu editor (CONTRACT §10 "Specials"): an
// item's day prices (some days, maybe a time window, per size) and the days
// it is sold on. Pure helpers — the draft, its validation, and the canonical
// form both sides of menu sync compare (the store's sdk/MenuSpecials.kt).
// No React, no fetch: lib/menu-specials.test.ts covers it.
import type { MenuSpecial } from "./types";

/** Day codes, Monday first (the canonical order). */
export const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;
export type Day = (typeof DAYS)[number];

export const MAX_SPECIALS = 10;
export const MAX_LABEL = 40;
const MAX_CENTS = 10_000_000;

export interface SpecialDraft {
  days: string[];
  /** "HH:mm" or "" (no window). */
  from: string;
  to: string;
  label: string;
  /** Size id → what the manager typed in dollars ("" = no special for that size). */
  prices: Record<string, string>;
}

export type SpecialError = "special_day_required" | "special_price_required" | "special_price_invalid" | "special_time_both" | "special_time_same" | "special_time_invalid" | "special_too_many";

/** Days as given → distinct codes, Monday first; unknown codes dropped. */
export function normalizeDays(days: readonly string[] | null | undefined): string[] {
  const want = new Set((days ?? []).map((d) => d.trim().toLowerCase().slice(0, 3)));
  return DAYS.filter((d) => want.has(d));
}

/** The selling days to send: [] = every day (all seven is every day too). */
export function availableDaysValue(days: readonly string[]): string[] {
  const d = normalizeDays(days);
  return d.length === 7 ? [] : d;
}

/** "9:05" → "09:05"; "" → ""; null when it isn't a time. */
export function normalizeTime(t: string): string | null {
  const s = t.trim();
  if (!s) return "";
  const m = /^(\d{1,2}):(\d{2})$/.exec(s);
  if (!m) return null;
  const h = Number(m[1]);
  const mi = Number(m[2]);
  if (h > 24 || mi > 59 || (h === 24 && mi !== 0)) return null;
  return `${String(h % 24).padStart(2, "0")}:${String(mi).padStart(2, "0")}`;
}

/** Same rules as menu-edit's parseCents (kept local so this file stands alone). */
function cents(input: string): number | null {
  const s = input.trim().replace(/^\$/, "").replace(/,(?=\d{3}(\D|$))/g, "").trim();
  const m = /^(\d*)(?:\.(\d{0,2}))?$/.exec(s);
  if (!m || (m[1] === "" && !m[2])) return null;
  const c = Number(m[1] || "0") * 100 + Number((m[2] ?? "").padEnd(2, "0"));
  return Number.isSafeInteger(c) && c <= MAX_CENTS ? c : null;
}

function centsText(c: number): string {
  return `${Math.floor(c / 100)}.${String(c % 100).padStart(2, "0")}`;
}

export function specialDraftOf(sp: MenuSpecial): SpecialDraft {
  return {
    days: normalizeDays(sp.days),
    from: sp.from ?? "",
    to: sp.to ?? "",
    label: sp.label ?? "",
    prices: Object.fromEntries(Object.entries(sp.prices ?? {}).map(([k, v]) => [k, centsText(v)])),
  };
}

export function emptySpecial(): SpecialDraft {
  return { days: [], from: "", to: "", label: "", prices: {} };
}

/** What stops a save, per special (index → errors). Only [sizeIds] (the item's live sizes) count. */
export function validateSpecials(list: SpecialDraft[], sizeIds: string[]): SpecialError[][] {
  return list.map((sp, i) => {
    const out: SpecialError[] = [];
    if (i >= MAX_SPECIALS) out.push("special_too_many");
    if (normalizeDays(sp.days).length === 0) out.push("special_day_required");
    const typed = sizeIds.filter((id) => (sp.prices[id] ?? "").trim() !== "");
    if (typed.length === 0) out.push("special_price_required");
    if (typed.some((id) => cents(sp.prices[id]) === null)) out.push("special_price_invalid");
    const from = normalizeTime(sp.from);
    const to = normalizeTime(sp.to);
    if (from === null || to === null) out.push("special_time_invalid");
    else if ((from === "") !== (to === "")) out.push("special_time_both");
    else if (from !== "" && from === to) out.push("special_time_same");
    return out;
  });
}

/** Sizes whose special price is not below the menu price (a soft warning, never blocks). */
export function notCheaper(sp: SpecialDraft, regular: Record<string, number>): string[] {
  return Object.keys(regular).filter((id) => {
    const c = cents(sp.prices[id] ?? "");
    return c !== null && (sp.prices[id] ?? "").trim() !== "" && c >= regular[id];
  });
}

/**
 * The canonical special (CONTRACT §10): keys days, from, to, label, prices in
 * that order, optional keys left out, prices sorted by size id. Only
 * [sizeIds] are kept. Assumes [validateSpecials] passed.
 */
export function canonicalSpecial(sp: SpecialDraft, sizeIds: string[]): MenuSpecial {
  const from = normalizeTime(sp.from) || "";
  const to = normalizeTime(sp.to) || "";
  const label = sp.label.replace(/\s+/g, " ").trim().slice(0, MAX_LABEL);
  const prices: Record<string, number> = {};
  for (const id of [...sizeIds].sort()) {
    const raw = (sp.prices[id] ?? "").trim();
    if (!raw) continue;
    const c = cents(raw);
    if (c !== null) prices[id] = c;
  }
  // key order matters (both sides compare the JSON text): days, from, to, label, prices
  return {
    days: normalizeDays(sp.days),
    ...(from && to ? { from, to } : {}),
    ...(label ? { label } : {}),
    prices,
  };
}

/** The list to send, canonical and without duplicates. */
export function specialsValue(list: SpecialDraft[], sizeIds: string[]): MenuSpecial[] {
  const seen = new Set<string>();
  const out: MenuSpecial[] = [];
  for (const sp of list) {
    const c = canonicalSpecial(sp, sizeIds);
    if (Object.keys(c.prices).length === 0) continue;
    const k = JSON.stringify(c);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(c);
  }
  return out;
}

/** The item's specials as loaded, in canonical form (to compare with an edit). */
export function loadedSpecials(list: MenuSpecial[] | null | undefined): MenuSpecial[] {
  return (list ?? []).map((sp) => canonicalSpecial(specialDraftOf(sp), Object.keys(sp.prices ?? {})));
}

export const sameValue = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/**
 * "Fri & Sat", "Mon–Fri", "Every day": [dayName] gives a day's (short) name
 * in the reader's language, [and] the word between the last two.
 * A run of 3+ consecutive days is written first–last.
 */
export function daysSummary(days: readonly string[], dayName: (d: Day) => string, and: string, everyDay: string): string {
  const d = normalizeDays(days) as Day[];
  if (d.length === 0 || d.length === 7) return everyDay;
  const idx = d.map((x) => DAYS.indexOf(x));
  const consecutive = idx.every((v, i) => i === 0 || v === idx[i - 1] + 1);
  if (d.length >= 3 && consecutive) return `${dayName(d[0])}–${dayName(d[d.length - 1])}`;
  const names = d.map(dayName);
  return names.length === 1 ? names[0] : `${names.slice(0, -1).join(", ")}${and}${names[names.length - 1]}`;
}
