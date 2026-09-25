"use client";

import { Suspense, useState } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD, CADSigned } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, type ExportDoc } from "@/lib/export/doc";
import type { Shift, ShiftsReport } from "@/lib/types";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Sheet, SheetBody, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { PageHeader } from "@/components/page-header";
import { StoreBreakdown, StoreTag } from "@/components/store-breakdown";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback } from "@/components/states";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <ShiftsPage />
    </Suspense>
  );
}

function ShiftsPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const { data, error, isLoading, mutate } = useApi<ShiftsReport>(reportKey("/v1/reports/shifts", range));
  const [selected, setSelected] = useState<Shift | null>(null);

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("shifts"),
      reportTitle: t("shifts_title"),
      sections: [
        {
          columns: [
            col.int<Shift>(t("col_shift"), (s) => s.shiftId),
            col.text<Shift>(t("col_status"), (s) => (s.status === "OPEN" ? t("badge_live") : t("badge_closed"))),
            col.text<Shift>(t("shift_opened"), (s) => fmt.dateTime(s.openedAt)),
            col.text<Shift>(t("shift_closed"), (s) => (s.closedAt ? fmt.dateTime(s.closedAt) : t("shift_still_open"))),
            col.money<Shift>(t("shift_revenue"), (s) => s.revenueCents),
            col.int<Shift>(t("shift_checks"), (s) => s.transactionCount),
            col.money<Shift>(t("shift_avg_check"), (s) => s.avgCheckCents),
            col.text<Shift>(t("shift_over_short"), (s) => (s.overShortCents == null ? "—" : CADSigned(s.overShortCents)), {
              align: "right",
              width: 12,
            }),
          ],
          rows: data.rows,
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("shifts_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || data.rows.length === 0} />}
      />
      <DateRangePicker />
      <StoreBreakdown />

      {isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-28 w-full rounded-2xl" />
          ))}
        </div>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : data && data.rows.length > 0 ? (
        <div className="space-y-3">
          {data.rows.map((s) => (
            <button key={`${s.venueId}/${s.shiftId}`} className="block w-full text-left" onClick={() => setSelected(s)}>
              <Card className="p-4 transition-colors hover:border-accent/50">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-semibold">{t("shift_n", { id: s.shiftId })}</span>
                  <StoreTag venueId={s.venueId} />
                  {s.status === "OPEN" ? (
                    <Badge variant="pink">{t("badge_live")}</Badge>
                  ) : (
                    <Badge>{t("badge_closed")}</Badge>
                  )}
                  <span className="ml-auto text-xs text-neutral-500">
                    {fmt.dateTime(s.openedAt)}
                    {s.closedAt ? ` → ${fmt.dateTime(s.closedAt)}` : ` → ${t("shift_now")}`}
                  </span>
                </div>
                <div className="mt-3 grid grid-cols-4 gap-2">
                  <MiniStat label={t("shift_revenue")} value={CAD(s.revenueCents)} strong />
                  <MiniStat label={t("shift_checks")} value={String(s.transactionCount)} />
                  <MiniStat label={t("shift_avg")} value={CAD(s.avgCheckCents)} />
                  <MiniStat
                    label={t("shift_over_short")}
                    value={s.overShortCents === null ? "—" : CADSigned(s.overShortCents)}
                    tone={
                      s.overShortCents === null || s.overShortCents === 0
                        ? undefined
                        : "alert"
                    }
                  />
                </div>
              </Card>
            </button>
          ))}
        </div>
      ) : (
        <Card>
          <EmptyState title={t("shifts_empty")} hint={t("shifts_empty_hint")} />
        </Card>
      )}

      <ShiftSheet shift={selected} onClose={() => setSelected(null)} />
    </div>
  );
}

function MiniStat({
  label,
  value,
  strong = false,
  tone,
}: {
  label: string;
  value: string;
  strong?: boolean;
  tone?: "alert";
}) {
  return (
    <div>
      <div className="text-[10px] font-medium uppercase tracking-wider text-neutral-400">{label}</div>
      <div
        className={cn(
          "mt-0.5 text-sm tabular-nums",
          strong ? "font-semibold" : "font-medium",
          tone === "alert" && "text-red-600"
        )}
      >
        {value}
      </div>
    </div>
  );
}

