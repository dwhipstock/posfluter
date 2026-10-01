// CSV export: formula injection (red-team). Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildCsv, neutralize } from "./csv";
import { col, type ExportDoc } from "./doc";

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

test("a text cell that starts like a formula gets a leading apostrophe", () => {
  for (const v of ['=HYPERLINK("http://x","y")', "+1+1", "-2+3+cmd|' /C calc'!A0", "@SUM(1)", "\t=1", "\r=1"]) {
    assert.equal(neutralize(v), `'${v}`, JSON.stringify(v));
  }
  for (const v of ["Fish & chips", "-5.00", "12", "0.01", "", "a=b"]) assert.equal(neutralize(v), v);
});

test("no cell of the file starts with = + - @ tab or CR, except plain numbers", () => {
  const csv = buildCsv(
    docWith([{ name: "=1+1", cents: -500 }, { name: "@x", cents: 100 }], '=HYPERLINK("http://evil.example","Open")'),
  );
  // naive split is enough: every dangerous cell above is quoted or single-line
  const cells = csv.split("\r\n").flatMap((l) => l.split(",")).map((c) => c.replace(/^"/, ""));
  const dangerous = cells.filter((c) => /^[=+\-@\t\r]/.test(c) && !/^-?\d+(\.\d+)?$/.test(c));
  assert.deepEqual(dangerous, []);
  assert.ok(csv.includes("-5.00"), "money stays a plain decimal");
});
