"use client";

import { useRef, useState } from "react";
import { Check, ImagePlus, Loader2, Pencil, Plus, Trash2, X } from "lucide-react";
import { del, patch, post, putFile, ApiError } from "@/lib/api";
import { inputToCents, centsToInput, CAD } from "@/lib/format";
import { toast, toastError } from "@/lib/toast";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MenuCategory, MenuItem, MenuResponse, MenuVariant } from "@/lib/types";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Switch } from "@/components/ui/switch";

export type EditorState = { mode: "new" } | { mode: "edit"; itemId: string };

export function ItemEditor({
  state,
  menu,
  onClose,
  onChanged,
}: {
  state: EditorState | null;
  menu?: MenuResponse;
  onClose: () => void;
  onChanged: () => void;
}) {
  const item = state?.mode === "edit" ? menu?.items.find((i) => i.id === state.itemId) ?? null : null;
  return (
    <Sheet open={state !== null} onOpenChange={(o) => !o && onClose()}>
      <SheetContent>
        {state && menu && (
          <EditorInner
            key={state.mode === "edit" ? state.itemId : "new"}
            item={item}
            categories={menu.categories}
            onClose={onClose}
            onChanged={onChanged}
          />
        )}
      </SheetContent>
    </Sheet>
  );
}

type VariantDraft = { labelFr: string; labelEn: string; price: string };

