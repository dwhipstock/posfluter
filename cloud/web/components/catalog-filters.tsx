"use client";

// Search, filters and paging for the product lists (Products, Stock, reorder
// suggestions) — a retail store can carry ~5,000 products, so the lists are
// paged by the API and never rendered whole. The filter is "2-way": a
// category, then one of ITS subcategories (style / varietal / type), plus a
// size / pack; they combine, and Reset clears them all. The API returns the
// values present under the other filters (`facets`), so a dropdown never
// offers an empty choice.

import { useEffect, useMemo, useState } from "react";
import { ChevronLeft, ChevronRight, RotateCcw, Search } from "lucide-react";
import { useI18n, useT } from "@/lib/i18n/context";
import { useApi } from "@/lib/hooks";
import type { CatalogFacets, MenuResponse } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

const ALL = "__all";

export interface CatalogFilters {
  /** The search as typed (the query uses a debounced copy). */
  text: string;
  setText: (v: string) => void;
  category: string | null;
  setCategory: (v: string | null) => void;
  subcategory: string | null;
  setSubcategory: (v: string | null) => void;
  size: string | null;
  setSize: (v: string | null) => void;
  offset: number;
  setOffset: (n: number) => void;
  pageSize: number;
  /** True when anything is filtered. */
  active: boolean;
  reset: () => void;
  /** `q=…&category=…&subcategory=…&size=…` for the API (no paging). */
  filterQuery: string;
  /** filterQuery plus `limit` / `offset` for the current page. */
  pageQuery: string;
}

export function useCatalogFilters(pageSize = 50): CatalogFilters {
  const [text, setTextRaw] = useState("");
  const [q, setQ] = useState("");
  const [category, setCategoryRaw] = useState<string | null>(null);
  const [subcategory, setSubcategoryRaw] = useState<string | null>(null);
  const [size, setSizeRaw] = useState<string | null>(null);
  const [offset, setOffset] = useState(0);

  // type-ahead without a request per keystroke
  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(id);
  }, [text]);
  // any filter change starts over at the first page
  useEffect(() => setOffset(0), [q, category, subcategory, size]);

  const filterQuery = useMemo(() => {
    const p = new URLSearchParams();
    if (q) p.set("q", q);
    if (category) p.set("category", category);
    if (subcategory) p.set("subcategory", subcategory);
    if (size) p.set("size", size);
    return p.toString();
  }, [q, category, subcategory, size]);

  return {
    text,
    setText: setTextRaw,
    category,
    // a new category: its own subcategories and sizes apply
    setCategory: (v) => {
      setCategoryRaw(v);
      setSubcategoryRaw(null);
      setSizeRaw(null);
    },
    subcategory,
    setSubcategory: (v) => {
      setSubcategoryRaw(v);
      setSizeRaw(null);
    },
    size,
    setSize: setSizeRaw,
    offset,
    setOffset,
    pageSize,
    active: !!(text.trim() || category || subcategory || size),
    reset: () => {
      setTextRaw("");
      setQ("");
      setCategoryRaw(null);
      setSubcategoryRaw(null);
      setSizeRaw(null);
      setOffset(0);
    },
    filterQuery,
    pageQuery: `limit=${pageSize}&offset=${offset}${filterQuery ? `&${filterQuery}` : ""}`,
  };
}

