// Locale completeness: every portal string exists in every locale, is not
// blank, and keeps the English {placeholders}. (A missing Spanish, German or
// Afrikaans key is also a compile error — messages.es.ts / .de.ts / .af.ts are
// Record<MsgKey, string>.) Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { messages, type MsgKey } from "./messages";
import { es } from "./messages.es";
import { de } from "./messages.de";
import { af } from "./messages.af";
import { pickName, translate } from "./translate";
import { makeFmt } from "./format";
import { count, decimal, money, moneyCents } from "../format";

const keys = Object.keys(messages) as MsgKey[];
const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
const tables = { es, de, af };

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
    assert.ok(af[k]?.trim(), `af ${k}`);
  }
});

test("every locale keeps the English placeholders", () => {
  for (const k of keys) {
    const want = placeholders(messages[k].en);
    assert.deepEqual(placeholders(messages[k].fr), want, `fr ${k}`);
    assert.deepEqual(placeholders(es[k]), want, `es ${k}`);
    assert.deepEqual(placeholders(de[k]), want, `de ${k}`);
    assert.deepEqual(placeholders(af[k]), want, `af ${k}`);
  }
});

test("Spanish, German and Afrikaans are translated, not English copied over", () => {
  // short labels can legitimately match ("PDF", "Stripe", "Total"); a long
  // sentence that is identical in both is a missed translation
  for (const [lang, table] of Object.entries(tables)) {
    const copied = keys.filter((k) => messages[k].en.length > 24 && table[k] === messages[k].en);
    assert.deepEqual(copied, [], lang);
  }
});

test("no client's name is baked into the portal strings", () => {
  for (const k of keys) {
    for (const s of [messages[k].en, messages[k].fr, es[k], de[k], af[k]]) {
      assert.doesNotMatch(s, /copper lantern|\blantern|\bsage\b|poppy|pronghorn/i, k);
    }
  }
});

test("translate fills placeholders per locale", () => {
  assert.equal(translate("es", "account_footer", { brand: "X", version: "1" }), es.account_footer.replace("{brand}", "X").replace("{version}", "1"));
  assert.equal(translate("de", "account_footer", { brand: "X", version: "1" }), "X Cloud-Portal · v1");
  assert.equal(translate("en", "account_footer", { brand: "X", version: "1" }), "X cloud portal · v1");
});

test("German numbers and dates: 1.234 and dd.MM.yyyy; money stays North American", () => {
  assert.equal(money(123456, "CAD", { locale: "de" }), "$1,234.56");
  assert.equal(money(123400, "CAD", { locale: "de" }), "$1,234");
  assert.equal(money(1299, "CAD", { locale: "de", unambiguous: true }), "CA$12.99");
  assert.equal(moneyCents(-123400, "USD", { locale: "de" }), "-$1,234.00");
  assert.equal(money(123456, "CAD", { locale: "fr" }), "$1,234.56");
  assert.equal(money(123456, "CAD", { locale: "fr", short: true }), "$1.2k");
  assert.equal(moneyCents(-500, "CAD", { locale: "fr" }), "-$5.00");
  assert.equal(count(1234567, "de"), "1.234.567");
  assert.equal(decimal(9.975, 3, "de"), "9,975");
  const fmt = makeFmt("de", (k) => translate("de", k));
  assert.equal(fmt.dayYear("2026-01-08"), "08.01.2026");
  assert.equal(fmt.day("2026-01-08"), "08.01.");
  assert.equal(fmt.dateTime("2026-01-08T20:14:00.000-05:00"), "08.01. 20:14");
  assert.equal(fmt.rangeLabel({ from: "2026-01-08", to: "2026-01-08" } as never), "08.01.2026");
});

