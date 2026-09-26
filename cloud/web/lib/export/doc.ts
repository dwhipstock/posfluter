// The report → export data model. A page builds one ExportDoc from the data it
// already fetched; buildXlsx (lib/export/xlsx.ts) and buildPdf (lib/export/pdf.ts)
// each render it. Keeping one typed model means every report — and both output
// formats — share column definitions, alignment and money handling.
//
// Money is carried as integer cents all the way down: the XLSX writes it as a
// real number (cents/100, 2-decimal CAD) the accountant can sum; the PDF
// renders it as a "$1,234.56" string. Never stringify money before here.

import type { Locale } from "@/lib/i18n/messages";

export type CellKind = "text" | "money" | "int";

export interface Cell {
  kind: CellKind;
  /** money → cents (int); int → the number; text → the string. */
  value: string | number | null;
  /** money only: the ISO currency the cents are in (default CAD). */
  currency?: string;
  /** money only: a converted, approximate figure ("≈" in the PDF). */
  approximate?: boolean;
}

export const T = (v: string | number | null | undefined): Cell => ({
  kind: "text",
  value: v == null ? "" : String(v),
});
export const Money = (cents: number, currency?: string, approximate?: boolean): Cell => ({
  kind: "money",
  value: Math.round(cents),
  ...(currency ? { currency } : {}),
  ...(approximate ? { approximate } : {}),
});
export const Int = (n: number): Cell => ({ kind: "int", value: n });

// A column bound to a row type R: header + how to pull the cell out of a row.
// col.money / col.int default to right-aligned; col.text to left.
export interface Col<R> {
  header: string;
  align: "left" | "right";
  /** Preferred XLSX column width in characters. */
  width: number;
  get: (row: R) => Cell;
}

export interface ColOpts {
  /** XLSX column width in characters. */
  width?: number;
  /** Override alignment (text defaults left; money/int default right). */
  align?: "left" | "right";
}

function make<R>(
  kind: CellKind,
  header: string,
  get: (r: R) => string | number | null | undefined,
  defWidth: number,
  defAlign: "left" | "right",
  opts?: ColOpts
): Col<R> {
  const wrap =
    kind === "money"
      ? (r: R) => Money(Number(get(r) ?? 0))
      : kind === "int"
        ? (r: R) => Int(Number(get(r) ?? 0))
        : (r: R) => T(get(r));
  return { header, align: opts?.align ?? defAlign, width: opts?.width ?? defWidth, get: wrap };
}

export const col = {
  text: <R>(header: string, get: (r: R) => string | number | null | undefined, opts?: ColOpts): Col<R> =>
    make("text", header, get, 22, "left", opts),
  money: <R>(header: string, get: (r: R) => number, opts?: ColOpts): Col<R> =>
    make("money", header, get, 14, "right", opts),
  int: <R>(header: string, get: (r: R) => number, opts?: ColOpts): Col<R> =>
    make("int", header, get, 10, "right", opts),
};

// R defaults to `any` (not `unknown`) on purpose: a doc holds Sections over
// different row types, and `Col`'s `get` is contravariant in R, so `unknown`
// would reject a `Col<DayRow>`. Call sites stay type-safe via col.*<Row>().
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export interface Section<R = any> {
  /** Sheet name in XLSX; sub-heading in PDF. Falls back to the report title. */
  title?: string;
  /** Small caption under the section heading (both formats). */
  note?: string;
  columns: Col<R>[];
  rows: R[];
  /** Optional totals row; one cell per column (usually T(label) then Money/Int). */
  total?: Cell[];
  /** The currency of a row; default: `row.currency`, else its store's, else the scope's. */
  rowCurrency?: (row: R) => string | undefined;
  /** The totals row's currency label (e.g. "≈ CAD" when converted); default: the scope's. */
  totalCurrency?: string;
}

export interface Kpi {
  label: string;
  /** Pre-formatted for display (PDF/XLSX show it as-is). */
  value: string;
}

