// CSV export: the same ExportDoc as the PDF / XLSX, as one plain-text file.
// A header block (venue, scope, report, range, generated), the KPI band as
// label,value rows, then each section: its title, the column headers, the rows
// and the totals row, separated by a blank line. Money is a plain decimal
// (cents / 100, two places, no symbol) so a spreadsheet can sum it. UTF-8 with
// a BOM so Excel opens French accents correctly.

import type { Cell, ExportDoc } from "./doc";
import { saveBlob } from "./download";

function value(c: Cell): string {
  if (c.kind === "money") return (Number(c.value ?? 0) / 100).toFixed(2);
  if (c.kind === "int") return String(Number(c.value ?? 0));
  if (c.kind === "num") return Number(c.value ?? 0).toFixed(c.decimals ?? 2);
  return text(String(c.value ?? ""));
}

/**
 * CSV formula injection: a text cell that starts with = + - @ tab or CR is
 * run as a formula by Excel / Sheets / Numbers (an item named
 * `=HYPERLINK(...)`). Prefix such text with an apostrophe so it opens as
 * text (OWASP's advice). A plain number ("-5.00", "+12") is left alone so it
 * still sums.
 */
function text(v: string): string {
  if (!/^[=+\-@\t\r]/.test(v)) return v;
  if (/^[+-]?\d+(\.\d+)?$/.test(v)) return v;
  return `'${v}`;
}

// CSV formula injection: a text cell starting with = + - @ (or a tab / CR that
// some spreadsheets skip) runs as a formula when the file is opened in Excel or
// Sheets — an item or store name like =HYPERLINK(...). Such a cell gets a
// leading apostrophe (OWASP); a plain number (money, "-5.00") is left alone.
const FORMULA_START = /^[=+\-@\t\r]/;
const PLAIN_NUMBER = /^-?\d+(\.\d+)?$/;

export function neutralize(v: string): string {
  return FORMULA_START.test(v) && !PLAIN_NUMBER.test(v) ? `'${v}` : v;
}

function esc(raw: string): string {
  const v = neutralize(raw);
  return /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

/** A row of already-safe values (numbers from [value], or text passed through [text]). */
const row = (cells: string[]) => cells.map(esc).join(",");
/** A row of free text (venue, labels, headers). */
const line = (cells: string[]) => row(cells.map(text));

export function buildCsv(doc: ExportDoc): string {
  const out: string[] = [
    line([doc.venue]),
    line([doc.scopeLabel]),
    line([`${doc.reportTitle} — ${doc.rangeLabel}`]),
    line([doc.generatedLabel]),
  ];
  for (const n of doc.notes ?? []) out.push(line([n]));
  if (doc.kpis?.length) {
    out.push("");
    for (const k of doc.kpis) out.push(line([k.label, k.value]));
  }
  for (const s of doc.sections) {
    out.push("");
    if (s.title) out.push(line([s.title]));
    out.push(line(s.columns.map((c) => c.header)));
    for (const r of s.rows) out.push(row(s.columns.map((c) => value(c.get(r)))));
    if (s.total) out.push(row(s.total.map(value)));
  }
  return out.join("\r\n") + "\r\n";
}

export async function downloadCsv(doc: ExportDoc): Promise<void> {
  saveBlob(new Blob(["﻿", buildCsv(doc)], { type: "text/csv;charset=utf-8" }), `${doc.filenameBase}.csv`);
}
