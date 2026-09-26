"use client";

import { useState } from "react";
import { History, PackagePlus, SlidersHorizontal, Target } from "lucide-react";
import { get, post, put } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useFmt, useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { scopeApiPath, useStores } from "@/lib/store";
import { CatalogFilterBar, Pager, useCatalogFilters, useCategoryNames } from "@/components/catalog-filters";
import { toast, toastError } from "@/lib/toast";
import type { StockMovementsResponse, StockResponse, StockRow } from "@/lib/types";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, T, type ExportDoc } from "@/lib/export/doc";
import { ExportMenu } from "@/components/export-menu";
import { PageHeader } from "@/components/page-header";
import { Kpi } from "@/components/kpi";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { StoreTag } from "@/components/store-breakdown";
import { isRetail } from "@/components/money-scope";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { CountsPanel, DeliveriesPanel, ReorderPanel } from "@/components/stock/store-panels";
import { cn } from "@/lib/utils";

type Action = { kind: "RECEIVED" | "ADJUSTMENT" | "REORDER"; row: StockRow };

/**
 * Stock of the retail stores (restaurants don't track it). The cloud computes
 * on hand = last count + received − sold ± adjustments + returns, from the
 * synced sales and the counts / deliveries done at the store (stock app or
 * counter); deliveries and adjustments can also be recorded here, and never
 * flow down to the store. With one retail store picked, each row can be
 * received, adjusted and given a reorder level; "All stores" lists every
 * retail store's products. Tabs: on hand, reorder suggestions, the store's
 * counts, and its deliveries. A store can carry ~5,000 products: the list is
 * paged by the API with a search and the 2-way category → subcategory filter
 * plus size; the KPIs stay the whole scope's and the export fetches every
 * matching row.
 */
const PAGE_SIZE = 50;

