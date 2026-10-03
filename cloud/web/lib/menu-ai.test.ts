// The AI assistant's pure helpers: the voice clip's WAV, the checklist's ticking rules, error messages.
import { test } from "node:test";
import assert from "node:assert/strict";
import { aiErrorKey, allTicked, basisItems, clock, encodeWav, liftKey, salesHeadline, salesNoteKey, signedPct, tickedIds, toggleChange, toMono, VOICE_RATE } from "./menu-ai";
import { messages } from "./i18n/messages";
import type { AiChange, AiSalesBasis } from "./types";

test("a stereo 48 kHz recording becomes 16 kHz mono", () => {
  const left = new Float32Array(48_000).fill(0.5);
  const right = new Float32Array(48_000).fill(-0.5);
  const mono = toMono([left, right], 48_000);
  assert.equal(mono.length, VOICE_RATE);
  assert.ok(mono.every((s) => Math.abs(s) < 1e-6));
  assert.equal(toMono([new Float32Array(16_000).fill(0.25)], 16_000).length, 16_000);
  assert.equal(toMono([], 44_100).length, 0);
});

test("the WAV header says 16 kHz mono 16-bit PCM", () => {
  const wav = encodeWav(new Float32Array([0, 1, -1, 2]));
  const v = new DataView(wav.buffer);
  const ascii = (at: number) => String.fromCharCode(...wav.slice(at, at + 4));
  assert.equal(ascii(0), "RIFF");
  assert.equal(ascii(8), "WAVE");
  assert.equal(ascii(36), "data");
  assert.equal(v.getUint16(20, true), 1);
  assert.equal(v.getUint16(22, true), 1);
  assert.equal(v.getUint32(24, true), 16_000);
  assert.equal(v.getUint16(34, true), 16);
  assert.equal(v.getUint32(40, true), 8);
  assert.equal(wav.length, 44 + 8);
  assert.equal(v.getInt16(46, true), 0x7fff);
  assert.equal(v.getInt16(48, true), -0x8000);
  assert.equal(v.getInt16(50, true), 0x7fff); // clipped
  // 30 s of speech stays well under the cloud's 5 MB cap
  assert.ok(encodeWav(new Float32Array(30 * VOICE_RATE)).length < 1_000_000);
});

const changes: AiChange[] = [
  { id: "c1", kind: "add_category", title: "Desserts", details: [] },
  { id: "c2", kind: "add_item", title: "Brownie", details: [], needs: "c1" },
  { id: "c3", kind: "update_item", title: "Poutine", details: [] },
];

test("ticking an item ticks the new category it needs; unticking the category unticks the item", () => {
  let t = allTicked(changes);
  assert.deepEqual(tickedIds(t, changes), ["c1", "c2", "c3"]);
  t = toggleChange(t, changes, "c1");
  assert.deepEqual(tickedIds(t, changes), ["c3"]);
  t = toggleChange(t, changes, "c2");
  assert.deepEqual(tickedIds(t, changes), ["c1", "c2", "c3"]);
  t = toggleChange(t, changes, "c3");
  assert.deepEqual(tickedIds(t, changes), ["c1", "c2"]);
});

test("every AI error code maps to a real message", () => {
  for (const code of ["menu_ai_too_many", "menu_ai_daily_limit", "menu_ai_disabled", "menu_ai_expired", "menu_ai_timeout",
    "menu_ai_audio_type", "network", "menu_ai_already_reverted"]) {
    const k = aiErrorKey(code);
    assert.ok(k && messages[k], code);
  }
  assert.equal(aiErrorKey("store_not_upgraded"), null);
});

test("the recording clock", () => {
  assert.equal(clock(0), "0:00");
  assert.equal(clock(7.9), "0:07");
  assert.equal(clock(30), "0:30");
});

const basis: AiSalesBasis = {
  rank: "top",
  by: "units",
  n: 5,
  from: "2026-09-03",
  to: "2026-10-02",
  store: "Copper Lantern — Glenwood South",
  currency: "USD",
  rows: [
    { itemId: "lager", name: "Lantern House Lager", units: 412, revenueMinor: 339_900 },
    { itemId: "burger", name: "Copper Burger", units: 1388, revenueMinor: 1_728_060 },
  ],
};

test("a sales basis line lists the server's numbers, never more than asked", () => {
  const units = (n: number) => n.toLocaleString("en-US");
  const money = (m: number) => `$${(m / 100).toFixed(2)}`;
  assert.equal(basisItems(basis, units, money), "Lantern House Lager (412), Copper Burger (1,388)");
  assert.equal(basisItems({ ...basis, by: "revenue" }, units, money), "Lantern House Lager ($3399.00), Copper Burger ($17280.60)");
  assert.equal(basisItems(basis, units, money, 1), "Lantern House Lager (412), …");
  assert.equal(basisItems({ ...basis, rank: "unsold" }, units, money), "Lantern House Lager, Copper Burger");
});

test("every sales headline and note has a message", () => {
  const all: AiSalesBasis[] = [
    basis,
    { ...basis, by: "revenue" },
    { ...basis, rank: "bottom" },
    { ...basis, rank: "unsold", days: 14 },
    { ...basis, rank: "list" },
  ];
  for (const b of all) assert.ok(messages[salesHeadline(b).key], b.rank);
  assert.deepEqual(salesHeadline({ ...basis, rank: "unsold", days: 14 }).vars, { days: 14 });
  for (const code of ["no_sales", "fewer_items", "pick_corrected", "size_refused", "already_lower", "item_skipped", "too_many", "none_match", "bad_request"]) {
    const k = salesNoteKey({ code });
    assert.ok(k && messages[k], code);
  }
  assert.equal(salesNoteKey({ code: "size_refused", item: "Lager", size: "pitcher" }), "ai_sales_note_size_refused");
  assert.equal(salesNoteKey({ code: "something_new" }), null);
});

test("how the specials are doing: lifts, baselines and the notes", () => {
  assert.equal(signedPct(140), "+140%");
  assert.equal(signedPct(-5), "−5%");
  assert.equal(signedPct(0), "0%");
  assert.equal(liftKey("weekdays"), "ai_sales_lift_weekdays");
  assert.equal(liftKey("weekend"), "ai_sales_lift_weekend");
  assert.equal(liftKey("other_days"), "ai_sales_lift_other");
  const specials: AiSalesBasis = {
    ...basis,
    rank: "specials",
    rows: [
      { itemId: "burger", name: "Copper Burger", units: 328, revenueMinor: 0, special: "Tue", liftPct: 140, enough: true },
      { itemId: "poutine", name: "Poutine", units: 0, revenueMinor: 0, special: "Sun", enough: false },
    ],
  };
  assert.equal(basisItems(specials, String, String), "Copper Burger (Tue): +140%, Poutine (Sun): –");
  for (const b of [specials, { ...basis, rank: "happy_hour" as const }]) assert.ok(messages[salesHeadline(b).key], b.rank);
  for (const code of ["no_specials", "no_happy_hour", "all_working", "assumes_whole_period"]) {
    const k = salesNoteKey({ code });
    assert.ok(k && messages[k], code);
  }
});
