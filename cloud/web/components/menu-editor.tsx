"use client";

// Menu editing in the manager portal (two-way menu sync, CONTRACT §10). Each
// save is planned as the fewest API calls (lib/menu-edit.ts); every call
// carries an Idempotency-Key, so a retried request changes nothing twice.
// The store picker decides the scope: one store, or "All stores" (every
// store that carries the item).
import { useMemo, useState } from "react";
import { AlertTriangle, ArrowDown, ArrowUp, Clock, Plus, Trash2 } from "lucide-react";
import { ApiError, del, patch, post, put } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import {
  createCall,
  draftFromItem,
  emptyDraft,
  extraLangs,
  moveInOrder,
  namesDiff,
  planEdit,
  validateDraft,
  type DraftError,
  type ItemDraft,
  type PlannedCall,
} from "@/lib/menu-edit";
import {
  carryingEditable,
  categoriesFor,
  copySource,
  createOrigin,
  initialTicks,
  matchCategory,
  planStoreCalls,
  storeChanges,
  type Ticks,
} from "@/lib/menu-stores";
import { scopeApiPath, shortStoreName, useStores } from "@/lib/store";
import { toast } from "@/lib/toast";
import type { MenuCategory, MenuEditResult, MenuItem, MenuItemStores, MenuSyncStatus } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";
import { ItemPhotoPanel } from "@/components/ai-photo";
import { useAiStatus } from "@/components/menu-ai";
import { SpecialsSection } from "@/components/menu-specials";
import { StoresSection } from "@/components/menu-stores";

type T = ReturnType<typeof useT>;

