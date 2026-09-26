"use client";

import { useMemo } from "react";
import { Wine } from "lucide-react";
import { useApi } from "@/lib/hooks";
import { useMoney, type MoneyApi } from "@/lib/money";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MenuItem, MenuResponse } from "@/lib/types";
import { useStores } from "@/lib/store";
import { StoreTag } from "@/components/store-breakdown";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { PageHeader } from "@/components/page-header";
import { EmptyState, ErrorState } from "@/components/states";
import { ExportMenu } from "@/components/export-menu";
import { CatalogFilterBar, Pager, useCatalogFilters } from "@/components/catalog-filters";
import { get } from "@/lib/api";
import { scopeApiPath, useStoreId } from "@/lib/store";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, type ExportDoc } from "@/lib/export/doc";

const PAGE_SIZE = 100;

/**
 * Read-only: each store's tablet owns its menu and pushes it up (one-way sync).
 * A retail store can carry ~5,000 products, so the list is paged by the API
 * (100 products a page, grouped by category) with a search and the 2-way
 * category → subcategory filter plus size; the export fetches every match.
 */
export default function MenuPage() {
  const t = useT();
  const { name, nameAlt } = useI18n();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const storeId = useStoreId();
  const filters = useCatalogFilters(PAGE_SIZE);
  const { data, error, isLoading, mutate } = useApi<MenuResponse>(`/v1/menu?${filters.pageQuery}`);
  // category names from the unfiltered menu shape (every page lists them all)
  const categoryName = (id: string) => {
    const c = data?.categories.find((x) => x.id === id);
    return c ? name(c.nameFr, c.nameEn) : id;
  };

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
      }))
      // a filtered page only shows the categories it has products in
      .filter((g) => g.items.length > 0 || !filters.active);
  }, [data, filters.active]);

  const buildDoc = async (): Promise<ExportDoc | null> => {
    const all = await get<MenuResponse>(
      scopeApiPath(`/v1/menu?limit=10000${filters.filterQuery ? `&${filters.filterQuery}` : ""}`, storeId)
    );
    const catName = (id: string) => {
      const c = all.categories.find((x) => x.id === id);
      return c ? name(c.nameFr, c.nameEn) : id;
    };
    return {
      ...meta("products"),
      rangeLabel: t("catalog_products", { n: (all.total ?? all.items.length).toLocaleString() }),
      reportTitle: t("menu_title"),
      kpis: [{ label: t("stock_kpi_products"), value: String(all.total ?? all.items.length) }],
      sections: [
        {
          title: t("menu_title"),
          columns: storeExport.withStore<MenuItem>([
            col.text(t("stock_col_product"), (i) => name(i.nameFr, i.nameEn), { width: 40 }),
            col.text(t("menu_col_brand"), (i) => i.brand ?? "", { width: 18 }),
            col.text(t("menu_col_category"), (i) => catName(i.categoryId), { width: 16 }),
            col.text(t("catalog_subcategory"), (i) => i.subcategory ?? "", { width: 16 }),
            col.text(t("catalog_size"), (i) => i.size ?? "", { width: 12 }),
            col.text(t("stock_col_barcode"), (i) => i.barcode ?? "", { width: 16 }),
            col.money(t("menu_col_price"), (i) => i.variants[0]?.priceCents ?? 0),
            col.text(t("menu_col_active"), (i) => (i.active ? t("stock_yes") : ""), { width: 8 }),
          ]),
          rows: all.items,
        },
      ],
    };
  };

  const total = data?.total ?? 0;
  return (
    <div className="space-y-4">
      <PageHeader
        title={t("menu_title")}
        sub={t("menu_sub")}
        action={<ExportMenu build={buildDoc} disabled={!data || total === 0} />}
      />

      {data && (total > 0 || filters.active) && (
        <Card>
          <CatalogFilterBar
            filters={filters}
            facets={data.facets}
            categoryName={categoryName}
            searchLabel={t("menu_search")}
          >
            <span className="ml-auto text-xs tabular-nums text-neutral-500">
              {t("catalog_products", { n: total.toLocaleString() })}
            </span>
          </CatalogFilterBar>
        </Card>
      )}

      {isLoading && !data ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-40 w-full rounded-2xl" />
          ))}
        </div>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : groups.length > 0 && total > 0 ? (
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
          <Card>
            <Pager total={total} offset={filters.offset} pageSize={PAGE_SIZE} onOffset={filters.setOffset} />
          </Card>
        </div>
      ) : filters.active ? (
        <Card>
          <EmptyState title={t("menu_no_match")} hint={t("menu_no_match_hint")} />
        </Card>
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

/** Min–max price, in each store's own currency (one range per currency, never mixed). */
function priceRange(items: MenuItem[], m: MoneyApi): string {
  const byCurrency = new Map<string, number[]>();
  for (const i of items) {
    const c = m.currencyOf(i.venueId);
    byCurrency.set(c, [...(byCurrency.get(c) ?? []), ...i.variants.map((v) => v.priceCents)]);
  }
  const ranges = [...byCurrency.entries()]
    .filter(([, prices]) => prices.length > 0)
    .map(([c, prices]) => {
      const min = Math.min(...prices);
      const max = Math.max(...prices);
      return min === max ? m.fmtIn(c, min) : `${m.fmtIn(c, min)}–${m.fmtIn(c, max)}`;
    });
  return ranges.length ? ranges.join(" · ") : "—";
}

function ItemRow({ row }: { row: MergedItem }) {
  const t = useT();
  const { name, nameAlt } = useI18n();
  const { combined, venues, nameOf, colorOf } = useStores();
  const { item, copies } = row;
  const m = useMoney();
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
        <span className="block truncate text-xs text-neutral-500">
          {[item.brand, item.subcategory, item.size].filter(Boolean).join(" · ") || nameAlt(item.nameFr, item.nameEn)}
          {item.barcode && <span className="ml-2 font-mono text-neutral-400">{item.barcode}</span>}
        </span>
      </span>
      {combined && copies.length > 1 ? (
        // "All stores": each store's own price (and whether it is on its menu)
        <span className="flex shrink-0 flex-col items-end gap-0.5">
          {copies.map((c) => (
            <span key={c.venueId} className="inline-flex items-center gap-1.5 text-xs tabular-nums text-neutral-600">
              <span className="h-2 w-2 rounded-full" style={{ backgroundColor: colorOf(c.venueId) }} />
              {nameOf(c.venueId)}
              <span className={c.active ? "font-medium text-ink" : "text-neutral-500 line-through"}>{priceRange([c], m)}</span>
            </span>
          ))}
        </span>
      ) : (
        <span className="shrink-0 text-sm font-medium tabular-nums">{priceRange(copies, m)}</span>
      )}
    </div>
  );
}

function Thumb({ item }: { item: MenuItem }) {
  const t = useT();
  if (item.photoVersion !== null) {
    const ai = item.photoSource === "ai_generated" || item.photoSource === "ai_enhanced";
    const aiLabel = item.photoSource === "ai_enhanced" ? t("menu_ai_enhanced") : t("menu_ai_generated");
    return (
      <span className="relative h-11 w-11 shrink-0">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={`/v1/menu/items/${item.id}/photo?venue=${encodeURIComponent(item.venueId)}&v=${item.photoVersion}`}
          alt={ai ? aiLabel : ""}
          loading="lazy"
          className="h-11 w-11 rounded-lg bg-neutral-100 object-cover"
        />
        {ai && (
          // transparency: an AI-made or AI-retouched photo says so
          <span
            title={aiLabel}
            aria-label={aiLabel}
            className="absolute -bottom-1 -right-1 rounded bg-ink px-1 text-[9px] font-bold leading-4 text-white ring-2 ring-white"
          >
            {t("menu_ai_badge")}
          </span>
        )}
      </span>
    );
  }
  return (
    <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-neutral-100 text-sm font-semibold text-neutral-400">
      {(item.nameEn[0] ?? "?").toUpperCase()}
    </span>
  );
}
