// Locale completeness: every portal string exists in every locale, is not
// blank, and keeps the English {placeholders}. (A missing Spanish or German key
// is also a compile error — messages.es.ts / messages.de.ts are
// Record<MsgKey, string>.) Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { messages, type MsgKey } from "./messages";
import { es } from "./messages.es";
import { de } from "./messages.de";
import { translate } from "./translate";
import { makeFmt } from "./format";
import { count, decimal, money, moneyCents } from "../format";

const keys = Object.keys(messages) as MsgKey[];
const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
const tables = { es, de };

for (const [lang, table] of Object.entries(tables)) {
  test(`${lang} has exactly the portal's keys`, () => {
    assert.deepEqual(Object.keys(table).sort(), [...keys].sort());
  });
}

test("no string is blank in any locale", () => {
  for (const k of keys) {
    assert.ok(messages[k].en.trim(), `en ${k}`);
    assert.ok(messages[k].fr.trim(), `fr ${k}`);
    assert.ok(es[k]?.trim(), `es ${k}`);
    assert.ok(de[k]?.trim(), `de ${k}`);
  }
});

test("every locale keeps the English placeholders", () => {
  for (const k of keys) {
    const want = placeholders(messages[k].en);
    assert.deepEqual(placeholders(messages[k].fr), want, `fr ${k}`);
    assert.deepEqual(placeholders(es[k]), want, `es ${k}`);
    assert.deepEqual(placeholders(de[k]), want, `de ${k}`);
  }
});

test("Spanish and German are translated, not English copied over", () => {
  // short labels can legitimately match ("PDF", "Stripe", "Total"); a long
  // sentence that is identical in both is a missed translation
  for (const [lang, table] of Object.entries(tables)) {
    const copied = keys.filter((k) => messages[k].en.length > 24 && table[k] === messages[k].en);
    assert.deepEqual(copied, [], lang);
  }
});

test("no client's name is baked into the portal strings", () => {
  for (const k of keys) {
    for (const s of [messages[k].en, messages[k].fr, es[k], de[k]]) {
      assert.doesNotMatch(s, /copper lantern|\blantern|\bsage\b|poppy|pronghorn/i, k);
    }
  }
});

test("translate fills placeholders per locale", () => {
  assert.equal(translate("es", "account_footer", { brand: "X", version: "1" }), es.account_footer.replace("{brand}", "X").replace("{version}", "1"));
  assert.equal(translate("de", "account_footer", { brand: "X", version: "1" }), "X Cloud-Portal · v1");
  assert.equal(translate("en", "account_footer", { brand: "X", version: "1" }), "X cloud portal · v1");
});

test("German numbers and dates: 1.234,56 and dd.MM.yyyy, the store's own currency", () => {
  const NBSP = " ";
  assert.equal(money(123456, "CAD", { locale: "de" }), `1.234,56${NBSP}$`);
  assert.equal(money(123400, "CAD", { locale: "de" }), `1.234${NBSP}$`);
  assert.equal(money(1299, "CAD", { locale: "de", unambiguous: true }), `12,99${NBSP}CA$`);
  assert.equal(moneyCents(-123400, "USD", { locale: "de" }), `-1.234,00${NBSP}$`);
  assert.equal(count(1234567, "de"), "1.234.567");
  assert.equal(decimal(9.975, 3, "de"), "9,975");
  const fmt = makeFmt("de", (k) => translate("de", k));
  assert.equal(fmt.dayYear("2026-01-08"), "08.01.2026");
  assert.equal(fmt.day("2026-01-08"), "08.01.");
  assert.equal(fmt.dateTime("2026-01-08T20:14:00.000-05:00"), "08.01. 20:14");
  assert.equal(fmt.rangeLabel({ from: "2026-01-08", to: "2026-01-08" } as never), "08.01.2026");
});
