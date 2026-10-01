// Menu editor helpers: price parsing, validation and the edit plan. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  centsToInput,
  createCall,
  draftFromItem,
  emptyDraft,
  extraLangs,
  moveInOrder,
  namesDiff,
  parseCents,
  planEdit,
  validateDraft,
} from "./menu-edit";
import type { MenuItem } from "./types";

const item: MenuItem = {
  id: "nachos",
  nameFr: "Nachos",
  nameEn: "Nachos",
  descriptionFr: "",
  descriptionEn: "Loaded",
  categoryId: "food",
  abbrev: "NA",
  isAlcohol: false,
  active: true,
  photoVersion: null,
  venueId: "plateau",
  names: { es: "Nachos con queso" },
  variants: [
    { id: "nachos:half", labelFr: "Demi", labelEn: "Half", priceCents: 900, sortOrder: 0 },
    { id: "nachos:full", labelFr: "Pleine", labelEn: "Full", priceCents: 1500, sortOrder: 1 },
  ],
};

test("parseCents reads dollars exactly, no floating point", () => {
  assert.equal(parseCents("12"), 1200);
  assert.equal(parseCents("12.5"), 1250);
  assert.equal(parseCents("12.50"), 1250);
  assert.equal(parseCents("$1,234.56"), 123456);
  assert.equal(parseCents(" 0.07 "), 7);
  assert.equal(parseCents(".99"), 99);
  assert.equal(parseCents("0"), 0);
  assert.equal(parseCents("19.99"), 1999); // 19.99 * 100 is 1998.9999… in floats
  for (const bad of ["", "abc", "-1", "1.234", "1,23", "12.3.4", "100000.01", "$"]) {
    assert.equal(parseCents(bad), null, bad);
  }
});

test("centsToInput round-trips", () => {
  assert.equal(centsToInput(1250), "12.50");
  assert.equal(centsToInput(7), "0.07");
  assert.equal(parseCents(centsToInput(123456)), 123456);
});

test("validation: name, category, sizes and prices", () => {
  const d = emptyDraft("food", "Regular");
  assert.deepEqual(validateDraft(d).sort(), ["name_required", "price_invalid"].sort());
  d.nameEn = "Poutine";
  d.variants[0].price = "11";
  assert.deepEqual(validateDraft(d), []);
  d.variants = [];
  assert.deepEqual(validateDraft(d), ["size_required"]);
});

test("an untouched item plans no calls (even with gaps in the stored sort orders)", () => {
  assert.deepEqual(planEdit(item, draftFromItem(item)), []);
  const gappy = { ...item, variants: item.variants.map((v, i) => ({ ...v, sortOrder: i * 5 })) };
  assert.deepEqual(planEdit(gappy, draftFromItem(gappy)), []);
});

test("reordering sizes sends their new positions", () => {
  const d = draftFromItem(item);
  d.variants.reverse();
  assert.deepEqual(planEdit(item, d).map((c) => c.body), [{ sortOrder: 0 }, { sortOrder: 1 }]);
});

test("only changed fields go in the item PATCH; names diff removes with empty text", () => {
  const d = draftFromItem(item);
  d.nameEn = "  Big Nachos ";
  d.active = false;
  d.names = { de: "Nachos" };
  const calls = planEdit(item, d);
  assert.deepEqual(calls, [
    {
      method: "PATCH",
      path: "/v1/menu/items/nachos",
      // nameFr unchanged; an emptied French name is not sent (it would fall back to English)
      body: { nameEn: "Big Nachos", active: false, names: { de: "Nachos", es: "" } },
    },
  ]);
});

test("sizes: add, reprice, and delete last", () => {
  const d = draftFromItem(item);
  d.variants[0].price = "9.50";
  d.variants.splice(1, 1); // remove "Full"
  d.variants.push({ labelEn: "Party", labelFr: "", price: "30", names: {} });
  const calls = planEdit(item, d);
  assert.deepEqual(calls, [
    { method: "PATCH", path: "/v1/menu/items/nachos/variants/nachos%3Ahalf", body: { priceCents: 950 } },
    { method: "POST", path: "/v1/menu/items/nachos/variants", body: { labelEn: "Party", labelFr: "Party", priceCents: 3000, names: {} } },
    { method: "DELETE", path: "/v1/menu/items/nachos/variants/nachos%3Afull" },
  ]);
});

test("create sends French = English when left blank", () => {
  const d = emptyDraft("food", "Regular");
  d.nameEn = "Poutine";
  d.variants[0].price = "$11.00";
  const c = createCall(d);
  assert.equal(c.path, "/v1/menu/items");
  assert.equal(c.body?.nameFr, "Poutine");
  assert.deepEqual(c.body?.variants, [{ labelEn: "Regular", labelFr: "Regular", priceCents: 1100, names: {} }]);
});

test("namesDiff, moveInOrder and extraLangs", () => {
  assert.equal(namesDiff({ es: "a" }, { es: " a " }), null);
  assert.deepEqual(namesDiff({}, { es: "b", de: "" }), { es: "b" });
  assert.deepEqual(moveInOrder(["a", "b", "c"], 1, -1), ["b", "a", "c"]);
  assert.deepEqual(moveInOrder(["a", "b", "c"], 2, 1), ["a", "b", "c"]);
  assert.deepEqual(extraLangs(["en", "fr", "es"], { de: "x" }), ["es", "de"]);
});