/** The client's colours for the export header rules, headings and table header (#rrggbb). */
export interface ExportColors {
  accent: string;
  text: string;
  muted: string;
  faint: string;
  headerFill: string;
  rule: string;
}

export interface ExportDoc {
  /** `{report}_{from}_{to}` — the ".pdf"/".xlsx" is appended per format. */
  filenameBase: string;
  reportTitle: string;
  /** The store's full name, or the group's name in "All stores". */
  venue: string;
  /** "Store: Plateau" / "All stores (2)" — printed under the title in every format. */
  scopeLabel: string;
  /** Locale-aware, e.g. "1 – 31 Jan." or "Last 7 days". */
  rangeLabel: string;
  /** "Généré le 12 juill. 2026 09:20" — built by the caller with the active fmt. */
  generatedLabel: string;
  locale: Locale;
  /** Explanatory captions (e.g. the tax decomposition note). */
  notes?: string[];
  kpis?: Kpi[];
  sections: Section[];
  /** The tenant has stores in several currencies: dollars print as CA$ / US$. */
  multiCurrency?: boolean;
  /** The client's brand colours; absent → the portal's defaults. */
  colors?: ExportColors;
  /** The client's name, as the file's creator/producer metadata. */
  producer?: string;
}

/**
 * Give every section that carries money a Currency column (right after the
 * first column) and stamp each money cell with its row's currency, so a
 * spreadsheet never mixes CAD and USD figures silently. Applied once, by the
 * export menu, to whatever doc a page built.
 */
export function withCurrencyColumns(
  doc: ExportDoc,
  opts: {
    header: string;
    currencyOf: (venueId: string) => string;
    /** The scope's currency (the picked store's, or the reporting one). */
    fallback: string;
    /** Totals are converted (All stores across currencies). */
    approximateTotals: boolean;
    multiCurrency: boolean;
  }
): ExportDoc {
  const rowCur = <R>(s: Section<R>, row: R): string => {
    const own = s.rowCurrency?.(row);
    if (own) return own;
    const r = row as unknown as { currency?: string; venueId?: string };
    if (r && typeof r.currency === "string" && r.currency) return r.currency;
    if (r && typeof r.venueId === "string" && r.venueId) return opts.currencyOf(r.venueId);
    return opts.fallback;
  };
  const sections = doc.sections.map((s) => {
    if (s.columns.length === 0 || s.columns.some((c) => c.header === opts.header)) return s;
    const hasMoney = s.rows.some((row) => s.columns.some((c) => c.get(row).kind === "money")) ||
      (s.total ?? []).some((c) => c.kind === "money");
    if (!hasMoney) return s;
    const currencyCol: Col<unknown> = {
      header: opts.header,
      align: "left",
      width: 8,
      get: (row) => T(rowCur(s, row)),
    };
    const columns = s.columns.map((c) => ({
      ...c,
      get: (row: unknown) => {
        const cell = c.get(row);
        return cell.kind === "money" && !cell.currency ? { ...cell, currency: rowCur(s, row) } : cell;
      },
    }));
    const totalCur = s.totalCurrency ?? (opts.approximateTotals ? `≈ ${opts.fallback}` : opts.fallback);
    const total = s.total?.map((c) =>
      c.kind === "money" && !c.currency ? { ...c, currency: opts.fallback, approximate: opts.approximateTotals } : c
    );
    return {
      ...s,
      columns: [columns[0], currencyCol, ...columns.slice(1)],
      total: total ? [total[0], T(totalCur), ...total.slice(1)] : undefined,
    };
  });
  return { ...doc, sections, multiCurrency: opts.multiCurrency };
}

// Helper: a Section is worth emitting only if it has rows. Reports skip empty
// sections so an all-quiet range still produces a valid (header-only) file.
export const hasRows = (s: Section): boolean => s.rows.length > 0;