test("catalog names: the store's own es/de name, else English, else French", () => {
  const names = { es: "Cerveza", de: "Bier" };
  assert.equal(pickName("de", "Bière", "Beer", names), "Bier");
  assert.equal(pickName("es", "Bière", "Beer", names), "Cerveza");
  assert.equal(pickName("en", "Bière", "Beer", names), "Beer");
  assert.equal(pickName("fr", "Bière", "Beer", names), "Bière");
  // blank, missing or absent names fall back to English, then French
  assert.equal(pickName("de", "Bière", "Beer", { de: "  " }), "Beer");
  assert.equal(pickName("de", "Bière", "Beer", {}), "Beer");
  assert.equal(pickName("es", "Bière", "Beer"), "Beer");
  assert.equal(pickName("de", "Bière", "", null), "Bière");
  assert.equal(pickName("fr", "", "Beer", names), "Beer");
  assert.equal(pickName("de", null, null), "");
});

test("product counts follow the portal language, not the browser's", () => {
  const n = count(5234, "de");
  assert.equal(translate("de", "catalog_products", { n }), "5.234 Produkte");
  assert.equal(translate("en", "catalog_products", { n: count(5234, "en") }), "5,234 products");
  assert.equal(translate("fr", "catalog_products", { n: count(5234, "fr") }), "5 234 produits");
  assert.equal(
    translate("de", "catalog_range", { from: count(1, "de"), to: count(100, "de"), total: count(5234, "de") }),
    "1–100 von 5.234"
  );
});

test("Afrikaans: every key translated; money, counts and decimals stay North American", () => {
  // every portal key has its own Afrikaans string (not the English fallback)
  const missing = keys.filter((k) => !Object.prototype.hasOwnProperty.call(af, k));
  assert.deepEqual(missing, []);
  for (const k of keys) assert.equal(translate("af", k), af[k], k);
  // the article is ’n (U+2019), never a straight apostrophe
  const straight = keys.filter((k) => /'n\b/.test(af[k]));
  assert.deepEqual(straight, []);
  // no rand, no space or comma grouping: exactly what English shows
  assert.equal(money(123456, "CAD", { locale: "af" }), "$1,234.56");
  assert.equal(money(123400, "CAD", { locale: "af" }), "$1,234");
  assert.equal(money(1299, "CAD", { locale: "af", unambiguous: true }), "CA$12.99");
  assert.equal(money(123456, "CAD", { locale: "af", short: true }), "$1.2k");
  assert.equal(moneyCents(-123400, "USD", { locale: "af" }), "-$1,234.00");
  assert.equal(count(1234567, "af"), "1,234,567");
  assert.equal(decimal(9.975, 3, "af"), "9.975");
  assert.equal(translate("af", "catalog_products", { n: count(5234, "af") }), "5,234 produkte");
  assert.equal(
    translate("af", "catalog_range", { from: count(1, "af"), to: count(100, "af"), total: count(5234, "af") }),
    "1–100 van 5,234"
  );
  // dates: Afrikaans month abbreviations, day first
  const fmt = makeFmt("af", (k) => translate("af", k));
  assert.equal(fmt.dayYear("2026-03-08"), "8 Mrt. 2026");
  assert.equal(fmt.day("2026-10-08"), "8 Okt.");
  assert.equal(fmt.dateTime("2026-05-08T20:14:00.000-05:00"), "8 Mei 20:14");
  assert.equal(fmt.rangeLabel({ from: "2026-01-08", to: "2026-01-08" } as never), "8 Jan. 2026");
});

test("Afrikaans catalog names: the store's own af name, else English, else French", () => {
  assert.equal(pickName("af", "Bière", "Beer", { af: "Bier", de: "Bier" }), "Bier");
  assert.equal(pickName("af", "Bière", "Beer", { es: "Cerveza", de: "Bier" }), "Beer");
  assert.equal(pickName("af", "Bière", "Beer", { af: " " }), "Beer");
  assert.equal(pickName("af", "Bière", "Beer"), "Beer");
  assert.equal(pickName("af", "Bière", "", null), "Bière");
  assert.equal(pickName("af", null, null), "");
});
