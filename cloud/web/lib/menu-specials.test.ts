// Menu specials in the portal editor: validation, the canonical form both
// sides of menu sync compare, and the item PATCH it plans. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  availableDaysValue,
  canonicalSpecial,
  daysSummary,
  normalizeDays,
  normalizeTime,
  notCheaper,
  specialsValue,
  validateSpecials,
  type SpecialDraft,
} from "./menu-specials";
import { draftFromItem, planEdit, validateDraft } from "./menu-edit";
import type { MenuItem } from "./types";

const beer: MenuItem = {
  id: "lantern-lager",
  nameFr: "Lager",
  nameEn: "Lager",
  categoryId: "beer",
  abbrev: "LL",
  isAlcohol: true,
  active: true,
  photoVersion: null,
  venueId: "vieux-port",
  variants: [
    { id: "lantern-lager:pint", labelFr: "Pinte", labelEn: "Pint", priceCents: 750, sortOrder: 0 },
    { id: "lantern-lager:pitcher", labelFr: "Pichet", labelEn: "Pitcher", priceCents: 2025, sortOrder: 1 },
  ],
};
const sizes = beer.variants.map((v) => v.id);

const happy: SpecialDraft = {
  days: ["fri", "mon", "wed", "tue", "thu"],
  from: "16:00",
  to: "18:00",
  label: "  Happy   hour ",
  prices: { "lantern-lager:pint": "5", "lantern-lager:pitcher": "" },
};

test("days come out distinct, Monday first; all seven is every day", () => {
  assert.deepEqual(normalizeDays(["sat", "FRI", "fri", "xyz"]), ["fri", "sat"]);
  assert.deepEqual(availableDaysValue(["mon", "tue", "wed", "thu", "fri", "sat", "sun"]), []);
  assert.deepEqual(availableDaysValue([]), []);
});

test("times are HH:mm, zero-padded", () => {
  assert.equal(normalizeTime("9:05"), "09:05");
  assert.equal(normalizeTime(""), "");
  assert.equal(normalizeTime("25:00"), null);
  assert.equal(normalizeTime("4pm"), null);
});

test("the canonical special is the contract's JSON text, key for key", () => {
  assert.equal(
    JSON.stringify(canonicalSpecial(happy, sizes)),
    '{"days":["mon","tue","wed","thu","fri"],"from":"16:00","to":"18:00","label":"Happy hour","prices":{"lantern-lager:pint":500}}'
  );
  const tuesday: SpecialDraft = { days: ["tue"], from: "", to: "", label: "", prices: { "lantern-lager:pitcher": "15.95", "lantern-lager:pint": "6" } };
  assert.equal(JSON.stringify(canonicalSpecial(tuesday, sizes)), '{"days":["tue"],"prices":{"lantern-lager:pint":600,"lantern-lager:pitcher":1595}}');
});

test("a special needs a day and a price; times both or neither, never equal", () => {
  const errs = (p: Partial<SpecialDraft>) => validateSpecials([{ ...happy, ...p }], sizes)[0];
  assert.deepEqual(errs({}), []);
  assert.deepEqual(errs({ days: [] }), ["special_day_required"]);
  assert.deepEqual(errs({ prices: {} }), ["special_price_required"]);
  assert.deepEqual(errs({ prices: { "lantern-lager:pint": "abc" } }), ["special_price_invalid"]);
  assert.deepEqual(errs({ to: "" }), ["special_time_both"]);
  assert.deepEqual(errs({ to: "16:00" }), ["special_time_same"]);
  assert.deepEqual(errs({ from: "", to: "" }), []);
  // a price for a size the item doesn't have is not a price
  assert.deepEqual(errs({ prices: { "other:size": "5" } }), ["special_price_required"]);
});

test("a special price not below the menu price is only a warning", () => {
  assert.deepEqual(notCheaper({ ...happy, prices: { "lantern-lager:pint": "7.50" } }, { "lantern-lager:pint": 750 }), ["lantern-lager:pint"]);
  assert.deepEqual(notCheaper(happy, { "lantern-lager:pint": 750 }), []);
  assert.deepEqual(validateSpecials([{ ...happy, prices: { "lantern-lager:pint": "9" } }], sizes)[0], []);
});

test("duplicates and price-less specials are dropped from what is sent", () => {
  assert.equal(specialsValue([happy, { ...happy }], sizes).length, 1);
});

test("days read in the reader's language", () => {
  const short: Record<string, string> = { mon: "Mon", tue: "Tue", wed: "Wed", thu: "Thu", fri: "Fri", sat: "Sat", sun: "Sun" };
  const name = (d: string) => short[d];
  assert.equal(daysSummary(["sat", "fri"], name, " & ", "Every day"), "Fri & Sat");
  assert.equal(daysSummary(["mon", "tue", "wed", "thu", "fri"], name, " & ", "Every day"), "Mon–Fri");
  assert.equal(daysSummary(["mon", "wed", "sun"], name, " & ", "Every day"), "Mon, Wed & Sun");
  assert.equal(daysSummary([], name, " & ", "Every day"), "Every day");
});

test("the item PATCH carries the days and specials only when they changed", () => {
  const loaded: MenuItem = {
    ...beer,
    availableDays: ["fri", "sat"],
    specials: [{ days: ["mon", "tue", "wed", "thu", "fri"], from: "16:00", to: "18:00", label: "Happy hour", prices: { "lantern-lager:pint": 500 } }],
  };
  const d = draftFromItem(loaded);
  assert.deepEqual(validateDraft(d), []);
  assert.deepEqual(planEdit(loaded, d), [], "nothing changed: no calls");

  const edited = { ...d, availableDays: [], specials: [...d.specials, { days: ["tue"], from: "", to: "", label: "", prices: { "lantern-lager:pitcher": "15" } }] };
  const calls = planEdit(loaded, edited);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].body, {
    availableDays: [],
    specials: [
      { days: ["mon", "tue", "wed", "thu", "fri"], from: "16:00", to: "18:00", label: "Happy hour", prices: { "lantern-lager:pint": 500 } },
      { days: ["tue"], prices: { "lantern-lager:pitcher": 1500 } },
    ],
  });

  // clearing every special sends []
  assert.deepEqual(planEdit(loaded, { ...d, specials: [] })[0].body, { specials: [] });
  // an invalid special blocks the save
  assert.deepEqual(validateDraft({ ...d, specials: [{ days: [], from: "", to: "", label: "", prices: {} }] }), ["specials_invalid"]);
});

test("a size removed in the same edit loses its special price", () => {
  const loaded: MenuItem = { ...beer, specials: [{ days: ["tue"], prices: { "lantern-lager:pint": 600, "lantern-lager:pitcher": 1500 } }] };
  const d = draftFromItem(loaded);
  const noPitcher = { ...d, variants: d.variants.filter((v) => v.id !== "lantern-lager:pitcher") };
  const patch = planEdit(loaded, noPitcher).find((c) => c.method === "PATCH" && c.path === "/v1/menu/items/lantern-lager");
  assert.deepEqual(patch?.body, { specials: [{ days: ["tue"], prices: { "lantern-lager:pint": 600 } }] });
});
