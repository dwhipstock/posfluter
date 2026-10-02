// Pure helpers for the portal's menu editor (two-way menu sync, CONTRACT §10):
// a form draft, its validation, the price parser, and the plan — the fewest
// API calls that turn the item as loaded into the item as edited. No React,
// no fetch: lib/menu-edit.test.ts covers it.
import type { MenuItem, MenuVariant } from "./types";
import {
  availableDaysValue,
  loadedSpecials,
  normalizeDays,
  sameValue,
  specialDraftOf,
  specialsValue,
  validateSpecials,
  type SpecialDraft,
} from "./menu-specials";

export interface VariantDraft {
  /** The size's id; absent for a size added in this edit. */
  id?: string;
  labelEn: string;
  labelFr: string;
  /** What the manager typed, in dollars ("12.50"); parsed with [parseCents]. */
  price: string;
  names: Record<string, string>;
}

export interface ItemDraft {
  nameEn: string;
  nameFr: string;
  /** Extra-language names (es, de, af…); "" = none. */
  names: Record<string, string>;
  descriptionEn: string;
  descriptionFr: string;
  categoryId: string;
  /** On sale (false = 86'd). */
  active: boolean;
  isAlcohol: boolean;
  variants: VariantDraft[];
  /** Sold only on these days; [] = every day. */
  availableDays: string[];
  /** Day prices (an existing item only: they are keyed by size id). */
  specials: SpecialDraft[];
}

export type DraftError = "name_required" | "category_required" | "size_required" | "size_label_required" | "price_invalid" | "specials_invalid";

export interface PlannedCall {
  method: "POST" | "PATCH" | "DELETE";
  /** API path without the store scope (the caller adds `?venue=`). */
  path: string;
  body?: Record<string, unknown>;
  /**
   * One store's call (Edit item → Stores): the path names the store, and a
   * refusal there is that store's skip — the other stores' calls still run.
   */
  venueId?: string;
}

const MAX_CENTS = 10_000_000;

/**
 * "$1,234.56" / "12.5" / "12" → integer cents; null when it isn't a
 * non-negative amount with at most two decimals (or is over $100,000).
 * No floating point: the digits are joined as text.
 */
export function parseCents(input: string): number | null {
  const s = input.trim().replace(/^\$/, "").replace(/,(?=\d{3}(\D|$))/g, "").trim();
  const m = /^(\d*)(?:\.(\d{0,2}))?$/.exec(s);
  if (!m || (m[1] === "" && !m[2])) return null;
  const whole = m[1] || "0";
  const frac = (m[2] ?? "").padEnd(2, "0");
  const cents = Number(whole) * 100 + Number(frac);
  if (!Number.isSafeInteger(cents) || cents > MAX_CENTS) return null;
  return cents;
}

/** Cents → the editable text ("12.50"), no grouping. */
export function centsToInput(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const a = Math.abs(Math.trunc(cents));
  return `${sign}${Math.floor(a / 100)}.${String(a % 100).padStart(2, "0")}`;
}

const trimNames = (names: Record<string, string> | undefined): Record<string, string> =>
  Object.fromEntries(Object.entries(names ?? {}).map(([k, v]) => [k, (v ?? "").trim()]).filter(([, v]) => v !== ""));

export function draftFromItem(item: MenuItem): ItemDraft {
  return {
    nameEn: item.nameEn,
    nameFr: item.nameFr,
    names: { ...(item.names ?? {}) },
    descriptionEn: item.descriptionEn ?? "",
    descriptionFr: item.descriptionFr ?? "",
    categoryId: item.categoryId,
    active: item.active,
    isAlcohol: item.isAlcohol,
    variants: [...item.variants]
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((v) => ({
        id: v.id,
        labelEn: v.labelEn,
        labelFr: v.labelFr,
        price: centsToInput(v.priceCents),
        names: { ...(v.names ?? {}) },
      })),
    availableDays: normalizeDays(item.availableDays),
    specials: (item.specials ?? []).map(specialDraftOf),
  };
}

/** The draft's sizes that already exist (a special price needs a size id). */
export function savedSizeIds(d: ItemDraft): string[] {
  return d.variants.map((v) => v.id).filter((id): id is string => !!id);
}

export function emptyDraft(categoryId: string, defaultSize: string): ItemDraft {
  return {
    nameEn: "",
    nameFr: "",
    names: {},
    descriptionEn: "",
    descriptionFr: "",
    categoryId,
    active: true,
    isAlcohol: false,
    variants: [{ labelEn: defaultSize, labelFr: "", price: "", names: {} }],
    availableDays: [],
    specials: [],
  };
}

/** What stops a save (empty = fine). */
export function validateDraft(d: ItemDraft): DraftError[] {
  const out: DraftError[] = [];
  if (!d.nameEn.trim()) out.push("name_required");
  if (!d.categoryId) out.push("category_required");
  if (d.variants.length === 0) out.push("size_required");
  if (d.variants.some((v) => !v.labelEn.trim())) out.push("size_label_required");
  if (d.variants.some((v) => parseCents(v.price) === null)) out.push("price_invalid");
  if (validateSpecials(d.specials, savedSizeIds(d)).some((e) => e.length > 0)) out.push("specials_invalid");
  return out;
}

/** Names that changed: a new or edited text, or "" for one that was removed. */
export function namesDiff(before: Record<string, string> | undefined, after: Record<string, string>): Record<string, string> | null {
  const b = trimNames(before);
  const a = trimNames(after);
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(a)) if (b[k] !== v) out[k] = v;
  for (const k of Object.keys(b)) if (!(k in a)) out[k] = "";
  return Object.keys(out).length ? out : null;
}

