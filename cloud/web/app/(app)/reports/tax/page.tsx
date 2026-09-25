"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { CAD } from "@/lib/format";
import { useT, useFmt } from "@/lib/i18n/context";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type ExportDoc } from "@/lib/export/doc";
import type { DayRow, TaxReport } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { StoreBreakdown } from "@/components/store-breakdown";
import { useStores } from "@/lib/store";
import type { VenueSummaryRow } from "@/lib/types";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <TaxPage />
    </Suspense>
  );
}

type TaxDay = Omit<DayRow, "byVenue"> & { venueId: string };

function TaxPage() {
  const t = useT();
  const fmt = useFmt();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const { combined, venues, nameOf, colorOf } = useStores();
  const { data, error, isLoading, mutate } = useApi<TaxReport>(reportKey("/v1/reports/tax", range));

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    return {
      ...meta("tax"),
      reportTitle: t("tax_title"),
      notes: [t("tax_note")],
      kpis: [
        { label: t("col_gross"), value: CAD(data.totals.grossCents) },
        { label: t("col_net"), value: CAD(data.totals.netCents) },
        { label: t("col_tax"), value: CAD(data.totals.taxCents) },
        { label: t("col_checks"), value: String(data.totals.checkCount) },
      ],
      sections: [
        ...storeExport.byStore<VenueSummaryRow>(
          [
            col.money(t("col_gross"), (r) => r.grossCents),
            col.money(t("col_net"), (r) => r.netCents),
            col.money(t("col_tax"), (r) => r.taxCents),
            col.int(t("col_checks"), (r) => r.checkCount),
          ],
          data.byVenue,
          [T(t("col_total")), Money(data.totals.grossCents), Money(data.totals.netCents), Money(data.totals.taxCents), Int(data.totals.checkCount)]
        ),
        {
          title: t("tax_title"),
          columns: [
            col.text<TaxDay>(t("col_date"), (r) => fmt.dayYear(r.date)),
            ...storeExport.withStore<TaxDay>([
              col.money(t("col_gross"), (r) => r.grossCents),
              col.money(t("col_net"), (r) => r.netCents),
              col.money(t("col_tax"), (r) => r.taxCents),
              col.int(t("col_checks"), (r) => r.checkCount),
            ]),
          ],
          // "All stores": each day once per store (store column); one store: its days
          rows: combined
            ? data.rows.flatMap((d) => d.byVenue.map((v) => ({ ...v, date: d.date })))
            : data.rows.map((d) => ({ ...d, venueId: "" })),
          total: [
            T(t("col_total")),
            ...(combined ? [T("")] : []),
            Money(data.totals.grossCents),
            Money(data.totals.netCents),
            Money(data.totals.taxCents),
            Int(data.totals.checkCount),
          ],
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("tax_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || data.rows.length === 0} />}
      />
      <DateRangePicker />
      {data && <StoreBreakdown rows={data.byVenue} />}

      <Card>
        <div className="flex flex-wrap items-center gap-2 border-b border-neutral-100 px-5 py-3">
          {data && <Badge variant="pink">{t("tax_included_badge", { rate: data.ratePercent })}</Badge>}
          <span className="text-xs text-neutral-500">{t("tax_note")}</span>
        </div>
        {isLoading ? (
          <TableSkeleton rows={6} />
        ) : error ? (
          <ErrorState message={error.message} onRetry={() => mutate()} />
        ) : data && data.rows.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("col_date")}</TableHead>
                <TableHead className="text-right">{t("col_gross")}</TableHead>
                <TableHead className="text-right">{t("col_net")}</TableHead>
                <TableHead className="text-right">{t("col_tax")}</TableHead>
                {combined &&
                  venues.map((v) => (
                    <TableHead key={v.id} className="hidden text-right md:table-cell">
                      <span className="inline-flex items-center gap-1.5">
                        <span className="h-2 w-2 rounded-full" style={{ backgroundColor: colorOf(v.id) }} />
                        {t("tax_of_store", { store: nameOf(v.id) })}
                      </span>
                    </TableHead>
                  ))}
                <TableHead className="text-right">{t("col_checks")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.date}>
                  <TableCell className="font-medium">{fmt.day(r.date)}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.grossCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.netCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{CAD(r.taxCents)}</TableCell>
                  {combined &&
                    venues.map((v) => (
                      <TableCell key={v.id} className="hidden text-right tabular-nums text-neutral-600 md:table-cell">
                        {CAD(r.byVenue.find((x) => x.venueId === v.id)?.taxCents ?? 0)}
                      </TableCell>
                    ))}
                  <TableCell className="text-right tabular-nums">{r.checkCount}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell>{t("col_total")}</TableCell>
                <TableCell className="text-right tabular-nums">{CAD(data.totals.grossCents)}</TableCell>
                <TableCell className="text-right tabular-nums">{CAD(data.totals.netCents)}</TableCell>
                <TableCell className="text-right tabular-nums text-accent">
                  {CAD(data.totals.taxCents)}
                </TableCell>
                {combined &&
                  venues.map((v) => (
                    <TableCell key={v.id} className="hidden text-right tabular-nums md:table-cell">
                      {CAD(data.byVenue.find((x) => x.venueId === v.id)?.taxCents ?? 0)}
                    </TableCell>
                  ))}
                <TableCell className="text-right tabular-nums">{data.totals.checkCount}</TableCell>
              </TableRow>
            </TableFooter>
          </Table>
        ) : (
          <EmptyState title={t("tax_empty")} hint={t("tax_empty_hint")} />
        )}
      </Card>
    </div>
  );
}
