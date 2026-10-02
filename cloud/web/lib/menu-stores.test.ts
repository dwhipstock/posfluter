// Edit item → Stores: ticks, category matching and the calls. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  canToggle,
  carryingEditable,
  categoriesFor,
  copySource,
  createOrigin,
  initialTicks,
  matchCategory,
  planStoreCalls,
  storeChanges,
} from "./menu-stores";
import type { MenuCategory, MenuItemStore } from "./types";

const cat = (id: string, nameEn: string, nameFr = nameEn): MenuCategory => ({ id, nameEn, nameFr, sortOrder: 0 });
const drinks = cat("drinks", "Drinks", "Boissons");

const stores: MenuItemStore[] = [
  { venueId: "glenwood", name: "Copper Lantern — Glenwood South", editable: true, pending: 0, carries: true, categoryId: "drinks", categories: [drinks, cat("food", "Food")] },
  { venueId: "express", name: "Copper Lantern — Express", editable: true, pending: 2, carries: false, categories: [cat("bev-x1", " drinks ", "Breuvages")] },
  { venueId: "plateau", name: "Copper Lantern — Plateau", editable: true, pending: 0, carries: false, categories: [cat("mains", "Mains", "Plats")] },
  { venueId: "old", name: "Copper Lantern — Old Town", editable: false, pending: 0, carries: false, categories: [drinks] },
];

test("the boxes start as the stores that carry it; a new item gets the picked store, or every store that takes edits", () => {
  assert.deepEqual(initialTicks(stores, false, null), { glenwood: true, express: false, plateau: false, old: false });
  assert.deepEqual(initialTicks(stores, false, "express"), { glenwood: true, express: false, plateau: false, old: false });
  assert.deepEqual(initialTicks(stores, true, "plateau"), { glenwood: false, express: false, plateau: true, old: false });
  assert.deepEqual(initialTicks(stores, true, null), { glenwood: true, express: true, plateau: true, old: false });
  assert.equal(canToggle(stores[3]), false); // too old to take edits: its box stays as it is
});

test("a category matches by id, else by English or French name; never invented", () => {
  assert.equal(matchCategory(stores[3], drinks, "drinks"), "drinks");
  assert.equal(matchCategory(stores[1], drinks, "drinks"), "bev-x1"); // " drinks " = "Drinks"
  assert.equal(matchCategory(stores[2], drinks, "drinks"), null);
  assert.equal(matchCategory(stores[2], undefined, "drinks"), null);
  assert.equal(matchCategory(stores[2], cat("x", "Other", "Plats"), "x"), "mains"); // the French name
});

test("ticking adds, unticking removes, nothing ticked removes it everywhere", () => {
  const c = storeChanges(stores, { glenwood: false, express: true, plateau: true, old: false });
  assert.deepEqual(c, { adds: ["express", "plateau"], removes: ["glenwood"], removesAll: false });
  assert.deepEqual(storeChanges(stores, { glenwood: true }), { adds: [], removes: [], removesAll: false });
  assert.equal(storeChanges(stores, { glenwood: false }).removesAll, true);
});

test("each new store's category: the pick, else the match; the rest are still to pick", () => {
  const r = categoriesFor(stores, ["express", "plateau"], {}, drinks, "drinks");
  assert.deepEqual(r, { byStore: { express: "bev-x1" }, missing: ["plateau"] });
  const picked = categoriesFor(stores, ["express", "plateau"], { plateau: "mains" }, drinks, "drinks");
  assert.deepEqual(picked, { byStore: { express: "bev-x1", plateau: "mains" }, missing: [] });
  // a pick that isn't one of that store's categories doesn't count
  assert.deepEqual(categoriesFor(stores, ["plateau"], { plateau: "drinks" }, drinks, "drinks").missing, ["plateau"]);
});

test("copies come from the picked store, else a carrying store that takes edits", () => {
  assert.equal(copySource(stores, "glenwood", "x"), "glenwood");
  assert.equal(copySource(stores, null, "x"), "glenwood");
  const oldOnly = stores.map((s) => ({ ...s, carries: s.venueId === "old" }));
  assert.equal(copySource(oldOnly, null, "x"), "old");
  assert.equal(copySource(stores.map((s) => ({ ...s, carries: false })), null, "x"), "x");
  assert.deepEqual(carryingEditable(stores).map((s) => s.venueId), ["glenwood"]);
});

test("the calls: one copy per new store with its category, then one scoped delete per store left", () => {
  const calls = planStoreCalls("iced tea/1", "glenwood", { express: "bev-x1", plateau: "mains" }, ["glenwood"]);
  assert.deepEqual(calls, [
    { method: "POST", path: "/v1/menu/items/iced%20tea%2F1/copy?venue=express", body: { from: "glenwood", categoryId: "bev-x1" }, venueId: "express" },
    { method: "POST", path: "/v1/menu/items/iced%20tea%2F1/copy?venue=plateau", body: { from: "glenwood", categoryId: "mains" }, venueId: "plateau" },
    { method: "DELETE", path: "/v1/menu/items/iced%20tea%2F1?venue=glenwood", venueId: "glenwood" },
  ]);
  assert.deepEqual(planStoreCalls("a", "b", {}, []), []);
});

test("a new item is made at the picked store when ticked, else the first ticked store that takes edits", () => {
  assert.equal(createOrigin(stores, ["glenwood", "express"], "express"), "express");
  assert.equal(createOrigin(stores, ["glenwood", "express"], "plateau"), "glenwood");
  assert.equal(createOrigin(stores, ["old", "plateau"], null), "plateau");
  assert.equal(createOrigin(stores, ["old"], null), null);
  assert.equal(createOrigin(stores, [], null), null);
});
