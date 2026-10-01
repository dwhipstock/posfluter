// Red-team: a 200–500 character name with no spaces ("AAAA…") typed at the
// demo. A table cell's min-content width is the whole unbroken word, so a cell
// with no truncate / break class stretches its table to thousands of pixels
// and pushes the money columns off screen. Static check of the source: the
// failing test is the repro (see the file:line in the message).
// Run: npx tsx --test "lib/**/redteam-*.test.ts"
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(__dirname, "..");
const read = (p: string) => fs.readFileSync(path.join(root, p), "utf8");
const GUARD = /\b(truncate|break-all|break-words|line-clamp-\d|max-w-\[)/;

/** The line holding [needle] (and the line above it, for wrapper classes) must carry a wrap/clip guard. */
function guarded(file: string, needle: string): string | null {
  const lines = read(file).split("\n");
  const i = lines.findIndex((l) => l.includes(needle));
  assert.ok(i >= 0, `${file}: '${needle}' not found (the page changed; update this test)`);
  const ctx = lines.slice(Math.max(0, i - 1), i + 1).join("\n");
  return GUARD.test(ctx) ? null : `${file}:${i + 1}  ${lines[i].trim()}`;
}

test("user-typed names in report tables are truncated or allowed to break", () => {
  const unguarded = [
    guarded("app/(app)/reports/items/page.tsx", '<div className="text-sm font-medium">{name(r.nameFr, r.nameEn, r.names)}</div>'),
    guarded("app/(app)/reports/items/page.tsx", "{name(r.categoryNameFr, r.categoryNameEn, r.categoryNames)"),
    guarded("app/(app)/reports/tables/page.tsx", "{t.tableLabel}"),
    guarded("app/(app)/reports/tables/page.tsx", "{name(null, t.zoneNameEn, t.zoneNames)}"),
    guarded("app/(app)/reports/journal/page.tsx", "{row.tableLabel}"),
    guarded("app/(app)/reports/refunds/page.tsx", "{r.reason}</TableCell>"),
    guarded("app/(app)/reports/exceptions/page.tsx", "{v.voidedBy}"),
  ].filter(Boolean);
  assert.deepEqual(unguarded, []);
});

test("the long-word wrap fallback applies in every language, not only French", () => {
  const css = read("app/globals.css");
  // today the only overflow-wrap rule is scoped to :lang(fr)
  const global = /(^|\n)\s*(html|body|:root)[^{]*\{[^}]*overflow-wrap:\s*(break-word|anywhere)/.test(css);
  assert.ok(global, "overflow-wrap is only set under :lang(fr); English/es/de/af pages get no wrap fallback");
});
