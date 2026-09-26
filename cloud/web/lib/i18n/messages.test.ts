// Locale completeness: every portal string exists in every locale, is not
// blank, and keeps the English {placeholders}. (A missing Spanish key is also a
// compile error — messages.es.ts is a Record<MsgKey, string>.) Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { messages, type MsgKey } from "./messages";
import { es } from "./messages.es";
import { translate } from "./translate";

const keys = Object.keys(messages) as MsgKey[];
const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

test("Spanish has exactly the portal's keys", () => {
  const esKeys = Object.keys(es).sort();
  assert.deepEqual(esKeys, [...keys].sort());
});

test("no string is blank in any locale", () => {
  for (const k of keys) {
    assert.ok(messages[k].en.trim(), `en ${k}`);
    assert.ok(messages[k].fr.trim(), `fr ${k}`);
    assert.ok(es[k]?.trim(), `es ${k}`);
  }
});

test("every locale keeps the English placeholders", () => {
  for (const k of keys) {
    const want = placeholders(messages[k].en);
    assert.deepEqual(placeholders(messages[k].fr), want, `fr ${k}`);
    assert.deepEqual(placeholders(es[k]), want, `es ${k}`);
  }
});

test("Spanish is translated, not English copied over", () => {
  // short labels can legitimately match ("PDF", "Stripe", "Total"); a long
  // sentence that is identical in both is a missed translation
  const copied = keys.filter((k) => messages[k].en.length > 24 && es[k] === messages[k].en);
  assert.deepEqual(copied, []);
});

test("no client's name is baked into the portal strings", () => {
  for (const k of keys) {
    for (const s of [messages[k].en, messages[k].fr, es[k]]) {
      assert.doesNotMatch(s, /copper lantern|\blantern|\bsage\b|poppy/i, k);
    }
  }
});

test("translate fills placeholders per locale", () => {
  assert.equal(translate("es", "account_footer", { brand: "X", version: "1" }), es.account_footer.replace("{brand}", "X").replace("{version}", "1"));
  assert.equal(translate("en", "account_footer", { brand: "X", version: "1" }), "X cloud portal · v1");
});
