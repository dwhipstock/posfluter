// A THB store: "฿1,234" — baht sign first, comma thousands, satang only when
// non-zero — everywhere (cards, short chart axes, PDF tables). CAD / USD must
// read exactly as before.
import { test } from "node:test";
import assert from "node:assert/strict";
import { currencySymbol, money, moneyCents } from "./format";
import { parseCents } from "./menu-edit";

test("THB money(): satang only when non-zero", () => {
  assert.equal(money(123400, "THB"), "฿1,234");
  assert.equal(money(123450, "THB"), "฿1,234.50");
  assert.equal(money(-500, "THB"), "-฿5");
  assert.equal(money(0, "THB"), "฿0");
  assert.equal(money(5, "THB"), "฿0.05");
  assert.equal(money(123400, "thb", { unambiguous: true }), "฿1,234");
  for (const locale of ["fr", "de", "es", "af"] as const) assert.equal(money(123450, "THB", { locale }), "฿1,234.50", locale);
});

test("THB short form (chart axes)", () => {
  assert.equal(money(123400, "THB", { short: true }), "฿1.2k");
  assert.equal(money(4_500_000, "THB", { short: true }), "฿45k");
  assert.equal(money(250_000_000, "THB", { short: true }), "฿2.5M");
  assert.equal(money(-50_000, "THB", { short: true }), "-฿500");
});

test("THB moneyCents() (PDF tables): satang only when non-zero", () => {
  assert.equal(moneyCents(123400, "THB"), "฿1,234");
  assert.equal(moneyCents(123450, "THB"), "฿1,234.50");
  assert.equal(moneyCents(-500, "THB"), "-฿5");
  assert.equal(moneyCents(0, "THB"), "฿0");
  assert.equal(moneyCents(5, "THB"), "฿0.05");
});

test("currencySymbol: THB is ฿, with or without unambiguous", () => {
  assert.equal(currencySymbol("THB"), "฿");
  assert.equal(currencySymbol("THB", true), "฿");
  assert.equal(currencySymbol("thb"), "฿");
});

test("CAD / USD / other unchanged", () => {
  assert.equal(money(123400, "CAD"), "$1,234");
  assert.equal(money(123450, "USD"), "$1,234.50");
  assert.equal(money(123450, "USD", { unambiguous: true }), "US$1,234.50");
  assert.equal(money(123450, "CAD", { unambiguous: true }), "CA$1,234.50");
  assert.equal(money(123400, "CAD", { short: true }), "$1.2k");
  assert.equal(moneyCents(123400, "CAD"), "$1,234.00");
  assert.equal(moneyCents(-500, "USD"), "-$5.00");
  assert.equal(moneyCents(0, "USD", { unambiguous: true }), "US$0.00");
  assert.equal(moneyCents(123400, "EUR"), "€1,234.00");
  assert.equal(currencySymbol("CAD"), "$");
  assert.equal(currencySymbol("USD", true), "US$");
  assert.equal(currencySymbol("JPY"), "JPY ");
});

test("price boxes accept a leading baht sign", () => {
  assert.equal(parseCents("฿1,234"), 123400);
  assert.equal(parseCents("฿12.50"), 1250);
  assert.equal(parseCents("$12.99"), 1299);
});