function EditorInner({
  item,
  categories,
  onClose,
  onChanged,
}: {
  item: MenuItem | null;
  categories: MenuCategory[];
  onClose: () => void;
  onChanged: () => void;
}) {
  const t = useT();
  const { name } = useI18n();
  const creating = item === null;
  const [nameFr, setNameFr] = useState(item?.nameFr ?? "");
  const [nameEn, setNameEn] = useState(item?.nameEn ?? "");
  const [abbrev, setAbbrev] = useState(item?.abbrev ?? "");
  const [categoryId, setCategoryId] = useState(item?.categoryId ?? categories[0]?.id ?? "");
  const [isAlcohol, setIsAlcohol] = useState(item?.isAlcohol ?? false);
  const [drafts, setDrafts] = useState<VariantDraft[]>([
    { labelFr: "normale", labelEn: "Regular", price: "" },
  ]);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const validDetails = nameFr.trim() && nameEn.trim() && categoryId;

  const save = async () => {
    if (!validDetails) {
      toast("error", t("item_required"));
      return;
    }
    if (creating) {
      const variants = drafts.map((d) => ({
        labelFr: d.labelFr.trim(),
        labelEn: d.labelEn.trim(),
        priceCents: inputToCents(d.price),
      }));
      if (variants.length === 0 || variants.some((v) => !v.labelFr || !v.labelEn || v.priceCents === null)) {
        toast("error", t("item_variants_required"));
        return;
      }
      setBusy(true);
      try {
        await post("/v1/menu/items", {
          nameFr: nameFr.trim(),
          nameEn: nameEn.trim(),
          categoryId,
          abbrev: abbrev.trim(),
          isAlcohol,
          variants,
        });
        toast("success", t("item_created_toast"));
        onChanged();
        onClose();
      } catch (err) {
        toastError(err);
        setBusy(false);
      }
      return;
    }
    setBusy(true);
    try {
      await patch(`/v1/menu/items/${item.id}`, {
        nameFr: nameFr.trim(),
        nameEn: nameEn.trim(),
        categoryId,
        abbrev: abbrev.trim(),
        isAlcohol,
      });
      toast("success", t("item_saved_toast"));
      onChanged();
    } catch (err) {
      toastError(err);
    } finally {
      setBusy(false);
    }
  };

  const deleteItem = async () => {
    if (!item) return;
    setBusy(true);
    try {
      await del(`/v1/menu/items/${item.id}`);
      toast("success", t("item_deleted_toast"));
      onChanged();
      onClose();
    } catch (err) {
      toastError(err);
      setBusy(false);
    }
  };

  return (
    <>
      <SheetHeader>
        <SheetTitle>{creating ? t("item_new") : name(nameFr || item.nameFr, nameEn || item.nameEn)}</SheetTitle>
      </SheetHeader>
      <SheetBody className="space-y-5">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={t("item_name_fr")}>
            <Input value={nameFr} onChange={(e) => setNameFr(e.target.value)} placeholder="éléphant" />
          </Field>
          <Field label={t("item_name_en")}>
            <Input value={nameEn} onChange={(e) => setNameEn(e.target.value)} placeholder="Lantern House Lager" />
          </Field>
          <Field label={t("item_category")}>
            <Select value={categoryId} onValueChange={setCategoryId}>
              <SelectTrigger>
                <SelectValue placeholder={t("item_pick_category")} />
              </SelectTrigger>
              <SelectContent>
                {categories.map((c) => (
                  <SelectItem key={c.id} value={c.id}>
                    {name(c.nameFr, c.nameEn)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field label={t("item_abbrev")}>
            <Input value={abbrev} onChange={(e) => setAbbrev(e.target.value)} placeholder="CH" />
          </Field>
        </div>
        <label className="flex items-center justify-between rounded-xl border border-neutral-100 px-3 py-2.5">
          <span className="text-sm font-medium">{t("item_alcohol")}</span>
          <Switch checked={isAlcohol} onCheckedChange={setIsAlcohol} />
        </label>

        <section>
          <h4 className="mb-2 text-[11px] font-medium uppercase tracking-wider text-neutral-400">
            {t("item_variants")}
          </h4>
          {creating ? (
            <DraftVariants drafts={drafts} setDrafts={setDrafts} />
          ) : (
            <LiveVariants item={item} onChanged={onChanged} />
          )}
        </section>

        {!creating && <PhotoSection item={item} onChanged={onChanged} />}

        {!creating && (
          <section className="border-t border-neutral-100 pt-4">
            <Button
              variant="destructive-outline"
              size="sm"
              onClick={() => setConfirmDelete(true)}
              disabled={busy}
            >
              <Trash2 /> {t("item_delete")}
            </Button>
          </section>
        )}
      </SheetBody>
      <SheetFooter className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={onClose} disabled={busy}>
          {t("cancel")}
        </Button>
        <Button className="flex-1" onClick={save} disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          {creating ? t("item_create") : t("save")}
        </Button>
      </SheetFooter>

      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent>
          <DialogTitle>{t("item_delete_q", { name: name(item?.nameFr, item?.nameEn) })}</DialogTitle>
          <DialogDescription>{t("item_delete_body")}</DialogDescription>
          <DialogFooter>
            <Button variant="secondary" size="sm" onClick={() => setConfirmDelete(false)}>
              {t("keep_it")}
            </Button>
            <Button variant="destructive" size="sm" onClick={deleteItem} disabled={busy}>
              {t("delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      {children}
    </div>
  );
}

function DraftVariants({
  drafts,
  setDrafts,
}: {
  drafts: VariantDraft[];
  setDrafts: React.Dispatch<React.SetStateAction<VariantDraft[]>>;
}) {
  const t = useT();
  const update = (i: number, patch: Partial<VariantDraft>) =>
    setDrafts((prev) => prev.map((d, j) => (j === i ? { ...d, ...patch } : d)));
  return (
    <div className="space-y-2">
      {drafts.map((d, i) => (
        <div key={i} className="flex items-center gap-2">
          <Input
            value={d.labelFr}
            onChange={(e) => update(i, { labelFr: e.target.value })}
            placeholder="bouteille"
            className="flex-1"
          />
          <Input
            value={d.labelEn}
            onChange={(e) => update(i, { labelEn: e.target.value })}
            placeholder="Bottle"
            className="flex-1"
          />
          <Input
            value={d.price}
            onChange={(e) => update(i, { price: e.target.value })}
            placeholder="$"
            inputMode="decimal"
            className="w-20 text-right"
          />
          <Button
            variant="ghost"
            size="icon-sm"
            disabled={drafts.length === 1}
            onClick={() => setDrafts((prev) => prev.filter((_, j) => j !== i))}
          >
            <Trash2 className="text-neutral-400" />
          </Button>
        </div>
      ))}
      <Button
        variant="secondary"
        size="sm"
        onClick={() => setDrafts((prev) => [...prev, { labelFr: "", labelEn: "", price: "" }])}
      >
        <Plus /> {t("item_add_variant")}
      </Button>
    </div>
  );
}

function LiveVariants({ item, onChanged }: { item: MenuItem; onChanged: () => void }) {
  const t = useT();
  const [adding, setAdding] = useState(false);
  const variants = [...item.variants].sort((a, b) => a.sortOrder - b.sortOrder);
  return (
    <div className="space-y-2">
      <div className="divide-y divide-neutral-100 rounded-xl border border-neutral-100">
        {variants.map((v) => (
          <VariantRow key={v.id} itemId={item.id} variant={v} onChanged={onChanged} />
        ))}
      </div>
      {adding ? (
        <VariantForm
          onCancel={() => setAdding(false)}
          onSubmit={async (labelFr, labelEn, priceCents) => {
            await post(`/v1/menu/items/${item.id}/variants`, { labelFr, labelEn, priceCents });
            setAdding(false);
            onChanged();
          }}
        />
      ) : (
        <Button variant="secondary" size="sm" onClick={() => setAdding(true)}>
          <Plus /> {t("item_add_variant")}
        </Button>
      )}
    </div>
  );
}

function VariantRow({
  itemId,
  variant,
  onChanged,
}: {
  itemId: string;
  variant: MenuVariant;
  onChanged: () => void;
}) {
  const t = useT();
  const { name, nameAlt } = useI18n();
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);

  const remove = async () => {
    setBusy(true);
    try {
      await del(`/v1/menu/items/${itemId}/variants/${variant.id}`);
      onChanged();
    } catch (err) {
      if (err instanceof ApiError && err.code === "last_variant") {
        toast("error", t("item_needs_variant"));
      } else {
        toastError(err);
      }
    } finally {
      setBusy(false);
    }
  };

  if (editing) {
    return (
      <div className="px-3 py-2.5">
        <VariantForm
          initial={variant}
          onCancel={() => setEditing(false)}
          onSubmit={async (labelFr, labelEn, priceCents) => {
            await patch(`/v1/menu/items/${itemId}/variants/${variant.id}`, {
              labelFr,
              labelEn,
              priceCents,
            });
            setEditing(false);
            onChanged();
          }}
        />
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2 px-3 py-2.5">
      <div className="min-w-0 flex-1">
        <span className="text-sm font-medium">{name(variant.labelFr, variant.labelEn)}</span>
        <span className="ml-1.5 text-xs text-neutral-500">{nameAlt(variant.labelFr, variant.labelEn)}</span>
      </div>
      <span className="text-sm font-medium tabular-nums">{CAD(variant.priceCents)}</span>
      <Button variant="ghost" size="icon-sm" onClick={() => setEditing(true)} disabled={busy}>
        <Pencil className="text-neutral-400" />
      </Button>
      <Button variant="ghost" size="icon-sm" onClick={remove} disabled={busy}>
        <Trash2 className="text-neutral-400" />
      </Button>
    </div>
  );
}

function VariantForm({
  initial,
  onSubmit,
  onCancel,
}: {
  initial?: MenuVariant;
  onSubmit: (labelFr: string, labelEn: string, priceCents: number) => Promise<void>;
  onCancel: () => void;
}) {
  const t = useT();
  const [labelFr, setLabelFr] = useState(initial?.labelFr ?? "");
  const [labelEn, setLabelEn] = useState(initial?.labelEn ?? "");
  const [price, setPrice] = useState(initial ? centsToInput(initial.priceCents) : "");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    const priceCents = inputToCents(price);
    if (!labelFr.trim() || !labelEn.trim() || priceCents === null) {
      toast("error", t("item_variant_required"));
      return;
    }
    setBusy(true);
    try {
      await onSubmit(labelFr.trim(), labelEn.trim(), priceCents);
    } catch (err) {
      toastError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex items-center gap-2">
      <Input value={labelFr} onChange={(e) => setLabelFr(e.target.value)} placeholder="bouteille" className="flex-1" />
      <Input value={labelEn} onChange={(e) => setLabelEn(e.target.value)} placeholder="Bottle" className="flex-1" />
      <Input
        value={price}
        onChange={(e) => setPrice(e.target.value)}
        placeholder="$"
        inputMode="decimal"
        className="w-20 text-right"
      />
      <Button variant="ghost" size="icon-sm" onClick={submit} disabled={busy}>
        {busy ? <Loader2 className="animate-spin" /> : <Check className="text-accent" />}
      </Button>
      <Button variant="ghost" size="icon-sm" onClick={onCancel} disabled={busy}>
        <X className="text-neutral-400" />
      </Button>
    </div>
  );
}

const MAX_PHOTO_BYTES = 2 * 1024 * 1024;

function PhotoSection({ item, onChanged }: { item: MenuItem; onChanged: () => void }) {
  const t = useT();
  const [uploading, setUploading] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const url =
    item.photoVersion !== null ? `/v1/menu/items/${item.id}/photo?v=${item.photoVersion}` : null;

  const onFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (!["image/jpeg", "image/png"].includes(file.type)) {
      toast("error", t("photo_type_err"));
      return;
    }
    if (file.size > MAX_PHOTO_BYTES) {
      toast("error", t("photo_size_err"));
      return;
    }
    setUploading(true);
    try {
      await putFile(`/v1/menu/items/${item.id}/photo`, "photo", file);
      toast("success", t("photo_updated"));
      onChanged();
    } catch (err) {
      toastError(err);
    } finally {
      setUploading(false);
    }
  };

  return (
    <section>
      <h4 className="mb-2 text-[11px] font-medium uppercase tracking-wider text-neutral-400">{t("item_photo")}</h4>
      <div className="flex items-center gap-4">
        {url ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={url} alt={item.nameEn} className="h-20 w-20 rounded-xl bg-neutral-100 object-cover" />
        ) : (
          <div className="grid h-20 w-20 place-items-center rounded-xl bg-neutral-100 text-neutral-300">
            <ImagePlus className="h-6 w-6" />
          </div>
        )}
        <div>
          <Button
            variant="secondary"
            size="sm"
            disabled={uploading}
            onClick={() => fileRef.current?.click()}
          >
            {uploading ? <Loader2 className="animate-spin" /> : <ImagePlus />}
            {url ? t("photo_replace") : t("photo_upload")}
          </Button>
          <p className="mt-1.5 text-xs text-neutral-400">{t("photo_hint")}</p>
          <input
            ref={fileRef}
            type="file"
            accept="image/jpeg,image/png"
            className="hidden"
            onChange={onFile}
          />
        </div>
      </div>
    </section>
  );
}
