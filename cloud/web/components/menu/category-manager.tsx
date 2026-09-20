"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, Check, Loader2, Pencil, Plus, Trash2, X } from "lucide-react";
import { del, patch, post, ApiError } from "@/lib/api";
import { toast, toastError } from "@/lib/toast";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MenuCategory, MenuResponse } from "@/lib/types";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Sheet, SheetBody, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";

export function CategoryManager({
  open,
  menu,
  onClose,
  onChanged,
}: {
  open: boolean;
  menu?: MenuResponse;
  onClose: () => void;
  onChanged: () => void;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const categories = [...(menu?.categories ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);

  const move = async (index: number, dir: -1 | 1) => {
    const target = index + dir;
    if (target < 0 || target >= categories.length) return;
    const ids = categories.map((c) => c.id);
    [ids[index], ids[target]] = [ids[target], ids[index]];
    setBusy(true);
    try {
      await patch("/v1/menu/categories/order", { orderedIds: ids });
      onChanged();
    } catch (err) {
      toastError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open={open} onOpenChange={(o) => !o && onClose()}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>{t("cats_title")}</SheetTitle>
        </SheetHeader>
        <SheetBody className="space-y-4">
          {categories.length > 0 ? (
            <div className="divide-y divide-neutral-100 rounded-xl border border-neutral-100">
              {categories.map((c, i) => (
                <CategoryRow
                  key={c.id}
                  category={c}
                  first={i === 0}
                  last={i === categories.length - 1}
                  busy={busy}
                  onMoveUp={() => move(i, -1)}
                  onMoveDown={() => move(i, 1)}
                  onChanged={onChanged}
                />
              ))}
            </div>
          ) : (
            <p className="py-6 text-center text-sm text-neutral-500">{t("cats_none")}</p>
          )}
          <AddCategory onChanged={onChanged} />
        </SheetBody>
      </SheetContent>
    </Sheet>
  );
}

function CategoryRow({
  category,
  first,
  last,
  busy,
  onMoveUp,
  onMoveDown,
  onChanged,
}: {
  category: MenuCategory;
  first: boolean;
  last: boolean;
  busy: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onChanged: () => void;
}) {
  const t = useT();
  const { name, nameAlt } = useI18n();
  const [editing, setEditing] = useState(false);
  const [nameFr, setNameFr] = useState(category.nameFr);
  const [nameEn, setNameEn] = useState(category.nameEn);
  const [saving, setSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const save = async () => {
    if (!nameFr.trim() || !nameEn.trim()) {
      toast("error", t("cats_both_names"));
      return;
    }
    setSaving(true);
    try {
      await patch(`/v1/menu/categories/${category.id}`, {
        nameFr: nameFr.trim(),
        nameEn: nameEn.trim(),
      });
      setEditing(false);
      onChanged();
    } catch (err) {
      toastError(err);
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    setSaving(true);
    try {
      await del(`/v1/menu/categories/${category.id}`);
      setConfirmDelete(false);
      onChanged();
    } catch (err) {
      setConfirmDelete(false);
      if (err instanceof ApiError && err.code === "category_in_use") {
        toast("error", t("cats_in_use"));
      } else {
        toastError(err);
      }
    } finally {
      setSaving(false);
    }
  };

  if (editing) {
    return (
      <div className="flex items-center gap-2 px-3 py-2.5">
        <Input value={nameFr} onChange={(e) => setNameFr(e.target.value)} className="flex-1" />
        <Input value={nameEn} onChange={(e) => setNameEn(e.target.value)} className="flex-1" />
        <Button variant="ghost" size="icon-sm" onClick={save} disabled={saving}>
          {saving ? <Loader2 className="animate-spin" /> : <Check className="text-accent" />}
        </Button>
        <Button variant="ghost" size="icon-sm" onClick={() => setEditing(false)} disabled={saving}>
          <X className="text-neutral-400" />
        </Button>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-1 px-3 py-2.5">
      <div className="min-w-0 flex-1">
        <span className="text-sm font-medium">{name(category.nameFr, category.nameEn)}</span>
        <span className="ml-1.5 text-xs text-neutral-500">{nameAlt(category.nameFr, category.nameEn)}</span>
      </div>
      <Button variant="ghost" size="icon-sm" onClick={onMoveUp} disabled={first || busy}>
        <ArrowUp className="text-neutral-400" />
      </Button>
      <Button variant="ghost" size="icon-sm" onClick={onMoveDown} disabled={last || busy}>
        <ArrowDown className="text-neutral-400" />
      </Button>
      <Button variant="ghost" size="icon-sm" onClick={() => setEditing(true)} disabled={busy}>
        <Pencil className="text-neutral-400" />
      </Button>
      <Button variant="ghost" size="icon-sm" onClick={() => setConfirmDelete(true)} disabled={busy}>
        <Trash2 className="text-neutral-400" />
      </Button>

      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent>
          <DialogTitle>{t("cats_delete_q", { name: name(category.nameFr, category.nameEn) })}</DialogTitle>
          <DialogDescription>{t("cats_delete_body")}</DialogDescription>
          <DialogFooter>
            <Button variant="secondary" size="sm" onClick={() => setConfirmDelete(false)}>
              {t("keep_it")}
            </Button>
            <Button variant="destructive" size="sm" onClick={remove} disabled={saving}>
              {t("delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function AddCategory({ onChanged }: { onChanged: () => void }) {
  const t = useT();
  const [nameFr, setNameFr] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [busy, setBusy] = useState(false);

  const add = async () => {
    if (!nameFr.trim() || !nameEn.trim()) {
      toast("error", t("cats_both_names"));
      return;
    }
    setBusy(true);
    try {
      await post("/v1/menu/categories", { nameFr: nameFr.trim(), nameEn: nameEn.trim() });
      setNameFr("");
      setNameEn("");
      onChanged();
    } catch (err) {
      toastError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h4 className="mb-2 text-[11px] font-medium uppercase tracking-wider text-neutral-400">
        {t("cats_add")}
      </h4>
      <div className="flex items-center gap-2">
        <Input
          value={nameFr}
          onChange={(e) => setNameFr(e.target.value)}
          placeholder="bière"
          className="flex-1"
        />
        <Input
          value={nameEn}
          onChange={(e) => setNameEn(e.target.value)}
          placeholder="Beer"
          className="flex-1"
        />
        <Button size="sm" onClick={add} disabled={busy}>
          {busy ? <Loader2 className="animate-spin" /> : <Plus />}
          {t("add")}
        </Button>
      </div>
    </div>
  );
}
