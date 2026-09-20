import { presetKeyOf, type DateRange } from "@/lib/range";
import type { Era, Locale, MsgKey } from "./messages";

// Timestamps are naive venue-local strings; never route them through Date.
function parts(s: string) {
  return { y: Number(s.slice(0, 4)), m: Number(s.slice(5, 7)), d: Number(s.slice(8, 10)), hm: s.slice(11, 16) };
}

// Epoch-ms of a naive venue-local timestamp placed on the UTC frame (so two
// naive wall clocks can be diffed against each other). Shared so callers that
// only need this axis (e.g. the devices page's last-seen diff + countdown)
// don't re-slice the string; it deliberately reuses the same positional
// parsing as parts() above.
export function naiveWallMs(s: string): number {
  return Date.UTC(
    Number(s.slice(0, 4)),
    Number(s.slice(5, 7)) - 1,
    Number(s.slice(8, 10)),
    Number(s.slice(11, 13) || 0),
    Number(s.slice(14, 16) || 0),
    Number(s.slice(17, 19) || 0)
  );
}

const EN_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
// French abbreviated months — the same forms the POS and French receipts use.
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

export function makeFmt(locale: Locale, era: Era, t: (key: MsgKey, vars?: Record<string, string | number>) => string): Fmt {
  const months = locale === "fr" ? FR_MONTHS : EN_MONTHS;
  const year = (y: number) => y;

  const day = (date: string) => {
    const p = parts(date);
    return `${p.d} ${months[p.m - 1]}`;
  };
  const dayYear = (date: string) => {
    const p = parts(date);
    return `${p.d} ${months[p.m - 1]} ${year(p.y)}`;
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
