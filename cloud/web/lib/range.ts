export type DateRange = { from: string; to: string };

// Date presets pick calendar dates in the stores' business timezone (not the
// browser's). The API reads each date over each store's own business day, so
// stores in other zones still line up; keep in sync with the stores' VENUE_TZ.
export const VENUE_TIME_ZONE = "America/New_York";

export function todayISO(): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: VENUE_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const value = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${value("year")}-${value("month")}-${value("day")}`;
}

function daysAgo(n: number): string {
  const d = new Date(`${todayISO()}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() - n);
  return d.toISOString().slice(0, 10);
}

// Labels are looked up by key (preset_<key>) in the i18n layer — keep this
// module free of display strings so it stays locale-agnostic.
export const PRESETS: { key: string; range: () => DateRange }[] = [
  { key: "today", range: () => ({ from: todayISO(), to: todayISO() }) },
  { key: "yesterday", range: () => ({ from: daysAgo(1), to: daysAgo(1) }) },
  { key: "7d", range: () => ({ from: daysAgo(6), to: todayISO() }) },
  { key: "30d", range: () => ({ from: daysAgo(29), to: todayISO() }) },
  {
    key: "month",
    range: () => {
      const today = todayISO();
      return { from: `${today.slice(0, 7)}-01`, to: today };
    },
  },
];

const ISO = /^\d{4}-\d{2}-\d{2}$/;

export function rangeFromParams(sp: { get(name: string): string | null }): DateRange {
  const from = sp.get("from");
  const to = sp.get("to");
  if (from && to && ISO.test(from) && ISO.test(to)) {
    return from <= to ? { from, to } : { from: to, to: from };
  }
  return PRESETS[0].range();
}

export function presetKeyOf(r: DateRange): string | null {
  const p = PRESETS.find((x) => {
    const pr = x.range();
    return pr.from === r.from && pr.to === r.to;
  });
  return p?.key ?? null;
}
