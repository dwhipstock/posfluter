// Excel (.xlsx) export via ExcelJS. Lazy-imported on export click.
//
// The point of the XLSX (vs the PDF) is that it's DATA: money lands as real
// numbers (cents → CAD, 2-decimal number format) the accountant can sum,
// pivot and re-file — never pre-formatted strings. Each report section becomes
// its own worksheet; a KPI band, if present, leads as a summary sheet. Accented
// text is plain UTF-8 in the sheet XML, so it needs no special handling here.

import ExcelJS from "exceljs";
import type { Cell, ExportColors, ExportDoc, Section } from "./doc";
import { saveBlob } from "./download";

// ARGB; each export carries its client's brand colours (doc.colors), else these
let MUTED = "FF62574B";
let FAINT = "FF6F6456";
let HEADER_FILL = "FFEFE6D6";
let ACCENT = "FF17456E";
let RULE = "FFDCCFB9";
const argb = (hex: string) => `FF${hex.replace("#", "").toUpperCase()}`;
function applyColors(c: ExportColors | undefined) {
  if (!c) return;
  MUTED = argb(c.muted);
  FAINT = argb(c.faint);
  HEADER_FILL = argb(c.headerFill);
  ACCENT = argb(c.accent);
  RULE = argb(c.rule);
}
const MONEY_FMT = "#,##0.00";
const INT_FMT = "#,##0";

type XlsxCell = { v: string | number | null; numFmt?: string };

function toXlsx(c: Cell): XlsxCell {
  if (c.kind === "money") return { v: Number(c.value ?? 0) / 100, numFmt: MONEY_FMT };
  if (c.kind === "int") return { v: Number(c.value ?? 0), numFmt: INT_FMT };
  return { v: c.value == null ? "" : String(c.value) };
}

// Excel worksheet names: ≤31 chars, none of : \ / ? * [ ], and unique.
function sheetName(name: string, used: Set<string>): string {
  const base = (name.replace(/[:\\/?*[\]]/g, " ").trim().slice(0, 31) || "Sheet");
  let candidate = base;
  let i = 2;
  while (used.has(candidate.toLowerCase())) {
    const suffix = ` (${i++})`;
    candidate = base.slice(0, 31 - suffix.length) + suffix;
  }
  used.add(candidate.toLowerCase());
  return candidate;
}

function writeMeta(ws: ExcelJS.Worksheet, doc: ExportDoc, subtitle?: string) {
  ws.addRow([doc.venue]).font = { bold: true, size: 13 };
  ws.addRow([doc.scopeLabel]).font = { bold: true, size: 10, color: { argb: ACCENT } };
  ws.addRow([`${doc.reportTitle}${subtitle ? ` · ${subtitle}` : ""} — ${doc.rangeLabel}`]).font = {
    size: 10,
    color: { argb: MUTED },
  };
  ws.addRow([doc.generatedLabel]).font = { size: 9, color: { argb: FAINT } };
  for (const note of doc.notes ?? []) {
    ws.addRow([note]).font = { size: 9, italic: true, color: { argb: MUTED } };
  }
  ws.addRow([]);
}

function writeSection(ws: ExcelJS.Worksheet, doc: ExportDoc, s: Section) {
  writeMeta(ws, doc, s.note ? undefined : s.title);
  if (s.note) ws.addRow([s.note]).font = { size: 9, italic: true, color: { argb: MUTED } };

  const headerRowIndex = ws.rowCount + 1;
  const header = ws.addRow(s.columns.map((c) => c.header));
  header.font = { bold: true };
  header.eachCell((cell, col) => {
    cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: HEADER_FILL } };
    cell.border = { bottom: { style: "thin", color: { argb: ACCENT } } };
    cell.alignment = { horizontal: s.columns[col - 1]?.align ?? "left" };
  });

  for (const row of s.rows) {
    const xs = s.columns.map((c) => toXlsx(c.get(row)));
    const r = ws.addRow(xs.map((x) => x.v));
    xs.forEach((x, i) => {
      const cell = r.getCell(i + 1);
      if (x.numFmt) cell.numFmt = x.numFmt;
      cell.alignment = { horizontal: s.columns[i].align };
    });
  }

  if (s.total) {
    const xs = s.total.map(toXlsx);
    const r = ws.addRow(xs.map((x) => x.v));
    xs.forEach((x, i) => {
      const cell = r.getCell(i + 1);
      if (x.numFmt) cell.numFmt = x.numFmt;
      cell.font = { bold: true };
      cell.alignment = { horizontal: s.columns[i]?.align ?? "left" };
      cell.border = { top: { style: "thin", color: { argb: RULE } } };
    });
  }

  s.columns.forEach((c, i) => {
    ws.getColumn(i + 1).width = c.width;
  });
  ws.views = [{ state: "frozen", ySplit: headerRowIndex }];
}

function writeKpis(ws: ExcelJS.Worksheet, doc: ExportDoc) {
  writeMeta(ws, doc);
  for (const k of doc.kpis ?? []) {
    const r = ws.addRow([k.label, k.value]);
    r.getCell(1).font = { color: { argb: MUTED } };
    r.getCell(2).font = { bold: true };
    r.getCell(2).alignment = { horizontal: "right" };
  }
  ws.getColumn(1).width = 30;
  ws.getColumn(2).width = 20;
}

export function buildWorkbook(doc: ExportDoc, kpiSheetName: string): ExcelJS.Workbook {
  applyColors(doc.colors);
  const wb = new ExcelJS.Workbook();
  wb.creator = doc.producer ?? "";
  const used = new Set<string>();

  if (doc.kpis?.length) {
    writeKpis(wb.addWorksheet(sheetName(kpiSheetName, used)), doc);
  }
  for (const s of doc.sections) {
    writeSection(wb.addWorksheet(sheetName(s.title ?? doc.reportTitle, used)), doc, s);
  }
  // A report with no KPIs and no sections still needs one sheet.
  if (wb.worksheets.length === 0) writeMeta(wb.addWorksheet(sheetName(doc.reportTitle, used)), doc);
  return wb;
}

export async function downloadXlsx(doc: ExportDoc, kpiSheetName: string): Promise<void> {
  const buf = await buildWorkbook(doc, kpiSheetName).xlsx.writeBuffer();
  saveBlob(
    new Blob([buf], {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }),
    `${doc.filenameBase}.xlsx`
  );
}
