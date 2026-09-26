// PDF export via pdfmake. The whole module is lazy-imported on export click, so
// pdfmake and its bundled Latin font never touch the initial bundle.

import type {
  Content,
  TableCell,
  TDocumentDefinitions,
  CustomTableLayout,
} from "pdfmake/interfaces";
import type { Cell, ExportColors, ExportDoc, Kpi, Section } from "./doc";
import { moneyCents } from "@/lib/format";

// the defaults; each export carries its client's brand colours (doc.colors)
const DEFAULT_COLORS: ExportColors = {
  text: "#1C2733",
  accent: "#17456E",
  muted: "#62574B",
  faint: "#6F6456",
  headerFill: "#EFE6D6",
  rule: "#DCCFB9",
};
let INK = DEFAULT_COLORS.text;
let ACCENT = DEFAULT_COLORS.accent;
let MUTED = DEFAULT_COLORS.muted;
let FAINT = DEFAULT_COLORS.faint;
let HEADER_FILL = DEFAULT_COLORS.headerFill;
let LINE = DEFAULT_COLORS.rule;
let RULE = DEFAULT_COLORS.rule;
const CONTENT_WIDTH = 515; // A4 (595pt) minus 40pt margins each side

function moneyStr(cents: number, currency = "CAD", unambiguous = false, approximate = false): string {
  return `${approximate ? "≈ " : ""}${moneyCents(cents, currency, { unambiguous, locale: docLocale })}`;
}

// set per render from the doc (one PDF at a time)
let unambiguousDollars = false;
let docLocale: "fr" | "en" = "en";

function cellText(c: Cell): string {
  if (c.kind === "money") return moneyStr(Number(c.value ?? 0), c.currency, unambiguousDollars, c.approximate);
  if (c.kind === "int") return Number(c.value ?? 0).toLocaleString("en-US").replace(/,/g, docLocale === "fr" ? "\u00A0" : ",");
  return String(c.value ?? "");
}

function tableLayout(hasTotal: boolean): CustomTableLayout {
  return {
    hLineWidth: (i, node) => {
      const last = node.table.body.length;
      if (i === 0 || i === last) return 0;
      if (i === 1) return 1.2; // accent rule under the header
      if (hasTotal && i === last - 1) return 0.8; // rule above the totals row
      return 0.4;
    },
    vLineWidth: () => 0,
    hLineColor: (i, node) => {
      if (i === 1) return ACCENT;
      if (hasTotal && i === node.table.body.length - 1) return RULE;
      return LINE;
    },
    fillColor: (rowIndex) => (rowIndex === 0 ? HEADER_FILL : null),
    paddingTop: () => 4,
    paddingBottom: () => 4,
    paddingLeft: () => 6,
    paddingRight: () => 6,
  };
}

function sectionContent(s: Section): Content[] {
  const out: Content[] = [];
  if (s.title) out.push({ text: s.title, style: "sectionTitle", margin: [0, 14, 0, s.note ? 1 : 6] });
  if (s.note) out.push({ text: s.note, style: "note", margin: [0, 0, 0, 6] });

  const header: TableCell[] = s.columns.map((c) => ({
    text: c.header,
    style: "fr",
    alignment: c.align,
  }));
  const body: TableCell[][] = s.rows.map((row) =>
    s.columns.map((c) => ({ text: cellText(c.get(row)), style: "td", alignment: c.align }))
  );
  const rows: TableCell[][] = [header, ...body];
  if (s.total) {
    rows.push(
      s.total.map((cell, i) => ({
        text: cellText(cell),
        style: "tot",
        alignment: s.columns[i]?.align ?? "left",
      }))
    );
  }

  // Text columns stretch; numeric columns stay compact and right-aligned.
  const widths = s.columns.map((c) => (c.align === "right" ? "auto" : "*"));
  out.push({
    table: { headerRows: 1, widths, body: rows },
    layout: tableLayout(Boolean(s.total)),
  });
  return out;
}

function kpiContent(kpis: Kpi[]): Content[] {
  const out: Content[] = [];
  // Up to four KPIs per band; each band is a borderless label/value table.
  for (let i = 0; i < kpis.length; i += 4) {
    const band = kpis.slice(i, i + 4);
    const widths = band.map(() => "*");
    out.push({
      table: {
        widths,
        body: [
          band.map((k) => ({ text: k.label, style: "kpiLabel" })),
          band.map((k) => ({ text: k.value, style: "kpiValue" })),
        ],
      },
      layout: "noBorders",
      margin: [0, i === 0 ? 4 : 6, 0, 0],
    });
  }
  return out;
}