/** The search box and the category → subcategory + size dropdowns, with Reset. */
export function CatalogFilterBar({
  filters: f,
  facets,
  categoryName,
  searchLabel,
  children,
}: {
  filters: CatalogFilters;
  facets?: CatalogFacets | null;
  /** Display name of a category id. */
  categoryName: (id: string) => string;
  searchLabel: string;
  /** Extra controls (e.g. a "low stock only" switch). */
  children?: React.ReactNode;
}) {
  const t = useT();
  const categories = facets?.categories ?? [];
  const subcategories = facets?.subcategories ?? [];
  const sizes = facets?.sizes ?? [];
  // keep a picked value listed even when the current facets no longer carry it
  const withPicked = (list: { value: string; count: number }[], picked: string | null) =>
    picked && !list.some((x) => x.value === picked) ? [{ value: picked, count: 0 }, ...list] : list;

  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-neutral-200/70 px-4 py-3">
      <div className="relative min-w-56 flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400" />
        <Input
          value={f.text}
          onChange={(e) => f.setText(e.target.value)}
          placeholder={searchLabel}
          className="pl-9"
          aria-label={searchLabel}
        />
      </div>
      <FacetSelect
        label={t("catalog_category")}
        allLabel={t("catalog_all_categories")}
        value={f.category}
        onChange={f.setCategory}
        options={withPicked(categories, f.category)}
        display={categoryName}
      />
      <FacetSelect
        label={t("catalog_subcategory")}
        allLabel={t("catalog_all_subcategories")}
        value={f.subcategory}
        onChange={f.setSubcategory}
        options={withPicked(subcategories, f.subcategory)}
        // the second step of the 2-way filter: a category first
        disabled={!f.category || (subcategories.length === 0 && !f.subcategory)}
      />
      <FacetSelect
        label={t("catalog_size")}
        allLabel={t("catalog_all_sizes")}
        value={f.size}
        onChange={f.setSize}
        options={withPicked(sizes, f.size)}
        disabled={sizes.length === 0 && !f.size}
      />
      {children}
      {f.active && (
        <Button variant="ghost" size="sm" onClick={f.reset}>
          <RotateCcw /> {t("catalog_reset")}
        </Button>
      )}
    </div>
  );
}

function FacetSelect({
  label,
  allLabel,
  value,
  onChange,
  options,
  display = (v) => v,
  disabled = false,
}: {
  label: string;
  allLabel: string;
  value: string | null;
  onChange: (v: string | null) => void;
  options: { value: string; count: number }[];
  display?: (v: string) => string;
  disabled?: boolean;
}) {
  return (
    <Select value={value ?? ALL} onValueChange={(v) => onChange(v === ALL ? null : v)} disabled={disabled}>
      <SelectTrigger className="w-44" aria-label={label}>
        <SelectValue placeholder={allLabel} />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL}>{allLabel}</SelectItem>
        {options.map((o) => (
          <SelectItem key={o.value} value={o.value}>
            {display(o.value)}
            {o.count > 0 && <span className="ml-1.5 text-xs text-neutral-400 tabular-nums">{o.count}</span>}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/**
 * Category display names for the stock lists (whose rows carry only the id):
 * the menu mirror's categories, read with a one-product page.
 */
export function useCategoryNames(): (id: string) => string {
  const { name } = useI18n();
  const { data } = useApi<MenuResponse>("/v1/menu?limit=1");
  return (id: string) => {
    const c = data?.categories.find((x) => x.id === id);
    return c ? name(c.nameFr, c.nameEn) : id;
  };
}

/** "1–50 of 5,000" with previous / next. Hidden when everything fits on one page. */
export function Pager({
  total,
  offset,
  pageSize,
  onOffset,
}: {
  total: number;
  offset: number;
  pageSize: number;
  onOffset: (n: number) => void;
}) {
  const t = useT();
  if (total <= pageSize && offset === 0) return null;
  const from = total === 0 ? 0 : offset + 1;
  const to = Math.min(offset + pageSize, total);
  return (
    <div className="flex items-center justify-end gap-2 border-t border-neutral-200/70 px-4 py-2.5 text-sm text-neutral-600">
      <span className="tabular-nums">
        {t("catalog_range", { from: from.toLocaleString(), to: to.toLocaleString(), total: total.toLocaleString() })}
      </span>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onOffset(Math.max(0, offset - pageSize))}
        disabled={offset === 0}
        aria-label={t("prev")}
      >
        <ChevronLeft /> {t("prev")}
      </Button>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onOffset(offset + pageSize)}
        disabled={offset + pageSize >= total}
        aria-label={t("next")}
      >
        {t("next")} <ChevronRight />
      </Button>
    </div>
  );
}