/** A fresh idempotency key (randomUUID needs a secure context; the LAN portal may not be one). */
export function newEditKey(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  const b = new Uint8Array(16);
  c?.getRandomValues?.(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("") || String(Date.now() + Math.random());
}

/** Whether this user may edit, and each in-scope store's sync state. */
export function useMenuEditing() {
  const { data, mutate } = useApi<MenuSyncStatus>("/v1/menu/sync-status");
  const canEdit = !!data && data.canEdit && data.stores.some((s) => s.editable);
  return { status: data, canEdit, refreshStatus: mutate };
}

const SKIP_KEY: Record<string, MsgKey> = {
  store_not_upgraded: "menu_skip_store_not_upgraded",
  not_found: "menu_skip_not_found",
  category_not_found: "menu_skip_category_not_found",
  last_variant: "menu_skip_last_variant",
  category_not_empty: "menu_skip_category_not_empty",
  already_on_menu: "menu_skip_already_on_menu",
};

function skipMessage(t: T, store: string, reason: string): string {
  const k = SKIP_KEY[reason];
  return k ? t(k, { store }) : t("menu_skip_other", { store, reason });
}

/** A refusal (nothing applied) in plain words; the API's own message otherwise. */
function refusalMessage(t: T, e: unknown, storeName: string): string {
  if (e instanceof ApiError) {
    if (e.code === "category_not_empty") return t("menu_cat_not_empty");
    if (SKIP_KEY[e.code]) return skipMessage(t, storeName, e.code);
    return e.message;
  }
  return t("something_wrong");
}

/**
 * Send [calls] in order, scoped to the picked store, each with its own
 * idempotency key from [key]. Stops at the first refusal — except a call for
 * one named store ([PlannedCall.venueId], Edit item → Stores), whose refusal
 * for a known reason is that store's skip, as in "All stores" mode. Returns
 * the stores some call skipped.
 */
async function runCalls(calls: PlannedCall[], storeId: string | null, key: string): Promise<MenuEditResult["skipped"]> {
  const skipped: MenuEditResult["skipped"] = [];
  const add = (s: MenuEditResult["skipped"][number]) => {
    if (!skipped.some((x) => x.venueId === s.venueId && x.reason === s.reason)) skipped.push(s);
  };
  for (const [i, c] of calls.entries()) {
    const path = scopeApiPath(c.path, storeId);
    const headers = { "Idempotency-Key": `${key}:${i}` };
    try {
      const r =
        c.method === "POST"
          ? await post<MenuEditResult>(path, c.body ?? {}, headers)
          : c.method === "PATCH"
            ? await patch<MenuEditResult>(path, c.body ?? {}, headers)
            : await del<MenuEditResult>(path, headers);
      for (const s of r?.skipped ?? []) add(s);
    } catch (e) {
      if (c.venueId && e instanceof ApiError && SKIP_KEY[e.code]) add({ venueId: c.venueId, reason: e.code });
      else throw e;
    }
  }
  return skipped;
}

function useSkipToast() {
  const t = useT();
  const { nameOf } = useStores();
  return (okKey: MsgKey, skipped: MenuEditResult["skipped"]) => {
    if (skipped.length === 0) toast("success", t(okKey));
    else toast("info", t("menu_not_everywhere", { list: skipped.map((s) => skipMessage(t, nameOf(s.venueId), s.reason)).join(" · ") }));
  };
}

function useScopeLine(): string {
  const t = useT();
  const { store, storeId } = useStores();
  return storeId ? t("menu_scope_one", { store: shortStoreName(store?.name ?? storeId) }) : t("menu_scope_all");
}

function langLabel(t: T, lang: string): string {
  const k = `menu_lang_${lang}` as MsgKey;
  const v = t(k);
  return v === k ? lang.toUpperCase() : v;
}

/** Stores that are offline with edits waiting, and stores too old to take edits. */
export function MenuSyncBanner({ status }: { status: MenuSyncStatus | undefined }) {
  const t = useT();
  if (!status || !status.canEdit) return null;
  const lines = status.stores.flatMap((s) => {
    const store = shortStoreName(s.name);
    if (!s.editable) return [{ warn: true, text: t("menu_store_outdated", { store }) }];
    const out: { warn: boolean; text: string }[] = [];
    if (s.failed && s.failed > 0) out.push({ warn: true, text: t("menu_apply_failed", { store, n: s.failed }) });
    if (s.pending > 0) out.push({ warn: false, text: t("menu_pending", { store, n: s.pending }) });
    return out;
  });
  if (lines.length === 0) return null;
  return (
    <div className="space-y-1 rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-900">
      {lines.map((l, i) => (
        <p key={i} className="flex items-start gap-2">
          {l.warn ? <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" /> : <Clock className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
          {l.text}
        </p>
      ))}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs text-neutral-500">{label}</Label>
      {children}
    </div>
  );
}

const ERR_KEY: Record<DraftError, MsgKey> = {
  name_required: "menu_err_name_required",
  category_required: "menu_err_category_required",
  size_required: "menu_err_size_required",
  size_label_required: "menu_err_size_label_required",
  price_invalid: "menu_err_price_invalid",
  specials_invalid: "menu_err_specials_invalid",
};

/** Create (item = null) or edit one item. */
export function ItemSheet({
  open,
  onOpenChange,
  item,
  categories,
  onSaved,
  onPhotoChanged,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  item: MenuItem | null;
  categories: MenuCategory[];
  onSaved: () => void;
  /** An AI photo was used or undone (the sheet stays open). */
  onPhotoChanged?: () => void;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent>
        {open && (
          <ItemForm
            key={item?.id ?? "new"}
            item={item}
            categories={categories}
            onPhotoChanged={onPhotoChanged ?? onSaved}
            onDone={() => {
              onOpenChange(false);
              onSaved();
            }}
          />
        )}
      </SheetContent>
    </Sheet>
  );
}

function ItemForm({
  item,
  categories,
  onDone,
  onPhotoChanged,
}: {
  item: MenuItem | null;
  categories: MenuCategory[];
  onDone: () => void;
  onPhotoChanged: () => void;
}) {
  const t = useT();
  const { available, name } = useI18n();
  const { storeId, nameOf, venues } = useStores();
  const ai = useAiStatus();
  const scopeLine = useScopeLine();
  const showSkips = useSkipToast();
  const sorted = useMemo(() => [...categories].sort((a, b) => a.sortOrder - b.sortOrder), [categories]);
  const [draft, setDraft] = useState<ItemDraft>(() =>
    item ? draftFromItem(item) : emptyDraft(sorted[0]?.id ?? "", t("menu_default_size"))
  );
  const [errors, setErrors] = useState<DraftError[]>([]);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [key] = useState(newEditKey);
  const langs = extraLangs(available, item?.names, ...(item?.variants.map((v) => v.names) ?? []));

  // Stores: every store of the client, whatever store is picked (a client with one store has no section)
  const multiStore = venues.length > 1;
  const { data: storeData, isValidating: storesLoading, error: storesError } = useApi<MenuItemStores>(
    multiStore ? `/v1/menu/stores${item ? `?item=${encodeURIComponent(item.id)}` : ""}` : null
  );
  const storeList = storeData?.stores;
  // only the boxes the manager changed are kept: the rest follow the latest list (a cached list
  // refreshed under the sheet must never turn into a removal nobody asked for)
  const [touched, setTouched] = useState<Ticks>({});
  const ticks: Ticks | null = storeList ? { ...initialTicks(storeList, !item, storeId), ...touched } : null;
  const [picked, setPicked] = useState<Record<string, string>>({});
  const [missing, setMissing] = useState<string[]>([]);
  const [noStore, setNoStore] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  /** The Delete item confirm, for "every store unticked": it goes from each store that carries it. */
  const [deleteEverywhere, setDeleteEverywhere] = useState(false);
  const changes = storeList && ticks ? storeChanges(storeList, ticks) : null;
  // the category the item is in (or goes in) — what each new store matches against
  const sourceCat =
    storeList?.find((s) => s.venueId === (storeId ?? item?.venueId))?.categories.find((c) => c.id === draft.categoryId) ??
    sorted.find((c) => c.id === draft.categoryId);
  const tickedIds = storeList && ticks ? storeList.filter((s) => ticks[s.venueId]).map((s) => s.venueId) : [];
  // the stores the item goes to that need a category: a new item's ticked stores, an item's new stores
  const goingTo = item ? (changes?.adds ?? []) : tickedIds;
  const ask = storeList
    ? goingTo.filter((id) => {
        const s = storeList.find((x) => x.venueId === id);
        return !!s && matchCategory(s, sourceCat, draft.categoryId) === null;
      })
    : [];
  const storeNames = (ids: string[]) => ids.map((id) => shortStoreName(storeList?.find((s) => s.venueId === id)?.name ?? nameOf(id))).join(", ");

  // an AI photo is made at one store that carries the item; in "All stores" it then goes to every carrying store
  const carrying = storeList ? carryingEditable(storeList) : [];
  const photoVenue = storeId ?? carrying[0]?.venueId ?? item?.venueId ?? (venues.length === 1 ? venues[0].id : null);
  const photoEveryStore = !storeId && carrying.length > 1;
  const photoAppliesTo = storeId ? [nameOf(storeId)] : carrying.map((s) => shortStoreName(s.name));

  const set = (patchDraft: Partial<ItemDraft>) => setDraft((d) => ({ ...d, ...patchDraft }));
  const setVariant = (i: number, p: Partial<ItemDraft["variants"][number]>) =>
    setDraft((d) => ({ ...d, variants: d.variants.map((v, j) => (j === i ? { ...v, ...p } : v)) }));

  const save = async () => {
    const errs = validateDraft(draft);
    setErrors(errs);
    const stillToPick = storeList ? categoriesFor(storeList, goingTo, picked, sourceCat, draft.categoryId).missing : [];
    setMissing(stillToPick);
    const none = !item && multiStore && !!storeList && createOrigin(storeList, tickedIds, storeId) === null;
    setNoStore(none);
    if (errs.length || stillToPick.length || none) return;
    if (item && changes?.removesAll) {
      setDeleteEverywhere(true);
      setConfirmDelete(true);
      return;
    }
    if (item && changes && changes.removes.length > 0) return setConfirmRemove(true);
    await commit();
  };

  const commit = async () => {
    setConfirmRemove(false);
    setBusy(true);
    let created = false;
    try {
      const skipped: MenuEditResult["skipped"] = [];
      if (item) {
        // the edit, in the picked scope (not for a store it is leaving)
        const calls = storeId && changes?.removes.includes(storeId) ? [] : planEdit(item, draft);
        if (calls.length) skipped.push(...(await runCalls(calls, storeId, key)));
        if (storeList && changes && (changes.adds.length || changes.removes.length)) {
          const { byStore } = categoriesFor(storeList, changes.adds, picked, sourceCat, draft.categoryId);
          const from = copySource(storeList, storeId, item.venueId);
          skipped.push(...(await runCalls(planStoreCalls(item.id, from, byStore, changes.removes), null, `${key}:stores`)));
        } else if (calls.length === 0) return onDone();
      } else if (storeList && multiStore) {
        // made at one ticked store, then copied to the others (each in its own category)
        const origin = createOrigin(storeList, tickedIds, storeId)!;
        const { byStore } = categoriesFor(storeList, tickedIds, picked, sourceCat, draft.categoryId);
        const create = createCall({ ...draft, categoryId: byStore[origin] ?? draft.categoryId });
        const r = await post<MenuEditResult>(`${create.path}?venue=${encodeURIComponent(origin)}`, create.body ?? {}, {
          "Idempotency-Key": `${key}:0`,
        });
        created = true;
        skipped.push(...(r?.skipped ?? []));
        const rest = Object.fromEntries(Object.entries(byStore).filter(([v]) => v !== origin));
        if (r?.id && Object.keys(rest).length) skipped.push(...(await runCalls(planStoreCalls(r.id, origin, rest, []), null, `${key}:stores`)));
      } else {
        skipped.push(...(await runCalls([createCall(draft)], storeId, key)));
      }
      showSkips("menu_saved", skipped);
      onDone();
    } catch (e) {
      toast("error", refusalMessage(t, e, storeId ? nameOf(storeId) : ""));
      // a partial save still changed something: show what the API now has
      if (item || created) onDone();
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!item) return;
    setBusy(true);
    try {
      const enc = encodeURIComponent;
      const calls: PlannedCall[] =
        deleteEverywhere && storeList
          ? storeList
              .filter((s) => s.carries)
              .map((s) => ({ method: "DELETE", path: `/v1/menu/items/${enc(item.id)}?venue=${enc(s.venueId)}`, venueId: s.venueId }))
          : [{ method: "DELETE", path: `/v1/menu/items/${enc(item.id)}` }];
      showSkips("menu_deleted", await runCalls(calls, deleteEverywhere ? null : storeId, `${key}:delete`));
      onDone();
    } catch (e) {
      toast("error", refusalMessage(t, e, storeId ? nameOf(storeId) : ""));
    } finally {
      setBusy(false);
      setConfirmDelete(false);
    }
  };

  return (
    <>
      <SheetHeader>
        <div className="min-w-0">
          <SheetTitle>{item ? t("menu_edit_item") : t("menu_new_item")}</SheetTitle>
          <p className="mt-0.5 text-xs text-neutral-500">{scopeLine}</p>
        </div>
      </SheetHeader>
      <SheetBody className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("menu_name_en")}>
            <Input value={draft.nameEn} maxLength={200} onChange={(e) => set({ nameEn: e.target.value })} />
          </Field>
          <Field label={t("menu_name_fr")}>
            <Input value={draft.nameFr} maxLength={200} placeholder={draft.nameEn} onChange={(e) => set({ nameFr: e.target.value })} />
          </Field>
          {langs.map((l) => (
            <Field key={l} label={t("menu_name_in", { lang: langLabel(t, l) })}>
              <Input
                value={draft.names[l] ?? ""}
                maxLength={500}
                placeholder={draft.nameEn}
                onChange={(e) => set({ names: { ...draft.names, [l]: e.target.value } })}
              />
            </Field>
          ))}
          <Field label={t("menu_desc_en")}>
            <Input value={draft.descriptionEn} maxLength={500} onChange={(e) => set({ descriptionEn: e.target.value })} />
          </Field>
          <Field label={t("menu_desc_fr")}>
            <Input value={draft.descriptionFr} maxLength={500} onChange={(e) => set({ descriptionFr: e.target.value })} />
          </Field>
        </div>

        <Field label={t("menu_col_category")}>
          <Select value={draft.categoryId} onValueChange={(v) => set({ categoryId: v })}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {sorted.map((c) => (
                <SelectItem key={c.id} value={c.id}>
                  {name(c.nameFr, c.nameEn, c.names)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>

        {multiStore && (
          <StoresSection
            stores={storeList}
            ticks={ticks}
            onToggle={(id) => ticks && setTouched((tc) => ({ ...tc, [id]: !ticks[id] }))}
            ask={ask}
            picked={picked}
            onPick={(id, c) => setPicked((p) => ({ ...p, [id]: c }))}
            missing={missing}
            readOnly={!!storeData && !storeData.canEdit}
            isNew={!item}
          />
        )}

        {item && ai?.photos && ai.canUse && (
          <ItemPhotoPanel
            venue={photoVenue}
            everyStore={photoEveryStore}
            appliesTo={multiStore ? photoAppliesTo : []}
            item={item}
            title={name(item.nameFr, item.nameEn, item.names)}
            onChanged={onPhotoChanged}
          />
        )}

        <div className="space-y-3 rounded-lg border border-neutral-100 px-3 py-3">
          <label className="flex items-center justify-between gap-3">
            <span>
              <span className="block text-sm font-medium">{t("menu_available")}</span>
              <span className="block text-xs text-neutral-500">{t("menu_available_hint")}</span>
            </span>
            <Switch checked={draft.active} onCheckedChange={(v) => set({ active: v })} />
          </label>
          <label className="flex items-center justify-between gap-3">
            <span className="text-sm font-medium">{t("menu_alcohol")}</span>
            <Switch checked={draft.isAlcohol} onCheckedChange={(v) => set({ isAlcohol: v })} />
          </label>
        </div>

        <div className="space-y-2">
          <h3 className="text-sm font-semibold">{t("menu_sizes")}</h3>
          {draft.variants.map((v, i) => (
            <div key={v.id ?? `new-${i}`} className="grid grid-cols-[1fr_1fr_7rem_auto] items-end gap-2">
              <Field label={t("menu_size_en")}>
                <Input value={v.labelEn} maxLength={100} onChange={(e) => setVariant(i, { labelEn: e.target.value })} />
              </Field>
              <Field label={t("menu_size_fr")}>
                <Input value={v.labelFr} maxLength={100} placeholder={v.labelEn} onChange={(e) => setVariant(i, { labelFr: e.target.value })} />
              </Field>
              <Field label={t("menu_size_price")}>
                <div className="relative">
                  <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-neutral-400">$</span>
                  <Input
                    className="pl-6 tabular-nums"
                    inputMode="decimal"
                    value={v.price}
                    placeholder="0.00"
                    onChange={(e) => setVariant(i, { price: e.target.value })}
                  />
                </div>
              </Field>
              <Button
                variant="ghost"
                size="icon"
                aria-label={t("menu_remove_size")}
                title={t("menu_remove_size")}
                disabled={draft.variants.length <= 1}
                onClick={() => set({ variants: draft.variants.filter((_, j) => j !== i) })}
              >
                <Trash2 />
              </Button>
            </div>
          ))}
          <Button
            variant="secondary"
            size="sm"
            onClick={() => set({ variants: [...draft.variants, { labelEn: "", labelFr: "", price: "", names: {} }] })}
          >
            <Plus /> {t("menu_add_size")}
          </Button>
        </div>

        {item ? (
          <SpecialsSection
            draft={draft}
            onChange={set}
            showErrors={errors.includes("specials_invalid")}
            sizeLabel={(v) => name(v.labelFr || v.labelEn, v.labelEn, v.names)}
          />
        ) : (
          <p className="rounded-lg bg-neutral-50 px-3 py-2 text-xs text-neutral-600">{t("menu_special_save_first")}</p>
        )}

        {(errors.length > 0 || missing.length > 0 || noStore) && (
          <ul className="space-y-0.5 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-700" role="alert">
            {errors.map((e) => (
              <li key={e}>{t(ERR_KEY[e])}</li>
            ))}
            {missing.map((id) => (
              <li key={`cat-${id}`}>{t("menu_stores_err_category", { store: storeNames([id]) })}</li>
            ))}
            {noStore && <li>{t("menu_stores_err_none")}</li>}
          </ul>
        )}
      </SheetBody>
      <SheetFooter className="flex items-center gap-2">
        {item && (
          <Button
            variant="destructive-outline"
            disabled={busy}
            onClick={() => {
              setDeleteEverywhere(false);
              setConfirmDelete(true);
            }}
          >
            <Trash2 /> {t("menu_delete_item")}
          </Button>
        )}
        {/* the Stores list must be the current one before a save can add or remove stores */}
        <Button className="ml-auto" disabled={busy || (multiStore && !storesError && (!storeData || storesLoading))} onClick={save}>
          {t("save")}
        </Button>
      </SheetFooter>

      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent>
          <DialogTitle>{t("menu_delete_item_q", { name: item ? name(item.nameFr, item.nameEn, item.names) : "" })}</DialogTitle>
          <DialogDescription>
            {t("menu_delete_item_body")}{" "}
            {deleteEverywhere ? t("menu_stores_delete_all", { stores: storeNames(changes?.removes ?? []) }) : scopeLine}
          </DialogDescription>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirmDelete(false)}>
              {t("keep_it")}
            </Button>
            <Button variant="destructive" disabled={busy} onClick={remove}>
              {t("delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmRemove} onOpenChange={setConfirmRemove}>
        <DialogContent>
          <DialogTitle>
            {t("menu_stores_remove_q", {
              name: item ? name(draft.nameFr || draft.nameEn, draft.nameEn, draft.names) : "",
              stores: storeNames(changes?.removes ?? []),
            })}
          </DialogTitle>
          <DialogDescription>{t("menu_stores_remove_body")}</DialogDescription>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirmRemove(false)}>
              {t("keep_it")}
            </Button>
            <Button variant="destructive" disabled={busy} onClick={commit}>
              {t("menu_stores_remove")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

/** The categories: rename, reorder, add, delete (empty ones only). */
export function CategoriesSheet({
  open,
  onOpenChange,
  categories,
  onSaved,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  categories: MenuCategory[];
  onSaved: () => void;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent>{open && <CategoriesForm categories={categories} onSaved={onSaved} />}</SheetContent>
    </Sheet>
  );
}

interface CatDraft {
  nameEn: string;
  nameFr: string;
  names: Record<string, string>;
}

function CategoriesForm({ categories, onSaved }: { categories: MenuCategory[]; onSaved: () => void }) {
  const t = useT();
  const { available, name } = useI18n();
  const { storeId, nameOf } = useStores();
  const scopeLine = useScopeLine();
  const showSkips = useSkipToast();
  const sorted = useMemo(() => [...categories].sort((a, b) => a.sortOrder - b.sortOrder), [categories]);
  const [editing, setEditing] = useState<string | null>(null); // a category id, or "new"
  const [draft, setDraft] = useState<CatDraft>({ nameEn: "", nameFr: "", names: {} });
  const [confirm, setConfirm] = useState<MenuCategory | null>(null);
  const [busy, setBusy] = useState(false);
  const langs = extraLangs(available, ...sorted.map((c) => c.names));

  const run = async (calls: PlannedCall[], ok: MsgKey) => {
    setBusy(true);
    try {
      showSkips(ok, await runCalls(calls, storeId, newEditKey()));
      setEditing(null);
      onSaved();
    } catch (e) {
      toast("error", refusalMessage(t, e, storeId ? nameOf(storeId) : ""));
      onSaved();
    } finally {
      setBusy(false);
    }
  };

  const startEdit = (c: MenuCategory | null) => {
    setEditing(c?.id ?? "new");
    setDraft(c ? { nameEn: c.nameEn, nameFr: c.nameFr, names: { ...(c.names ?? {}) } } : { nameEn: "", nameFr: "", names: {} });
  };

  const saveDraft = () => {
    const nameEn = draft.nameEn.trim();
    if (!nameEn) return toast("error", t("menu_err_name_required"));
    const nameFr = draft.nameFr.trim() || nameEn;
    if (editing === "new") {
      const names = Object.fromEntries(Object.entries(draft.names).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v));
      return run([{ method: "POST", path: "/v1/menu/categories", body: { nameEn, nameFr, names } }], "menu_cat_saved");
    }
    const c = sorted.find((x) => x.id === editing);
    if (!c) return;
    const body: Record<string, unknown> = {};
    if (nameEn !== c.nameEn) body.nameEn = nameEn;
    if (nameFr !== c.nameFr) body.nameFr = nameFr;
    const n = namesDiff(c.names, draft.names);
    if (n) body.names = n;
    if (Object.keys(body).length === 0) return setEditing(null);
    run([{ method: "PATCH", path: `/v1/menu/categories/${encodeURIComponent(c.id)}`, body }], "menu_cat_saved");
  };

  const move = async (index: number, dir: -1 | 1) => {
    const ids = sorted.map((c) => c.id);
    const next = moveInOrder(ids, index, dir);
    if (next === ids) return;
    setBusy(true);
    try {
      await put<MenuEditResult>(scopeApiPath("/v1/menu/categories/order", storeId), { orderedIds: next }, { "Idempotency-Key": newEditKey() });
      onSaved();
    } catch (e) {
      toast("error", refusalMessage(t, e, storeId ? nameOf(storeId) : ""));
    } finally {
      setBusy(false);
    }
  };

  const form = (
    <div className="space-y-3 rounded-lg border border-neutral-100 bg-neutral-50 p-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={t("menu_name_en")}>
          <Input autoFocus value={draft.nameEn} maxLength={100} onChange={(e) => setDraft({ ...draft, nameEn: e.target.value })} />
        </Field>
        <Field label={t("menu_name_fr")}>
          <Input value={draft.nameFr} maxLength={100} placeholder={draft.nameEn} onChange={(e) => setDraft({ ...draft, nameFr: e.target.value })} />
        </Field>
        {langs.map((l) => (
          <Field key={l} label={t("menu_name_in", { lang: langLabel(t, l) })}>
            <Input
              value={draft.names[l] ?? ""}
              maxLength={500}
              placeholder={draft.nameEn}
              onChange={(e) => setDraft({ ...draft, names: { ...draft.names, [l]: e.target.value } })}
            />
          </Field>
        ))}
      </div>
      <div className="flex justify-end gap-2">
        <Button variant="secondary" size="sm" onClick={() => setEditing(null)}>
          {t("cancel")}
        </Button>
        <Button size="sm" disabled={busy} onClick={saveDraft}>
          {t("save")}
        </Button>
      </div>
    </div>
  );

  return (
    <>
      <SheetHeader>
        <div className="min-w-0">
          <SheetTitle>{t("menu_cat_title")}</SheetTitle>
          <p className="mt-0.5 text-xs text-neutral-500">{scopeLine}</p>
        </div>
      </SheetHeader>
      <SheetBody className="space-y-2">
        {sorted.map((c, i) =>
          editing === c.id ? (
            <div key={c.id}>{form}</div>
          ) : (
            <div key={c.id} className="flex items-center gap-1 rounded-lg border border-neutral-100 px-3 py-2">
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{name(c.nameFr, c.nameEn, c.names)}</span>
              <Button variant="ghost" size="icon-sm" aria-label={t("menu_cat_up")} title={t("menu_cat_up")} disabled={busy || i === 0} onClick={() => move(i, -1)}>
                <ArrowUp />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={t("menu_cat_down")}
                title={t("menu_cat_down")}
                disabled={busy || i === sorted.length - 1}
                onClick={() => move(i, 1)}
              >
                <ArrowDown />
              </Button>
              <Button variant="ghost" size="sm" disabled={busy} onClick={() => startEdit(c)}>
                {t("menu_cat_rename")}
              </Button>
              <Button variant="ghost" size="icon-sm" aria-label={t("delete")} title={t("delete")} disabled={busy} onClick={() => setConfirm(c)}>
                <Trash2 />
              </Button>
            </div>
          )
        )}
        {editing === "new" ? (
          form
        ) : (
          <Button variant="secondary" size="sm" disabled={busy} onClick={() => startEdit(null)}>
            <Plus /> {t("menu_cat_add")}
          </Button>
        )}
      </SheetBody>

      <Dialog open={confirm !== null} onOpenChange={(o) => !o && setConfirm(null)}>
        <DialogContent>
          <DialogTitle>{t("menu_cat_delete_q", { name: confirm ? name(confirm.nameFr, confirm.nameEn, confirm.names) : "" })}</DialogTitle>
          <DialogDescription>{t("menu_cat_delete_body")}</DialogDescription>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirm(null)}>
              {t("keep_it")}
            </Button>
            <Button
              variant="destructive"
              disabled={busy}
              onClick={() => {
                const c = confirm;
                setConfirm(null);
                if (c) run([{ method: "DELETE", path: `/v1/menu/categories/${encodeURIComponent(c.id)}` }], "menu_cat_saved");
              }}
            >
              {t("delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