export function buildDocDefinition(doc: ExportDoc): TDocumentDefinitions {
  unambiguousDollars = !!doc.multiCurrency;
  const c = { ...DEFAULT_COLORS, ...doc.colors };
  INK = c.text;
  ACCENT = c.accent;
  MUTED = c.muted;
  FAINT = c.faint;
  HEADER_FILL = c.headerFill;
  LINE = c.rule;
  RULE = c.rule;
  docLocale = doc.locale === "fr" ? "fr" : "en";
  const content: Content[] = [
    { text: doc.venue || " ", style: "venue" },
    {
      columns: [
        { text: `${doc.reportTitle} · ${doc.scopeLabel}`, style: "reportTitle" },
        { text: doc.rangeLabel, style: "rangeLabel", alignment: "right" },
      ],
      columnGap: 12,
      margin: [0, 2, 0, 0],
    },
    {
      canvas: [
        { type: "line", x1: 0, y1: 0, x2: CONTENT_WIDTH, y2: 0, lineWidth: 2, lineColor: ACCENT },
      ],
      margin: [0, 6, 0, 6],
    },
  ];

  if (doc.notes?.length) {
    for (const n of doc.notes) content.push({ text: n, style: "note", margin: [0, 0, 0, 2] });
  }
  if (doc.kpis?.length) content.push(...kpiContent(doc.kpis));
  for (const s of doc.sections) content.push(...sectionContent(s));

  return {
    pageSize: "A4",
    pageMargins: [40, 44, 40, 52],
    defaultStyle: { font: "Roboto", fontSize: 9, color: INK, lineHeight: 1.15 },
    info: {
      title: `${doc.reportTitle} · ${doc.scopeLabel} — ${doc.rangeLabel}`,
      ...(doc.producer ? { creator: doc.producer, producer: doc.producer } : {}),
    },
    footer: (currentPage, pageCount) => ({
      margin: [40, 8, 40, 0],
      columns: [
        { text: doc.generatedLabel, style: "foot" },
        { text: `${currentPage} / ${pageCount}`, alignment: "right", style: "foot" },
      ],
    }),
    content,
    styles: {
      venue: { fontSize: 15, bold: true, color: INK },
      reportTitle: { fontSize: 12, bold: true, color: INK },
      rangeLabel: { fontSize: 9, color: MUTED, margin: [0, 3, 0, 0] },
      note: { fontSize: 8, color: MUTED, italics: true },
      sectionTitle: { fontSize: 10.5, bold: true, color: ACCENT },
      fr: { fontSize: 8.5, bold: true, color: INK },
      td: { fontSize: 9, color: INK },
      tot: { fontSize: 9, bold: true, color: INK },
      kpiLabel: { fontSize: 7.5, color: FAINT, characterSpacing: 0.3 },
      kpiValue: { fontSize: 13, bold: true, color: INK, margin: [0, 1, 0, 0] },
      foot: { fontSize: 7.5, color: FAINT },
    },
  };
}

let configured = false;
async function getPdfMake() {
  const mod = await import("pdfmake/build/pdfmake");
  const fontsMod = await import("pdfmake/build/vfs_fonts");
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const pdfMake: any = (mod as any).default ?? mod;
  if (!configured) {
    const bundledVfs: any = (fontsMod as any).default ?? fontsMod;
    pdfMake.vfs = bundledVfs.pdfMake?.vfs ?? bundledVfs;
    pdfMake.fonts = {
      Roboto: {
        normal: "Roboto-Regular.ttf",
        bold: "Roboto-Medium.ttf",
        italics: "Roboto-Italic.ttf",
        bolditalics: "Roboto-MediumItalic.ttf",
      },
    };
    configured = true;
  }
  return pdfMake;
}

export async function downloadPdf(doc: ExportDoc): Promise<void> {
  const pdfMake = await getPdfMake();
  await new Promise<void>((resolve) => {
    pdfMake.createPdf(buildDocDefinition(doc)).download(`${doc.filenameBase}.pdf`, () => resolve());
  });
}
