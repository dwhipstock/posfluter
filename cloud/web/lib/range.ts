export type DateRange = { from: string; to: string };

const pad = (n: number) => String(n).padStart(2, "0");

export function toISODate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function todayISO(): string {
  return toISODate(new Date());
}

function daysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return toISODate(d);
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
      const d = new Date();
      return { from: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-01`, to: todayISO() };
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
