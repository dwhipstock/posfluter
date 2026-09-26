import { presetKeyOf, type DateRange } from "@/lib/range";
import type { Locale, MsgKey } from "./messages";

// API timestamps are instants carrying the venue's offset
// ("2026-07-11T18:02:11.000-04:00"): the leading wall clock is already
// venue-local, so display slices it instead of routing it through Date (which
// would convert to the browser's timezone).
function parts(s: string) {
  return { y: Number(s.slice(0, 4)), m: Number(s.slice(5, 7)), d: Number(s.slice(8, 10)), hm: s.slice(11, 16) };
}

const EN_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
// French abbreviated months — the same forms the POS and French receipts use.
// Spanish abbreviated months (lowercase, with a period, as US Spanish writes them).
const ES_MONTHS = ["ene.", "feb.", "mar.", "abr.", "may.", "jun.", "jul.", "ago.", "sept.", "oct.", "nov.", "dic."];
const FR_MONTHS = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juill.", "août", "sept.", "oct.", "nov.", "déc."];

export interface Fmt {
  /** "8 Jan." / "8 Jan" — no year */
  day(date: string): string;
  /** "8 janv. 2026" / "8 Jan 2026" */
  dayYear(date: string): string;
  /** "8 Jan. 20:14" / "8 Jan 20:14" */
  dateTime(dt: string): string;
  time(dt: string): string;
  hour(h: number): string;
  rangeLabel(r: DateRange): string;
}

export function makeFmt(locale: Locale, t: (key: MsgKey, vars?: Record<string, string | number>) => string): Fmt {
  const months = locale === "fr" ? FR_MONTHS : locale === "es" ? ES_MONTHS : EN_MONTHS;

  const day = (date: string) => {
    const p = parts(date);
    return `${p.d} ${months[p.m - 1]}`;
  };
  const dayYear = (date: string) => {
    const p = parts(date);
    return `${p.d} ${months[p.m - 1]} ${p.y}`;
  };
  const dateTime = (dt: string) => {
    const p = parts(dt);
    return `${p.d} ${months[p.m - 1]} ${p.hm}`;
  };
  const time = (dt: string) => dt.slice(11, 16);
  const hour = (h: number) => `${String(h).padStart(2, "0")}:00`;
  const rangeLabel = (r: DateRange) => {
    const key = presetKeyOf(r);
    if (key) return t(`range_${key}` as MsgKey);
    return r.from === r.to ? dayYear(r.from) : `${day(r.from)} – ${day(r.to)}`;
  };

  return { day, dayYear, dateTime, time, hour, rangeLabel };
}
