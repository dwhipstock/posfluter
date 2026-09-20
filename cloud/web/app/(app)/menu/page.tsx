"use client";

import { useMemo, useState } from "react";
import { FolderCog, Plus, Wine } from "lucide-react";
import { patch } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { toastError } from "@/lib/toast";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MenuItem, MenuResponse } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { PageHeader } from "@/components/page-header";
import { EmptyState, ErrorState } from "@/components/states";
import { CategoryManager } from "@/components/menu/category-manager";
import { ItemEditor, type EditorState } from "@/components/menu/item-editor";

export default function MenuPage() {
  const t = useT();
  const { name, nameAlt } = useI18n();
  const { data, error, isLoading, mutate } = useApi<MenuResponse>("/v1/menu");
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [catsOpen, setCatsOpen] = useState(false);

  const groups = useMemo(() => {
    if (!data) return [];
    const byCat = new Map<string, MenuItem[]>();
    for (const item of data.items) {
      const list = byCat.get(item.categoryId) ?? [];
      list.push(item);
      byCat.set(item.categoryId, list);
    }
    return [...data.categories]
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((c) => ({
        category: c,
        items: (byCat.get(c.id) ?? []).sort((a, b) => a.nameEn.localeCompare(b.nameEn)),
      }));
  }, [data]);

  const toggleActive = async (item: MenuItem, active: boolean) => {
    // optimistic — POS availability toggles should feel instant
    mutate(
      (prev) =>
        prev && { ...prev, items: prev.items.map((i) => (i.id === item.id ? { ...i, active } : i)) },
      { revalidate: false }
    );
    try {
      await patch(`/v1/menu/items/${item.id}`, { active });
      mutate();
    } catch (err) {
      toastError(err);
      mutate();
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("menu_title")}
        sub={t("menu_sub")}
        action={
          <div className="flex gap-2">
            <Button variant="secondary" size="sm" onClick={() => setCatsOpen(true)}>
              <FolderCog /> {t("menu_categories_btn")}
            </Button>
            <Button size="sm" onClick={() => setEditor({ mode: "new" })}>
              <Plus /> {t("menu_item_btn")}
            </Button>
          </div>
        }
      />

      {isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-40 w-full rounded-2xl" />
          ))}
        </div>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : groups.length > 0 ? (
        <div className="space-y-4">
          {groups.map(({ category, items }) => (
            <Card key={category.id}>
              <div className="flex items-baseline gap-2 border-b border-neutral-100 px-4 py-3">
                <h2 className="text-sm font-semibold">{name(category.nameFr, category.nameEn)}</h2>
                <span className="text-xs text-neutral-500">{nameAlt(category.nameFr, category.nameEn)}</span>
                <span className="ml-auto text-xs text-neutral-400">{items.length}</span>
              </div>
              {items.length > 0 ? (
                <div className="divide-y divide-neutral-100">
                  {items.map((item) => (
                    <ItemRow
                      key={item.id}
                      item={item}
                      onOpen={() => setEditor({ mode: "edit", itemId: item.id })}
                      onToggle={(active) => toggleActive(item, active)}
                    />
                  ))}
                </div>
              ) : (
                <p className="px-4 py-5 text-center text-xs text-neutral-400">{t("menu_cat_empty")}</p>
              )}
            </Card>
          ))}
        </div>
      ) : (
        <Card>
          <EmptyState title={t("menu_empty")} hint={t("menu_empty_hint")} />
        </Card>
      )}

      <ItemEditor state={editor} menu={data} onClose={() => setEditor(null)} onChanged={() => mutate()} />
      <CategoryManager
        open={catsOpen}
        menu={data}
        onClose={() => setCatsOpen(false)}
        onChanged={() => mutate()}
      />
    </div>
  );
}

function priceRange(item: MenuItem): string {
  const prices = item.variants.map((v) => v.priceCents);
  if (prices.length === 0) return "—";
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  return min === max ? CAD(min) : `${CAD(min)}–${CAD(max)}`;
}

function ItemRow({
  item,
  onOpen,
  onToggle,
}: {
  item: MenuItem;
  onOpen: () => void;
  onToggle: (active: boolean) => void;
}) {
  const t = useT();
  const { name, nameAlt } = useI18n();
  return (
    <div className="flex w-full items-center gap-3 px-4 py-2.5">
      <button onClick={onOpen} className="flex min-w-0 flex-1 items-center gap-3 text-left">
        <Thumb item={item} />
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-1.5">
            <span className="truncate text-sm font-medium">{name(item.nameFr, item.nameEn)}</span>
            {item.isAlcohol && <Wine className="h-3 w-3 shrink-0 text-neutral-300" />}
            {!item.active && <Badge variant="outline">{t("menu_off")}</Badge>}
          </span>
          <span className="block truncate text-xs text-neutral-500">{nameAlt(item.nameFr, item.nameEn)}</span>
        </span>
        <span className="shrink-0 text-sm font-medium tabular-nums">{priceRange(item)}</span>
      </button>
      <Switch
        checked={item.active}
        onCheckedChange={onToggle}
        aria-label={t("menu_available_aria", { name: name(item.nameFr, item.nameEn) })}
      />
    </div>
  );
}

function Thumb({ item }: { item: MenuItem }) {
  if (item.photoVersion !== null) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={`/v1/menu/items/${item.id}/photo?v=${item.photoVersion}`}
        alt=""
        loading="lazy"
        className="h-11 w-11 shrink-0 rounded-lg bg-neutral-100 object-cover"
      />
    );
  }
  return (
    <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-neutral-100 text-sm font-semibold text-neutral-400">
      {(item.nameEn[0] ?? "?").toUpperCase()}
    </span>
  );
}