function ShiftSheet({ shift, onClose }: { shift: Shift | null; onClose: () => void }) {
  const t = useT();
  const fmt = useFmt();
  return (
    <Sheet open={shift !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent>
        {shift && (
          <>
            <SheetHeader>
              <div className="flex items-center gap-2">
                <SheetTitle>{t("shift_n", { id: shift.shiftId })}</SheetTitle>
                {shift.status === "OPEN" ? <Badge variant="pink">{t("badge_live")}</Badge> : <Badge>{t("badge_closed")}</Badge>}
              </div>
            </SheetHeader>
            <SheetBody className="space-y-5">
              <section className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
                <DetailRow label={t("shift_opened")} value={`${fmt.dateTime(shift.openedAt)} · ${shift.openedBy}`} />
                <DetailRow
                  label={t("shift_closed")}
                  value={shift.closedAt ? `${fmt.dateTime(shift.closedAt)} · ${shift.closedBy ?? ""}` : t("shift_still_open")}
                />
                <DetailRow label={t("shift_revenue")} value={CAD(shift.revenueCents)} strong />
                <DetailRow label={t("shift_checks")} value={String(shift.transactionCount)} />
                <DetailRow label={t("shift_avg_check")} value={CAD(shift.avgCheckCents)} />
                <DetailRow label={t("shift_corkage")} value={CAD(shift.corkageCents)} />
              </section>

              <section>
                <h4 className="mb-2 text-[11px] font-medium uppercase tracking-wider text-neutral-400">
                  {t("shift_tenders")}
                </h4>
                {shift.tenderBreakdown.length > 0 ? (
                  <div className="divide-y divide-neutral-100 rounded-xl border border-neutral-100">
                    {shift.tenderBreakdown.map((row) => (
                      <div key={row.type} className="flex items-center justify-between px-3 py-2.5 text-sm">
                        <span className="text-neutral-600">
                          {t(`tender_${row.type}` as MsgKey)}
                          <span className="ml-1.5 text-xs text-neutral-400">×{row.count}</span>
                        </span>
                        <span className="font-medium tabular-nums">{CAD(row.amountCents)}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-xs text-neutral-500">{t("shift_no_tenders")}</p>
                )}
              </section>

              <section>
                <h4 className="mb-2 text-[11px] font-medium uppercase tracking-wider text-neutral-400">
                  {t("shift_cash_drawer")}
                </h4>
                <div className="space-y-2 rounded-xl border border-neutral-100 px-3 py-2.5 text-sm">
                  <CashRow label={t("shift_opening_float")} value={CAD(shift.openingFloatCents)} />
                  <CashRow
                    label={t("shift_expected_cash")}
                    value={shift.expectedCashCents === null ? "—" : CAD(shift.expectedCashCents)}
                  />
                  <CashRow
                    label={t("shift_counted")}
                    value={shift.closingCountCents === null ? "—" : CAD(shift.closingCountCents)}
                  />
                  <div className="border-t border-neutral-100 pt-2">
                    <CashRow
                      label={t("shift_over_short")}
                      value={shift.overShortCents === null ? "—" : CADSigned(shift.overShortCents)}
                      strong
                      alert={shift.overShortCents !== null && shift.overShortCents !== 0}
                    />
                  </div>
                </div>
              </section>
            </SheetBody>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}

function DetailRow({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div>
      <div className="text-[11px] font-medium uppercase tracking-wider text-neutral-400">{label}</div>
      <div className={cn("mt-0.5 tabular-nums", strong ? "font-semibold" : "")}>{value}</div>
    </div>
  );
}

function CashRow({
  label,
  value,
  strong = false,
  alert = false,
}: {
  label: string;
  value: string;
  strong?: boolean;
  alert?: boolean;
}) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-neutral-600">{label}</span>
      <span className={cn("tabular-nums", strong ? "font-semibold" : "font-medium", alert && "text-red-600")}>
        {value}
      </span>
    </div>
  );
}