export default function StockPage() {
  const t = useT();
  const fmt = useFmt();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const { store, combined, storeId } = useStores();
  const filters = useCatalogFilters(PAGE_SIZE);
  const [lowOnly, setLowOnlyRaw] = useState(false);
  const setLowOnly = (v: boolean) => {
    setLowOnlyRaw(v);
    filters.setOffset(0);
  };
  const lowParam = lowOnly ? "&low=true" : "";
  const { data, error, isLoading, mutate } = useApi<StockResponse>(`/v1/stock?${filters.pageQuery}${lowParam}`);
  const [action, setAction] = useState<Action | null>(null);
  const [tab, setTab] = useState("on-hand");
  const canRecord = !!store && isRetail(store);

  const rows = data?.rows ?? [];
  const products = data ? data.byVenue.reduce((n, v) => n + v.products, 0) : 0;
  const total = data?.total ?? rows.length;
  const categoryName = useCategoryNames();

  const buildDoc = async (): Promise<ExportDoc | null> => {
    if (!data) return null;
    // every matching row, not just the page on screen
    const all = await get<StockResponse>(
      scopeApiPath(`/v1/stock?limit=10000${filters.filterQuery ? `&${filters.filterQuery}` : ""}${lowParam}`, storeId)
    );
    const rows = all.rows;
    return {
      ...meta("stock"),
      rangeLabel: t("stock_as_of"),
      reportTitle: t("stock_title"),
      kpis: [
        { label: t("stock_kpi_products"), value: String(products) },
        { label: t("stock_kpi_on_hand"), value: String(data.totalOnHand) },
        { label: t("stock_kpi_low"), value: String(data.lowCount) },
      ],
      sections: [
        {
          title: t("stock_title"),
          columns: storeExport.withStore<StockRow>([
            col.text(t("stock_col_product"), (r) => r.name, { width: 36 }),
            col.text(t("stock_col_barcode"), (r) => r.barcode ?? "", { width: 16 }),
            col.text(t("catalog_subcategory"), (r) => r.subcategory ?? "", { width: 16 }),
            col.text(t("catalog_size"), (r) => r.size ?? "", { width: 12 }),
            col.int(t("stock_col_received"), (r) => r.received),
            col.int(t("stock_col_sold"), (r) => r.sold),
            col.int(t("stock_col_adjusted"), (r) => r.adjusted),
            col.int(t("stock_col_returned"), (r) => r.returned ?? 0),
            col.text(t("stock_col_counted"), (r) => r.countedQty ?? "", { align: "right", width: 10 }),
            col.int(t("stock_col_on_hand"), (r) => r.onHand),
            col.text(t("stock_col_reorder"), (r) => (r.reorderLevel ?? "") as string | number, { align: "right", width: 10 }),
            col.text(t("stock_col_low"), (r) => (r.low ? t("stock_yes") : ""), { width: 8 }),
          ]),
          rows,
          total: [
            T(t("col_total")),
            ...(storeExport.combined ? [T("")] : []),
            T(""),
            T(""),
            T(""),
            Int(rows.reduce((n, r) => n + r.received, 0)),
            Int(rows.reduce((n, r) => n + r.sold, 0)),
            Int(rows.reduce((n, r) => n + r.adjusted, 0)),
            Int(rows.reduce((n, r) => n + (r.returned ?? 0), 0)),
            T(""),
            Int(rows.reduce((n, r) => n + r.onHand, 0)),
            T(""),
            T(String(rows.filter((r) => r.low).length)),
          ],
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("stock_title")}
        sub={t("stock_sub")}
        action={tab === "on-hand" ? <ExportMenu build={buildDoc} disabled={!data || !data.retail} /> : undefined}
      />

      {isLoading && !data ? (
        <Card>
          <TableSkeleton rows={6} />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : data && !data.retail ? (
        <Card>
          <EmptyState title={t("stock_not_retail")} hint={t("stock_not_retail_hint")} />
        </Card>
      ) : data ? (
        <Tabs value={tab} onValueChange={setTab} className="space-y-4">
          <TabsList>
            <TabsTrigger value="on-hand">{t("stock_tab_on_hand")}</TabsTrigger>
            <TabsTrigger value="reorder">{t("stock_tab_reorder")}</TabsTrigger>
            <TabsTrigger value="counts">{t("stock_tab_counts")}</TabsTrigger>
            <TabsTrigger value="deliveries">{t("stock_tab_deliveries")}</TabsTrigger>
          </TabsList>
          <TabsContent value="reorder">
            <ReorderPanel />
          </TabsContent>
          <TabsContent value="counts">
            <CountsPanel />
          </TabsContent>
          <TabsContent value="deliveries">
            <DeliveriesPanel />
          </TabsContent>
          <TabsContent value="on-hand" className="space-y-4">
          <div className="grid grid-cols-3 gap-3">
            <Kpi label={t("stock_kpi_products")} value={String(products)} />
            <Kpi label={t("stock_kpi_on_hand")} value={String(data.totalOnHand)} accent />
            <Kpi label={t("stock_kpi_low")} value={String(data.lowCount)} className={data.lowCount > 0 ? "border-amber-300" : ""} />
          </div>

          {!canRecord && combined && (
            <p className="rounded-lg border border-neutral-200 bg-surface-alt px-4 py-2.5 text-sm text-neutral-600">
              {t("stock_pick_store")}
            </p>
          )}

          <Card>
            <CatalogFilterBar
              filters={filters}
              facets={data.facets}
              categoryName={categoryName}
              searchLabel={t("stock_search")}
            >
              <label className="flex items-center gap-2 text-sm text-neutral-600">
                <Switch checked={lowOnly} onCheckedChange={setLowOnly} aria-label={t("stock_low_only")} />
                {t("stock_low_only")}
              </label>
            </CatalogFilterBar>
            {rows.length === 0 ? (
              <EmptyState title={t("stock_empty")} hint={products === 0 ? t("stock_empty_hint") : undefined} />
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("stock_col_product")}</TableHead>
                      <TableHead className="hidden lg:table-cell">{t("stock_col_barcode")}</TableHead>
                      <TableHead className="text-right">{t("stock_col_on_hand")}</TableHead>
                      <TableHead className="text-right">{t("stock_col_reorder")}</TableHead>
                      <TableHead className="hidden text-right md:table-cell">{t("stock_col_received")}</TableHead>
                      <TableHead className="hidden text-right md:table-cell">{t("stock_col_sold")}</TableHead>
                      <TableHead className="hidden text-right md:table-cell">{t("stock_col_adjusted")}</TableHead>
                      {canRecord && <TableHead className="text-right" />}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((r) => (
                      <TableRow key={`${r.venueId}/${r.itemId}`} className={cn(r.low && "bg-amber-50/60")}>
                        <TableCell>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="font-medium">{r.name}</span>
                            <StoreTag venueId={r.venueId} />
                          </div>
                          {(r.subcategory || r.size) && (
                            <div className="text-xs text-neutral-500">{[r.subcategory, r.size].filter(Boolean).join(" · ")}</div>
                          )}
                          {r.countedAt && (
                            <div className="text-xs text-neutral-500">
                              {t("stock_counted_on", { qty: r.countedQty ?? 0, when: fmt.dateTime(r.countedAt) })}
                            </div>
                          )}
                        </TableCell>
                        <TableCell className="hidden font-mono text-xs text-neutral-500 lg:table-cell">
                          {r.barcode ?? "—"}
                        </TableCell>
                        <TableCell className="text-right">
                          <span className="inline-flex items-center gap-1.5">
                            {r.low && <Badge variant="warning">{t("stock_low")}</Badge>}
                            <span
                              className={cn(
                                "font-semibold tabular-nums",
                                r.low && "text-attention",
                                r.onHand < 0 && "text-red-700"
                              )}
                            >
                              {r.onHand}
                            </span>
                          </span>
                        </TableCell>
                        <TableCell className="text-right tabular-nums text-neutral-600">
                          {canRecord ? (
                            <button
                              type="button"
                              className="rounded px-1.5 py-0.5 hover:bg-neutral-100"
                              onClick={() => setAction({ kind: "REORDER", row: r })}
                              aria-label={`${t("stock_set_reorder")} · ${r.name}`}
                            >
                              {r.reorderLevel ?? "—"}
                            </button>
                          ) : (
                            (r.reorderLevel ?? "—")
                          )}
                        </TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">{r.received}</TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">{r.sold}</TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">
                          {r.adjusted}
                          {!!r.returned && <span className="ml-1 text-xs text-neutral-500">(+{r.returned})</span>}
                        </TableCell>
                        {canRecord && (
                          <TableCell className="text-right">
                            <div className="flex justify-end gap-1.5">
                              <Button size="sm" variant="secondary" onClick={() => setAction({ kind: "RECEIVED", row: r })}>
                                <PackagePlus /> {t("stock_receive")}
                              </Button>
                              <Button size="sm" variant="ghost" onClick={() => setAction({ kind: "ADJUSTMENT", row: r })}>
                                <SlidersHorizontal /> {t("stock_adjust")}
                              </Button>
                            </div>
                          </TableCell>
                        )}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
            <Pager total={total} offset={filters.offset} pageSize={PAGE_SIZE} onOffset={filters.setOffset} />
          </Card>
          {rows.some((r) => r.onHand < 0) && <p className="text-xs text-neutral-500">{t("stock_negative_note")}</p>}
          </TabsContent>
        </Tabs>
      ) : null}

      {action && store && (
        <StockDialog
          action={action}
          storeId={store.id}
          onClose={() => setAction(null)}
          onSaved={() => {
            setAction(null);
            mutate();
            toast("success", t("stock_saved"));
          }}
        />
      )}
    </div>
  );
}

function StockDialog({
  action,
  storeId,
  onClose,
  onSaved,
}: {
  action: Action;
  storeId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const fmt = useFmt();
  const { kind, row } = action;
  const reorder = kind === "REORDER";
  const [qty, setQty] = useState(reorder ? (row.reorderLevel == null ? "" : String(row.reorderLevel)) : "");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const venue = `venue=${encodeURIComponent(storeId)}`;
  const history = useApi<StockMovementsResponse>(
    reorder ? null : `/v1/stock/movements?itemId=${encodeURIComponent(row.itemId)}`
  );

  const n = qty.trim() === "" ? null : Number(qty);
  const valid = reorder
    ? n === null || (Number.isInteger(n) && n >= 0)
    : n !== null && Number.isInteger(n) && (kind === "RECEIVED" ? n > 0 : n !== 0);

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    try {
      if (reorder) {
        await put(`/v1/stock/reorder?${venue}`, { itemId: row.itemId, reorderLevel: n });
      } else {
        await post(`/v1/stock/movements?${venue}`, { itemId: row.itemId, kind, qty: n, note });
      }
      onSaved();
    } catch (e) {
      toastError(e);
    } finally {
      setBusy(false);
    }
  };

  const titleKey: MsgKey = reorder ? "stock_reorder_title" : kind === "RECEIVED" ? "stock_receive_title" : "stock_adjust_title";
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogTitle className="flex items-center gap-2">
          {reorder ? <Target className="h-4 w-4" /> : kind === "RECEIVED" ? <PackagePlus className="h-4 w-4" /> : <SlidersHorizontal className="h-4 w-4" />}
          {t(titleKey, { name: row.name })}
        </DialogTitle>
        <DialogDescription>
          {reorder ? t("stock_reorder_hint") : kind === "ADJUSTMENT" ? t("stock_qty_adjust_hint") : `${t("stock_col_on_hand")}: ${row.onHand}`}
        </DialogDescription>
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor="stock-qty">{reorder ? t("stock_set_reorder") : t("stock_qty")}</Label>
            <Input
              id="stock-qty"
              autoFocus
              inputMode="numeric"
              value={qty}
              onChange={(e) => setQty(e.target.value.replace(kind === "ADJUSTMENT" ? /[^0-9-]/g : /[^0-9]/g, ""))}
            />
          </div>
          {!reorder && (
            <div className="space-y-1.5">
              <Label htmlFor="stock-note">{t("stock_note")}</Label>
              <Input id="stock-note" value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
            </div>
          )}
          {!reorder && history.data && history.data.movements.length > 0 && (
            <div className="rounded-lg border border-neutral-200 bg-surface-alt px-3 py-2">
              <div className="mb-1 flex items-center gap-1.5 text-xs font-semibold text-neutral-600">
                <History className="h-3.5 w-3.5" /> {t("stock_history")}
              </div>
              <ul className="max-h-32 space-y-0.5 overflow-y-auto text-xs text-neutral-600">
                {history.data.movements.slice(0, 8).map((m) => (
                  <li key={m.id} className="flex gap-2">
                    <span className="w-24 shrink-0 tabular-nums">{fmt.dateTime(m.createdAt)}</span>
                    <span className="w-20 shrink-0">{t(`stock_kind_${m.kind}` as MsgKey)}</span>
                    <span className="w-10 shrink-0 text-right font-medium tabular-nums">{m.qty > 0 ? `+${m.qty}` : m.qty}</span>
                    <span className="truncate">{m.note}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <DialogFooter>
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={!valid || busy}>
              {t("stock_save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
