"use client";

// The Stock page's other tabs: reorder suggestions (with CSV/PDF/Excel
// export), counts submitted at the store (who, when, variances), and
// deliveries received at the store. Quantities only — no currency.

import { Fragment, useEffect, useState } from "react";
import { ChevronDown, ChevronRight, ClipboardList, PackageCheck } from "lucide-react";
import { useApi } from "@/lib/hooks";
import { useFmt, useT } from "@/lib/i18n/context";
import type { ReorderResponse, ReorderRow, StockCountsResponse, StockReceiptsResponse } from "@/lib/types";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, T, type ExportDoc } from "@/lib/export/doc";
import { ExportMenu } from "@/components/export-menu";
import { Kpi } from "@/components/kpi";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { StoreTag } from "@/components/store-breakdown";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { get } from "@/lib/api";
import { scopeApiPath, useStoreId } from "@/lib/store";
import { CatalogFilterBar, Pager, useCatalogFilters, useCategoryNames } from "@/components/catalog-filters";

const REORDER_PAGE = 50;

const DAYS_KEY = "stock.reorder.days";
const COVER_KEY = "stock.reorder.cover";

function remembered(key: string, fallback: number, min: number, max: number): number {
  try {
    const n = Number(window.localStorage.getItem(key));
    return Number.isInteger(n) && n >= min && n <= max ? n : fallback;
  } catch {
    return fallback;
  }
}

function remember(key: string, n: number) {
  try {
    window.localStorage.setItem(key, String(n));
  } catch {
    /* per-browser convenience only */
  }
}

