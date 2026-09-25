"use client";

import { useMemo } from "react";
import { Wine } from "lucide-react";
import { useApi } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MenuItem, MenuResponse } from "@/lib/types";
import { useStores } from "@/lib/store";
import { StoreTag } from "@/components/store-breakdown";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { PageHeader } from "@/components/page-header";
import { EmptyState, ErrorState } from "@/components/states";

/** Read-only: each store's tablet owns its menu and pushes it up (one-way sync). */
export default function MenuPage() {
  const t = useT();
  const { name, nameAlt } = useI18n();
  const { data, error, isLoading, mutate } = useApi<MenuResponse>("/v1/menu");

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
        items: mergeAcrossStores(byCat.get(c.id) ?? []).sort((a, b) => a.item.nameEn.localeCompare(b.item.nameEn)),
      }));
  }, [data]);

  return (
    <div className="space-y-4">
      <PageHeader title={t("menu_title")} sub={t("menu_sub")} />

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
                  {items.map((row) => (
                    <ItemRow key={row.item.id} row={row} />
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
    </div>
  );
}

/** One menu row; in "All stores" the same item id at several stores is one row. */
interface MergedItem {
  item: MenuItem;
  /** Every store's copy of the item (a single entry when one store is picked). */
  copies: MenuItem[];
}

function mergeAcrossStores(items: MenuItem[]): MergedItem[] {
  const byId = new Map<string, MergedItem>();
  for (const item of items) {
    const row = byId.get(item.id);
    if (row) row.copies.push(item);
    else byId.set(item.id, { item, copies: [item] });
  }
  return [...byId.values()];
}

function priceRange(items: MenuItem[]): string {
  const prices = items.flatMap((i) => i.variants.map((v) => v.priceCents));
  if (prices.length === 0) return "—";
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  return min === max ? CAD(min) : `${CAD(min)}–${CAD(max)}`;
}

function ItemRow({ row }: { row: MergedItem }) {
  const t = useT();
  const { name, nameAlt } = useI18n();
  const { combined, venues, nameOf, colorOf } = useStores();
  const { item, copies } = row;
  // an item on every store's menu needs no tag; a store-specific one names its store(s)
  const storeSpecific = combined && copies.length < venues.length;
  return (
    <div className="flex w-full items-center gap-3 px-4 py-2.5">
      <Thumb item={item} />
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="truncate text-sm font-medium">{name(item.nameFr, item.nameEn)}</span>
          {item.isAlcohol && <Wine className="h-3 w-3 shrink-0 text-neutral-400" />}
          {copies.every((c) => !c.active) && <Badge variant="outline">{t("menu_off")}</Badge>}
          {storeSpecific && copies.map((c) => <StoreTag key={c.venueId} venueId={c.venueId} />)}
        </span>
        <span className="block truncate text-xs text-neutral-500">{nameAlt(item.nameFr, item.nameEn)}</span>
      </span>
      {combined && copies.length > 1 ? (
        // "All stores": each store's own price (and whether it is on its menu)
        <span className="flex shrink-0 flex-col items-end gap-0.5">
          {copies.map((c) => (
            <span key={c.venueId} className="inline-flex items-center gap-1.5 text-xs tabular-nums text-neutral-600">
              <span className="h-2 w-2 rounded-full" style={{ backgroundColor: colorOf(c.venueId) }} />
              {nameOf(c.venueId)}
              <span className={c.active ? "font-medium text-ink" : "text-neutral-500 line-through"}>{priceRange([c])}</span>
            </span>
          ))}
        </span>
      ) : (
        <span className="shrink-0 text-sm font-medium tabular-nums">{priceRange(copies)}</span>
      )}
    </div>
  );
}

function Thumb({ item }: { item: MenuItem }) {
  if (item.photoVersion !== null) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={`/v1/menu/items/${item.id}/photo?venue=${encodeURIComponent(item.venueId)}&v=${item.photoVersion}`}
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
