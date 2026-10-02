// Pure helpers for Edit item → Stores (components/menu-stores.tsx): which
// stores carry the item, what ticking and unticking means, the category each
// new store puts it in, and the calls that do it. Every call goes through the
// portal's ordinary menu edits (CONTRACT §10): a copy is
// POST /v1/menu/items/{id}/copy?venue=<store>, a removal the ordinary
// DELETE /v1/menu/items/{id}?venue=<store>. No React, no fetch:
// lib/menu-stores.test.ts covers it.
import type { PlannedCall } from "./menu-edit";
import type { MenuCategory, MenuItemStore } from "./types";

export type Ticks = Record<string, boolean>;

/** What the boxes start as: the stores that carry it; for a new item the picked store, or every store that takes edits. */
export function initialTicks(stores: MenuItemStore[], isNew: boolean, storeId: string | null): Ticks {
  return Object.fromEntries(
    stores.map((s) => [s.venueId, isNew ? (storeId ? s.venueId === storeId : s.editable) : s.carries])
  );
}

/** A store's box can change: it takes portal edits (a too-old store keeps what it has). */
export function canToggle(s: MenuItemStore): boolean {
  return s.editable;
}

const norm = (s: string | undefined) => (s ?? "").trim().toLocaleLowerCase();

/**
 * The category at [target] that matches [source]: the same id, else the same
 * English or French name. Null: none — the manager picks one (never invented).
 */
export function matchCategory(target: MenuItemStore, source: MenuCategory | undefined, sourceId: string): string | null {
  if (target.categories.some((c) => c.id === sourceId)) return sourceId;
  if (!source) return null;
  const en = norm(source.nameEn);
  const fr = norm(source.nameFr);
  const hit = target.categories.find((c) => (en && norm(c.nameEn) === en) || (fr && norm(c.nameFr) === fr));
  return hit?.id ?? null;
}

export interface StoreChanges {
  /** Stores to put the item on (ticked, don't carry it), in the stores' order. */
  adds: string[];
  /** Stores to take it off (unticked, carry it). */
  removes: string[];
  /** Nothing ticked any more: the item goes from every store (the Delete item confirm). */
  removesAll: boolean;
}

export function storeChanges(stores: MenuItemStore[], ticks: Ticks): StoreChanges {
  const adds = stores.filter((s) => ticks[s.venueId] && !s.carries).map((s) => s.venueId);
  const removes = stores.filter((s) => !ticks[s.venueId] && s.carries).map((s) => s.venueId);
  const removesAll = stores.some((s) => s.carries) && !stores.some((s) => ticks[s.venueId]);
  return { adds, removes, removesAll };
}

/** Each new store's category: the manager's pick there, else the match. Missing = the stores still to pick. */
export function categoriesFor(
  stores: MenuItemStore[],
  venueIds: string[],
  picked: Record<string, string>,
  source: MenuCategory | undefined,
  sourceId: string
): { byStore: Record<string, string>; missing: string[] } {
  const byStore: Record<string, string> = {};
  const missing: string[] = [];
  for (const id of venueIds) {
    const s = stores.find((x) => x.venueId === id);
    if (!s) continue;
    const pick = picked[id];
    const c = pick && s.categories.some((x) => x.id === pick) ? pick : matchCategory(s, source, sourceId);
    if (c) byStore[id] = c;
    else missing.push(id);
  }
  return { byStore, missing };
}

/**
 * The store whose copy is copied: the picked store (it carries the item, and
 * the edit just saved went there), else the first carrying store that takes
 * edits (so it has the edit too), else the first carrying one.
 */
export function copySource(stores: MenuItemStore[], storeId: string | null, fallback: string): string {
  if (storeId) return storeId;
  const carrying = stores.filter((s) => s.carries);
  return (carrying.find((s) => s.editable) ?? carrying[0])?.venueId ?? fallback;
}

const enc = encodeURIComponent;

/** Copies first (from a store that may be about to lose it), then removals. Each call is one store's. */
export function planStoreCalls(itemId: string, from: string, adds: Record<string, string>, removes: string[]): PlannedCall[] {
  const base = `/v1/menu/items/${enc(itemId)}`;
  return [
    ...Object.entries(adds).map(([venueId, categoryId]) => ({
      method: "POST" as const,
      path: `${base}/copy?venue=${enc(venueId)}`,
      body: { from, categoryId },
      venueId,
    })),
    ...removes.map((venueId) => ({ method: "DELETE" as const, path: `${base}?venue=${enc(venueId)}`, venueId })),
  ];
}

/**
 * A new item on [ticked] stores: made at one of them (the picked store when
 * ticked, else the first), copied to the rest. Null when none takes edits.
 */
export function createOrigin(stores: MenuItemStore[], ticked: string[], storeId: string | null): string | null {
  const ok = stores.filter((s) => ticked.includes(s.venueId) && s.editable).map((s) => s.venueId);
  if (storeId && ok.includes(storeId)) return storeId;
  return ok[0] ?? null;
}

/** The stores an item-wide change reaches: those that carry it and take edits. */
export function carryingEditable(stores: MenuItemStore[]): MenuItemStore[] {
  return stores.filter((s) => s.carries && s.editable);
}