/** Reorder suggestions: avg daily sales over 14–28 days × days to cover, less on hand. */
export function ReorderPanel() {
  const t = useT();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const [days, setDays] = useState(28);
  const [cover, setCover] = useState(14);
  const [coverText, setCoverText] = useState("14");
  const [onlyToOrder, setOnlyToOrderRaw] = useState(true);
  const filters = useCatalogFilters(REORDER_PAGE);
  const categoryName = useCategoryNames();
  const storeId = useStoreId();
  const setOnlyToOrder = (v: boolean) => {
    setOnlyToOrderRaw(v);
    filters.setOffset(0);
  };
  const base = `/v1/stock/reorder-suggestions?days=${days}&cover=${cover}${onlyToOrder ? "&only=to-order" : ""}`;
  useEffect(() => {
    const d = remembered(DAYS_KEY, 28, 14, 28);
    const c = remembered(COVER_KEY, 14, 1, 120);
    setDays(d);
    setCover(c);
    setCoverText(String(c));
  }, []);
  const { data, error, isLoading, mutate } = useApi<ReorderResponse>(`${base}&${filters.pageQuery}`);
  const rows = data?.rows ?? [];

  const buildDoc = async (): Promise<ExportDoc | null> => {
    if (!data) return null;
    // every matching row, not just the page on screen
    const all = await get<ReorderResponse>(
      scopeApiPath(`${base}&limit=10000${filters.filterQuery ? `&${filters.filterQuery}` : ""}`, storeId)
    );
    const rows = all.rows;
    return {
      ...meta("reorder"),
      rangeLabel: t("reorder_as_of", { days: data.days, cover: data.coverDays }),
      reportTitle: t("reorder_title"),
      notes: [t("reorder_sub")],
      kpis: [
        { label: t("reorder_kpi_products"), value: String(data.toOrder) },
        { label: t("reorder_kpi_units"), value: String(data.units) },
      ],
      sections: [
        {
          title: t("reorder_title"),
          columns: storeExport.withStore<ReorderRow>([
            col.text(t("stock_col_product"), (r) => r.name, { width: 36 }),
            col.text(t("stock_col_barcode"), (r) => r.barcode ?? "", { width: 16 }),
            col.int(t("stock_col_on_hand"), (r) => r.onHand),
            col.int(t("reorder_col_sold", { n: data.days }), (r) => r.soldInWindow),
            col.text(t("reorder_col_avg"), (r) => r.avgDaily.toFixed(2), { align: "right", width: 10 }),
            col.int(t("reorder_col_target"), (r) => r.target),
            col.int(t("reorder_col_suggested"), (r) => r.suggested),
          ]),
          rows,
          total: [
            T(t("col_total")),
            ...(storeExport.combined ? [T("")] : []),
            T(""),
            Int(rows.reduce((n, r) => n + r.onHand, 0)),
            Int(rows.reduce((n, r) => n + r.soldInWindow, 0)),
            T(""),
            Int(rows.reduce((n, r) => n + r.target, 0)),
            Int(rows.reduce((n, r) => n + r.suggested, 0)),
          ],
        },
      ],
    };
  };

  const setCoverFrom = (text: string) => {
    const clean = text.replace(/[^0-9]/g, "").slice(0, 3);
    setCoverText(clean);
    const n = Number(clean);
    if (Number.isInteger(n) && n >= 1 && n <= 120) {
      setCover(n);
      remember(COVER_KEY, n);
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <div className="flex flex-wrap items-end gap-4 px-4 py-3">
          <div className="min-w-60 flex-1">
            <div className="text-sm font-semibold text-ink">{t("reorder_title")}</div>
            <p className="text-xs text-neutral-500">{t("reorder_sub")}</p>
          </div>
          <div className="space-y-1">
            <Label htmlFor="reorder-days">{t("reorder_days")}</Label>
            <div className="flex overflow-hidden rounded-lg border border-neutral-200" id="reorder-days" role="group">
              {[14, 21, 28].map((d) => (
                <button
                  key={d}
                  type="button"
                  aria-pressed={days === d}
                  onClick={() => {
                    setDays(d);
                    remember(DAYS_KEY, d);
                  }}
                  className={cn(
                    "px-3 py-1.5 text-sm tabular-nums",
                    days === d ? "bg-ink text-white" : "bg-surface text-neutral-600 hover:bg-neutral-100"
                  )}
                >
                  {d} {t("reorder_days_unit")}
                </button>
              ))}
            </div>
          </div>
          <div className="w-28 space-y-1">
            <Label htmlFor="reorder-cover">{t("reorder_cover")}</Label>
            <Input id="reorder-cover" inputMode="numeric" value={coverText} onChange={(e) => setCoverFrom(e.target.value)} />
          </div>
          <label className="flex items-center gap-2 pb-1.5 text-sm text-neutral-600">
            <Switch checked={onlyToOrder} onCheckedChange={setOnlyToOrder} aria-label={t("reorder_only")} />
            {t("reorder_only")}
          </label>
          <ExportMenu build={buildDoc} disabled={!data || !data.retail} />
        </div>
      </Card>

      {isLoading && !data ? (
        <Card>
          <TableSkeleton rows={6} />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : data ? (
        <>
          <div className="grid grid-cols-2 gap-3">
            <Kpi label={t("reorder_kpi_products")} value={String(data.toOrder)} accent />
            <Kpi label={t("reorder_kpi_units")} value={String(data.units)} />
          </div>
          <Card>
            <CatalogFilterBar
              filters={filters}
              facets={data.facets}
              categoryName={categoryName}
              searchLabel={t("stock_search")}
            />
            {rows.length === 0 ? (
              <EmptyState title={t("reorder_none")} hint={t("reorder_none_hint")} />
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("stock_col_product")}</TableHead>
                      <TableHead className="text-right">{t("stock_col_on_hand")}</TableHead>
                      <TableHead className="hidden text-right md:table-cell">{t("reorder_col_sold", { n: data.days })}</TableHead>
                      <TableHead className="hidden text-right md:table-cell">{t("reorder_col_avg")}</TableHead>
                      <TableHead className="hidden text-right md:table-cell">{t("reorder_col_target")}</TableHead>
                      <TableHead className="text-right">{t("reorder_col_suggested")}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((r) => (
                      <TableRow key={`${r.venueId}/${r.itemId}`} className={cn(r.low && "bg-amber-50/60")}>
                        <TableCell>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="font-medium">{r.name}</span>
                            {r.low && <Badge variant="warning">{t("stock_low")}</Badge>}
                            <StoreTag venueId={r.venueId} />
                          </div>
                        </TableCell>
                        <TableCell className={cn("text-right tabular-nums", r.onHand < 0 && "text-red-700")}>{r.onHand}</TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">{r.soldInWindow}</TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">{r.avgDaily.toFixed(2)}</TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">{r.target}</TableCell>
                        <TableCell className="text-right">
                          <span className={cn("font-semibold tabular-nums", r.suggested > 0 && "text-accent")}>
                            {r.suggested}
                          </span>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
            <Pager total={data.total ?? rows.length} offset={filters.offset} pageSize={REORDER_PAGE} onOffset={filters.setOffset} />
          </Card>
        </>
      ) : null}
    </div>
  );
}

/** Counts submitted at the store: who, when, variances (expand for the lines). */
export function CountsPanel() {
  const t = useT();
  const fmt = useFmt();
  const { data, error, isLoading, mutate } = useApi<StockCountsResponse>("/v1/stock/counts");
  const [open, setOpen] = useState<string | null>(null);

  if (isLoading && !data)
    return (
      <Card>
        <TableSkeleton rows={4} />
      </Card>
    );
  if (error)
    return (
      <Card>
        <ErrorState message={error.message} onRetry={() => mutate()} />
      </Card>
    );
  if (!data) return null;
  return (
    <Card>
      <div className="border-b border-neutral-200/70 px-4 py-3">
        <div className="text-sm font-semibold text-ink">{t("counts_title")}</div>
        <p className="text-xs text-neutral-500">{t("counts_sub")}</p>
      </div>
      {data.counts.length === 0 ? (
        <EmptyState title={t("counts_empty")} hint={t("counts_empty_hint")} />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableBody>
              {data.counts.map((c) => {
                const key = `${c.venueId}/${c.countId}`;
                const expanded = open === key;
                return (
                  <Fragment key={key}>
                    <TableRow className="cursor-pointer" onClick={() => setOpen(expanded ? null : key)}>
                      <TableCell className="w-8">
                        {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap items-center gap-1.5">
                          <ClipboardList className="h-4 w-4 text-neutral-400" />
                          <span className="font-medium">{c.name || t("stock_kind_COUNT")}</span>
                          <StoreTag venueId={c.venueId} />
                        </div>
                        <div className="text-xs text-neutral-500">
                          {fmt.dateTime(c.submittedAt)}
                          {c.submittedBy ? ` · ${t("counts_by", { who: c.submittedBy })}` : ""}
                          {c.approvedBy ? ` · ${t("counts_approved", { who: c.approvedBy })}` : ""}
                        </div>
                      </TableCell>
                      <TableCell className="text-right text-sm text-neutral-600">{t("counts_products", { n: c.products })}</TableCell>
                      <TableCell className="text-right">
                        {c.varianceLines > 0 ? (
                          <Badge variant="warning">
                            {t("counts_variances", { n: c.varianceLines })} ·{" "}
                            {c.varianceUnits > 0 ? `+${c.varianceUnits}` : c.varianceUnits}
                          </Badge>
                        ) : (
                          <span className="text-xs text-neutral-500">{t("counts_no_variance")}</span>
                        )}
                      </TableCell>
                    </TableRow>
                    {expanded &&
                      c.lines.map((l) => (
                        <TableRow key={`${key}/${l.itemId}`} className={cn("bg-surface-alt/60", (l.variance ?? 0) !== 0 && "bg-amber-50/60")}>
                          <TableCell />
                          <TableCell className="pl-8 text-sm">{l.name}</TableCell>
                          <TableCell className="text-right text-sm tabular-nums">
                            {t("stock_col_counted")} {l.counted} · {t("counts_col_expected")} {l.expected ?? "—"}
                          </TableCell>
                          <TableCell className="text-right text-sm font-semibold tabular-nums">
                            {l.variance == null ? "—" : l.variance > 0 ? `+${l.variance}` : l.variance}
                          </TableCell>
                        </TableRow>
                      ))}
                  </Fragment>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </Card>
  );
}

/** Deliveries received at the store. */
export function DeliveriesPanel() {
  const t = useT();
  const fmt = useFmt();
  const { data, error, isLoading, mutate } = useApi<StockReceiptsResponse>("/v1/stock/receipts");
  if (isLoading && !data)
    return (
      <Card>
        <TableSkeleton rows={4} />
      </Card>
    );
  if (error)
    return (
      <Card>
        <ErrorState message={error.message} onRetry={() => mutate()} />
      </Card>
    );
  if (!data) return null;
  return (
    <Card>
      <div className="border-b border-neutral-200/70 px-4 py-3 text-sm font-semibold text-ink">{t("deliveries_title")}</div>
      {data.receipts.length === 0 ? (
        <EmptyState title={t("deliveries_empty")} hint={t("deliveries_empty_hint")} />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("deliveries_col_when")}</TableHead>
                <TableHead>{t("deliveries_col_supplier")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("deliveries_col_reference")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("deliveries_col_by")}</TableHead>
                <TableHead className="text-right">{t("deliveries_col_units")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.receipts.map((r) => (
                <TableRow key={`${r.venueId}/${r.receiptId}`}>
                  <TableCell className="whitespace-nowrap tabular-nums">
                    <div className="flex items-center gap-1.5">
                      <PackageCheck className="h-4 w-4 text-neutral-400" />
                      {fmt.dateTime(r.receivedAt)}
                      <StoreTag venueId={r.venueId} />
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="font-medium">{r.supplier || "—"}</div>
                    <div className="text-xs text-neutral-500">
                      {r.lines.map((l) => `${l.name} × ${l.qty}`).join(" · ")}
                    </div>
                  </TableCell>
                  <TableCell className="hidden md:table-cell">{r.reference || "—"}</TableCell>
                  <TableCell className="hidden md:table-cell">{r.receivedBy ?? "—"}</TableCell>
                  <TableCell className="text-right font-semibold tabular-nums">{r.units}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </Card>
  );
}
