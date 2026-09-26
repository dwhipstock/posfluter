"use client";

import { useMemo, useState } from "react";
import { History, PackagePlus, Search, SlidersHorizontal, Target } from "lucide-react";
import { post, put } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useFmt, useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { useStores } from "@/lib/store";
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
import { cn } from "@/lib/utils";

type Action = { kind: "RECEIVED" | "ADJUSTMENT" | "REORDER"; row: StockRow };

/**
 * Stock of the retail stores (restaurants don't track it). The cloud computes
 * on hand = received − sold ± adjustments from the synced sales; deliveries
 * and adjustments are recorded here and never flow down to the store. With
 * one retail store picked, each row can be received, adjusted and given a
 * reorder level; "All stores" lists every retail store's products.
 */
export default function StockPage() {
  const t = useT();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const { store, combined } = useStores();
  const { data, error, isLoading, mutate } = useApi<StockResponse>("/v1/stock");
  const [q, setQ] = useState("");
  const [lowOnly, setLowOnly] = useState(false);
  const [action, setAction] = useState<Action | null>(null);
  const canRecord = !!store && isRetail(store);

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (data?.rows ?? []).filter(
      (r) =>
        (!lowOnly || r.low) &&
        (!needle || r.name.toLowerCase().includes(needle) || (r.barcode ?? "").includes(needle))
    );
  }, [data, q, lowOnly]);

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("stock"),
      rangeLabel: t("stock_as_of"),
      reportTitle: t("stock_title"),
      kpis: [
        { label: t("stock_kpi_products"), value: String(data.rows.length) },
        { label: t("stock_kpi_on_hand"), value: String(data.totalOnHand) },
        { label: t("stock_kpi_low"), value: String(data.lowCount) },
      ],
      sections: [
        {
          title: t("stock_title"),
          columns: storeExport.withStore<StockRow>([
            col.text(t("stock_col_product"), (r) => r.name, { width: 36 }),
            col.text(t("stock_col_barcode"), (r) => r.barcode ?? "", { width: 16 }),
            col.int(t("stock_col_received"), (r) => r.received),
            col.int(t("stock_col_sold"), (r) => r.sold),
            col.int(t("stock_col_adjusted"), (r) => r.adjusted),
            col.int(t("stock_col_on_hand"), (r) => r.onHand),
            col.text(t("stock_col_reorder"), (r) => (r.reorderLevel ?? "") as string | number, { align: "right", width: 10 }),
            col.text(t("stock_col_low"), (r) => (r.low ? t("stock_yes") : ""), { width: 8 }),
          ]),
          rows,
          total: [
            T(t("col_total")),
            ...(storeExport.combined ? [T("")] : []),
            T(""),
            Int(rows.reduce((n, r) => n + r.received, 0)),
            Int(rows.reduce((n, r) => n + r.sold, 0)),
            Int(rows.reduce((n, r) => n + r.adjusted, 0)),
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
        action={<ExportMenu build={buildDoc} disabled={!data || !data.retail} />}
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
        <>
          <div className="grid grid-cols-3 gap-3">
            <Kpi label={t("stock_kpi_products")} value={String(data.rows.length)} />
            <Kpi label={t("stock_kpi_on_hand")} value={String(data.totalOnHand)} accent />
            <Kpi label={t("stock_kpi_low")} value={String(data.lowCount)} className={data.lowCount > 0 ? "border-amber-300" : ""} />
          </div>

          {!canRecord && combined && (
            <p className="rounded-lg border border-neutral-200 bg-surface-alt px-4 py-2.5 text-sm text-neutral-600">
              {t("stock_pick_store")}
            </p>
          )}

          <Card>
            <div className="flex flex-wrap items-center gap-3 border-b border-neutral-200/70 px-4 py-3">
              <div className="relative min-w-56 flex-1">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400" />
                <Input
                  value={q}
                  onChange={(e) => setQ(e.target.value)}
                  placeholder={t("stock_search")}
                  className="pl-9"
                  aria-label={t("stock_search")}
                />
              </div>
              <label className="flex items-center gap-2 text-sm text-neutral-600">
                <Switch checked={lowOnly} onCheckedChange={setLowOnly} aria-label={t("stock_low_only")} />
                {t("stock_low_only")}
              </label>
            </div>
            {rows.length === 0 ? (
              <EmptyState title={t("stock_empty")} hint={data.rows.length === 0 ? t("stock_empty_hint") : undefined} />
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
                        <TableCell className="hidden text-right tabular-nums md:table-cell">{r.adjusted}</TableCell>
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
          </Card>
          {data.rows.some((r) => r.onHand < 0) && <p className="text-xs text-neutral-500">{t("stock_negative_note")}</p>}
        </>
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
