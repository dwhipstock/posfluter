// Money named in words follows the store's currency: the cash rounding note
// (nickel in dollars, whole baht in baht) and the AI examples' amounts.
// Dollar stores read exactly today's text.
import { test } from "node:test";
import assert from "node:assert/strict";
import { inCurrency, roundingNoteKey } from "./currency-words";
import { messages, type MsgKey } from "./i18n/messages";
import { th } from "./i18n/messages.th";

const fill = (s: string, vars: Record<string, string>) => s.replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? `{${k}}`);
const en = (k: MsgKey, cur: string) => {
  const c = inCurrency(k, cur);
  return fill(messages[c.key].en, c.vars);
};

test("cash rounding note: nickel for dollars, whole baht for THB, both when mixed", () => {
  assert.equal(roundingNoteKey(["USD"]), "cash_rounding_note");
  assert.equal(roundingNoteKey(["CAD", "USD"]), "cash_rounding_note");
  assert.equal(roundingNoteKey([]), "cash_rounding_note");
  assert.equal(roundingNoteKey(["THB"]), "cash_rounding_note_unit");
  assert.equal(roundingNoteKey(["thb"]), "cash_rounding_note_unit");
  assert.equal(roundingNoteKey(["USD", "THB"]), "cash_rounding_note_mixed");
  assert.match(messages.cash_rounding_note.en, /nearest 5¢/);
  assert.match(messages.cash_rounding_note_unit.en, /whole baht/);
  assert.doesNotMatch(messages.cash_rounding_note_unit.en, /¢/);
  assert.doesNotMatch(th.cash_rounding_note_unit, /¢/);
  assert.match(th.cash_rounding_note_unit, /บาทเต็ม/);
});

test("AI examples: dollar stores unchanged, a baht store quotes baht", () => {
  assert.equal(en("ai_placeholder", "USD"), messages.ai_placeholder.en);
  assert.equal(en("ai_placeholder", "CAD"), "For example: add a Caesar salad for $14 under Starters");
  assert.equal(en("ai_example_sales", "USD"), "Make my top 5 sellers $2 cheaper on Tuesdays");
  assert.equal(en("ai_example_1", "USD"), "Raise every beer by 50¢");
  assert.equal(en("ai_placeholder", "THB"), "For example: add a Caesar salad for ฿150 under Starters");
  assert.equal(en("ai_example_sales", "THB"), "Make my top 5 sellers ฿10 cheaper on Tuesdays");
  assert.equal(en("ai_example_1", "THB"), "Raise every beer by ฿5");
  assert.match(en("ai_sales_note_item_refused", "THB"), /or ฿0 or less/);
  assert.match(en("ai_sales_note_item_refused", "USD"), /or \$0 or less/);
  // a message without an amount passes through
  assert.deepEqual(inCurrency("save", "THB"), { key: "save", vars: {} });
  // the Thai twins carry the same placeholders
  assert.match(th.ai_placeholder_cur, /\{price\}/);
  assert.match(th.ai_example_sales_cur, /\{off\}/);
});
