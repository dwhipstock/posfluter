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
  return String(c.value ?? "");
}

function esc(v: string): string {
  return /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

const line = (cells: string[]) => cells.map(esc).join(",");

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
    for (const row of s.rows) out.push(line(s.columns.map((c) => value(c.get(row)))));
    if (s.total) out.push(line(s.total.map(value)));
  }
  return out.join("\r\n") + "\r\n";
}

export async function downloadCsv(doc: ExportDoc): Promise<void> {
  saveBlob(new Blob(["﻿", buildCsv(doc)], { type: "text/csv;charset=utf-8" }), `${doc.filenameBase}.csv`);
}
