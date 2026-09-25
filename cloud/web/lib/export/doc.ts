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
}

export const T = (v: string | number | null | undefined): Cell => ({
  kind: "text",
  value: v == null ? "" : String(v),
});
export const Money = (cents: number): Cell => ({ kind: "money", value: Math.round(cents) });
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
}

export interface Kpi {
  label: string;
  /** Pre-formatted for display (PDF/XLSX show it as-is). */
  value: string;
}

export interface ExportDoc {
  /** `{report}_{from}_{to}` — the ".pdf"/".xlsx" is appended per format. */
  filenameBase: string;
  reportTitle: string;
  venue: string;
  /** Locale-aware, e.g. "1 – 31 Jan." or "Last 7 days". */
  rangeLabel: string;
  /** "Émettre un rapport 12 Juillet 2026 09:20" — built by the caller with the active fmt. */
  generatedLabel: string;
  locale: Locale;
  /** Explanatory captions (e.g. the VAT decomposition note). */
  notes?: string[];
  kpis?: Kpi[];
  sections: Section[];
}

// Helper: a Section is worth emitting only if it has rows. Reports skip empty
// sections so an all-quiet range still produces a valid (header-only) file.
export const hasRows = (s: Section): boolean => s.rows.length > 0;