const enc = encodeURIComponent;

/** The create call for a new item. */
export function createCall(d: ItemDraft): PlannedCall {
  const nameEn = d.nameEn.trim();
  return {
    method: "POST",
    path: "/v1/menu/items",
    body: {
      nameEn,
      nameFr: d.nameFr.trim() || nameEn,
      names: trimNames(d.names),
      descriptionEn: d.descriptionEn.trim(),
      descriptionFr: d.descriptionFr.trim(),
      categoryId: d.categoryId,
      active: d.active,
      isAlcohol: d.isAlcohol,
      variants: d.variants.map((v) => ({
        labelEn: v.labelEn.trim(),
        labelFr: v.labelFr.trim() || v.labelEn.trim(),
        priceCents: parseCents(v.price) ?? 0,
        names: trimNames(v.names),
      })),
    },
  };
}

/**
 * The fewest calls that turn [item] into [d]: one item PATCH with only the
 * changed fields, then new sizes, edited sizes, and removed sizes last (so
 * the item never has none). Assumes [validateDraft] passed.
 */
export function planEdit(item: MenuItem, d: ItemDraft): PlannedCall[] {
  const calls: PlannedCall[] = [];
  const base = `/v1/menu/items/${enc(item.id)}`;
  const patch: Record<string, unknown> = {};
  const nameEn = d.nameEn.trim();
  const nameFr = d.nameFr.trim() || nameEn;
  if (nameEn !== item.nameEn) patch.nameEn = nameEn;
  if (nameFr !== item.nameFr) patch.nameFr = nameFr;
  if (d.descriptionEn.trim() !== (item.descriptionEn ?? "")) patch.descriptionEn = d.descriptionEn.trim();
  if (d.descriptionFr.trim() !== (item.descriptionFr ?? "")) patch.descriptionFr = d.descriptionFr.trim();
  if (d.categoryId !== item.categoryId) patch.categoryId = d.categoryId;
  if (d.active !== item.active) patch.active = d.active;
  if (d.isAlcohol !== item.isAlcohol) patch.isAlcohol = d.isAlcohol;
  const names = namesDiff(item.names, d.names);
  if (names) patch.names = names;
  // specials: each list is one value, sent whole when it changed
  const days = availableDaysValue(d.availableDays);
  if (!sameValue(days, availableDaysValue(item.availableDays ?? []))) patch.availableDays = days;
  const specials = specialsValue(d.specials, savedSizeIds(d));
  if (!sameValue(specials, loadedSpecials(item.specials))) patch.specials = specials;
  if (Object.keys(patch).length) calls.push({ method: "PATCH", path: base, body: patch });

  const byId = new Map<string, MenuVariant>(item.variants.map((v) => [v.id, v]));
  const kept = new Set<string>();
  // sort orders are only sent when the sizes were actually reordered (stored
  // orders may have gaps: 0, 2, 5 is fine as it is)
  const origOrder = [...item.variants].sort((a, b) => a.sortOrder - b.sortOrder).map((v) => v.id);
  const draftOrder = d.variants.map((v) => v.id).filter((id): id is string => !!id && byId.has(id));
  const keptOrig = origOrder.filter((id) => draftOrder.includes(id));
  const reordered = keptOrig.some((id, i) => draftOrder[i] !== id);
  d.variants.forEach((v, index) => {
    const labelEn = v.labelEn.trim();
    const labelFr = v.labelFr.trim() || labelEn;
    const priceCents = parseCents(v.price) ?? 0;
    const orig = v.id ? byId.get(v.id) : undefined;
    if (!orig) {
      calls.push({ method: "POST", path: `${base}/variants`, body: { labelEn, labelFr, priceCents, names: trimNames(v.names) } });
      return;
    }
    kept.add(orig.id);
    const body: Record<string, unknown> = {};
    if (labelEn !== orig.labelEn) body.labelEn = labelEn;
    if (labelFr !== orig.labelFr) body.labelFr = labelFr;
    if (priceCents !== orig.priceCents) body.priceCents = priceCents;
    if (reordered && index !== orig.sortOrder) body.sortOrder = index;
    const vn = namesDiff(orig.names, v.names);
    if (vn) body.names = vn;
    if (Object.keys(body).length) calls.push({ method: "PATCH", path: `${base}/variants/${enc(orig.id)}`, body });
  });
  for (const v of item.variants) {
    if (!kept.has(v.id)) calls.push({ method: "DELETE", path: `${base}/variants/${enc(v.id)}` });
  }
  return calls;
}

/** [ids] with the one at [index] moved one place up (-1) or down (+1); unchanged at the ends. */
export function moveInOrder(ids: string[], index: number, dir: -1 | 1): string[] {
  const to = index + dir;
  if (index < 0 || index >= ids.length || to < 0 || to >= ids.length) return ids;
  const out = [...ids];
  [out[index], out[to]] = [out[to], out[index]];
  return out;
}

/** The extra-language name fields to show: the portal's languages beyond en/fr, plus any the thing already has. */
export function extraLangs(available: string[], ...existing: (Record<string, string> | undefined)[]): string[] {
  const set = new Set(available.filter((l) => l !== "en" && l !== "fr"));
  for (const names of existing) for (const k of Object.keys(names ?? {})) if (k !== "en" && k !== "fr") set.add(k);
  return [...set];
}
