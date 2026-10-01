// Red-team: money must read "$1,234.56" (North American) in every portal
// language, at every edge value. Failing tests are the repro of a bug.
// Run: npx tsx --test "lib/**/redteam-*.test.ts"
import { test } from "node:test";
import assert from "node:assert/strict";
import * as React from "react";
import { renderToString } from "react-dom/server";
import type { Locale } from "./i18n/context";
import type { FuelFmt } from "./fuel";
import { cad, money, moneyCents } from "./format";
import { parseCents } from "./menu-edit";

const LOCALES: Locale[] = ["en", "fr", "es", "de", "af"];

const h = React.createElement;
// context.tsx is compiled with the classic JSX runtime under tsx: it needs a global React
(globalThis as unknown as { React: typeof React }).React = React;
async function fuelFmtIn(locale: Locale): Promise<FuelFmt> {
  const { LocaleProvider } = await import("./i18n/context");
  const { useFuelFmt } = await import("./fuel");
  let out: FuelFmt | null = null;
  function Probe() {
    out = useFuelFmt();
    return null;
  }
  renderToString(h(LocaleProvider, { initialLocale: locale, available: LOCALES }, h(Probe)));
  return out!;
}

test("fuel margin in cents per gallon is North American in every language (30.0¢/gal, not 30,0¢/gal)", async () => {
  const bad: string[] = [];
  for (const l of LOCALES) {
    const s = (await fuelFmtIn(l)).perGallon(305); // 305 mills = 30.5¢
    if (!/^30\.5\s?¢/.test(s.replace(/ | /g, " "))) bad.push(`${l}: ${s}`);
  }
  assert.deepEqual(bad, []);
});

test("money: fractional cents never print as two decimal points", () => {
  // the API sends integer cents today; a computed average reaching money()
  // un-rounded prints "$12.34.5"
  assert.match(money(1234.5), /^\$12\.3[45]$/);
  assert.match(cad(1234.5), /^\$12\.3[45]$/);
});

// --- held up -------------------------------------------------------------

test("money (held up): edge values in every language", () => {
  for (const l of LOCALES) {
    assert.equal(money(0, "CAD", { locale: l }), "$0", l);
    assert.equal(moneyCents(0, "CAD", { locale: l }), "$0.00", l);
    assert.equal(money(9_999_999, "CAD", { locale: l }), "$99,999.99", l);
    assert.equal(moneyCents(-123456, "CAD", { locale: l }), "-$1,234.56", l);
    assert.equal(money(1, "USD", { locale: l, unambiguous: true }), "US$0.01", l);
    assert.equal(money(123_456_789_012, "CAD", { locale: l }), "$1,234,567,890.12", l);
    assert.equal(money(-5, "CAD", { locale: l }), "-$0.05", l);
  }
});

test("menu price input (held up): hostile strings are refused, edges accepted", () => {
  for (const s of ["", " ", ".", "-5", "1e3", "12,50", "１２", "NaN", "Infinity", "0x10", "100000.01", "1.234", "$-1", "<b>1</b>"]) {
    assert.equal(parseCents(s), null, JSON.stringify(s));
  }
  assert.equal(parseCents("0"), 0);
  assert.equal(parseCents("$99,999.99"), 9_999_999);
  assert.equal(parseCents("100,000.00"), 10_000_000);
  assert.equal(parseCents(".5"), 50);
});
