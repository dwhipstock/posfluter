"use client";

import { Suspense } from "react";
import { useApi, useRange, reportKey } from "@/lib/hooks";
import { useI18n } from "@/lib/i18n/context";
import { useMoney } from "@/lib/money";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta, useStoreExport } from "@/lib/export/report";
import { col, Int, Money, T, type Col, type ExportDoc } from "@/lib/export/doc";
import type { DayRow, TaxCodeRow, TaxReport } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
import { FxNote, RetailBadge } from "@/components/money-scope";
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

const CANADIAN = new Set(["GST", "QST"]);

function TaxPage() {
  const { t, fmt, locale, name } = useI18n();
  const range = useRange();
  const meta = useExportMeta();
  const storeExport = useStoreExport();
  const m = useMoney();
  const { combined, venues, nameOf, colorOf } = useStores();
  const { data, error, isLoading, mutate } = useApi<TaxReport>(reportKey("/v1/reports/tax", range));

  const money = data?.money;
  const fmtC = (n: number) => m.fmtScope(money, n);
  const rateText = (r: string) => (locale === "fr" ? r.replace(".", ",") : r);
  const taxLabel = (r: { code: string; labelFr: string; labelEn: string; ratePercent: string }) =>
    r.ratePercent
      ? t("tax_col_rate", { label: name(r.labelFr, r.labelEn) || r.code, rate: rateText(r.ratePercent) })
      : name(r.labelFr, r.labelEn) || r.code;
  // GST / QST day columns only where Canadian taxes were charged: a US-only
  // scope shows its own tax in the "by tax" table instead of two zero columns
  const rates = data?.rates ?? [];
  const byTax: TaxCodeRow[] = data?.byTax ?? [];
  const showQc = !(
    (rates.length > 0 || byTax.length > 0) &&
    rates.every((r) => !CANADIAN.has(r.code)) &&
    byTax.every((r) => !CANADIAN.has(r.code))
  );

  const buildDoc = (): ExportDoc | null => {
    if (!data) return null;
    const qcCols = <R extends { gstCents: number; qstCents: number }>(): Col<R>[] =>
      showQc ? [col.money(t("col_gst"), (r) => r.gstCents), col.money(t("col_qst"), (r) => r.qstCents)] : [];
    const qcTotal = showQc ? [Money(data.totals.gstCents), Money(data.totals.qstCents)] : [];
    return {
      ...meta("tax"),
      reportTitle: t("tax_title"),
      notes: [t("tax_note"), t("tax_no_breakdown")],
      kpis: [
        { label: t("col_gross"), value: fmtC(data.totals.grossCents) },
        { label: t("col_net"), value: fmtC(data.totals.netCents) },
        ...(showQc
          ? [
              { label: t("col_gst"), value: fmtC(data.totals.gstCents) },
              { label: t("col_qst"), value: fmtC(data.totals.qstCents) },
            ]
          : []),
        { label: t("col_tax"), value: fmtC(data.totals.taxCents) },
        { label: t("col_checks"), value: String(data.totals.checkCount) },
        ...byTax.map((r) => ({ label: taxLabel(r), value: m.fmtIn(r.currency, r.amountCents) })),
      ],
      sections: [
        ...storeExport.byStore<VenueSummaryRow>(
          [
            col.money(t("col_gross"), (r) => r.grossCents),
            col.money(t("col_net"), (r) => r.netCents),
            ...qcCols<VenueSummaryRow>(),
            col.money(t("col_tax"), (r) => r.taxCents),
            col.int(t("col_checks"), (r) => r.checkCount),
          ],
          data.byVenue,
          [
            T(t("col_total")),
            Money(data.totals.grossCents),
            Money(data.totals.netCents),
            ...qcTotal,
            Money(data.totals.taxCents),
            Int(data.totals.checkCount),
          ]
        ),
        {
          title: t("tax_by_code"),
          columns: [
            col.text<TaxCodeRow>(t("tax_col_tax"), (r) => taxLabel(r)),
            col.money(t("col_amount"), (r) => r.amountCents),
          ],
          rows: byTax,
        },
        {
          title: t("tax_title"),
          columns: [
            col.text<TaxDay>(t("col_date"), (r) => fmt.dayYear(r.date)),
            ...storeExport.withStore<TaxDay>([
              col.money(t("col_gross"), (r) => r.grossCents),
              col.money(t("col_net"), (r) => r.netCents),
              ...qcCols<TaxDay>(),
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
            ...qcTotal,
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
      <FxNote money={money} />
      {data && <StoreBreakdown rows={data.byVenue} />}

      {byTax.length > 0 && (
        <Card data-testid="tax-by-code">
          <CardHeader>
            <CardTitle>{t("tax_by_code")}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="-mx-2">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("tax_col_tax")}</TableHead>
                    {combined && m.multi && <TableHead>{t("col_currency")}</TableHead>}
                    {combined && <TableHead className="hidden md:table-cell">{t("col_store")}</TableHead>}
                    <TableHead className="text-right">{t("col_amount")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {byTax.map((r) => {
                    const stores = data?.byVenue.filter((v) => v.taxes?.some((x) => x.code === r.code && x.currency === r.currency)) ?? [];
                    return (
                      <TableRow key={`${r.code}-${r.currency}`}>
                        <TableCell className="font-medium">{taxLabel(r)}</TableCell>
                        {combined && m.multi && <TableCell>{r.currency}</TableCell>}
                        {combined && (
                          <TableCell className="hidden text-neutral-600 md:table-cell">
                            <span className="flex flex-wrap items-center gap-2">
                              {stores.map((v) => (
                                <span key={v.venueId} className="inline-flex items-center gap-1">
                                  <span className="h-2 w-2 rounded-full" style={{ backgroundColor: colorOf(v.venueId) }} />
                                  {nameOf(v.venueId)}
                                  <RetailBadge venueId={v.venueId} />
                                </span>
                              ))}
                            </span>
                          </TableCell>
                        )}
                        <TableCell className="text-right font-semibold tabular-nums">
                          {m.fmtIn(r.currency, r.amountCents)}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          </CardContent>
        </Card>
      )}

      <Card>
        <div className="flex flex-wrap items-center gap-2 border-b border-neutral-100 px-5 py-3">
          {rates.map((r) => (
            <Badge key={`${r.code}-${r.ratePercent}-${r.currency ?? ""}`} variant="pink">
              {t("tax_rate_badge", {
                label: name(r.labelFr, r.labelEn) || r.code,
                rate: rateText(r.ratePercent),
              })}
            </Badge>
          ))}
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
                {showQc && <TableHead className="text-right">{t("col_gst")}</TableHead>}
                {showQc && <TableHead className="text-right">{t("col_qst")}</TableHead>}
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
                  <TableCell className="text-right tabular-nums">{fmtC(r.grossCents)}</TableCell>
                  <TableCell className="text-right tabular-nums">{fmtC(r.netCents)}</TableCell>
                  {showQc && <TableCell className="text-right tabular-nums">{fmtC(r.gstCents)}</TableCell>}
                  {showQc && <TableCell className="text-right tabular-nums">{fmtC(r.qstCents)}</TableCell>}
                  <TableCell className="text-right tabular-nums">{fmtC(r.taxCents)}</TableCell>
                  {combined &&
                    venues.map((v) => (
                      <TableCell key={v.id} className="hidden text-right tabular-nums text-neutral-600 md:table-cell">
                        {m.fmtVenue(v.id, r.byVenue.find((x) => x.venueId === v.id)?.taxCents ?? 0)}
                      </TableCell>
                    ))}
                  <TableCell className="text-right tabular-nums">{r.checkCount}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell>{t("col_total")}</TableCell>
                <TableCell className="text-right tabular-nums">{fmtC(data.totals.grossCents)}</TableCell>
                <TableCell className="text-right tabular-nums">{fmtC(data.totals.netCents)}</TableCell>
                {showQc && <TableCell className="text-right tabular-nums">{fmtC(data.totals.gstCents)}</TableCell>}
                {showQc && <TableCell className="text-right tabular-nums">{fmtC(data.totals.qstCents)}</TableCell>}
                <TableCell className="text-right tabular-nums text-accent">{fmtC(data.totals.taxCents)}</TableCell>
                {combined &&
                  venues.map((v) => (
                    <TableCell key={v.id} className="hidden text-right tabular-nums md:table-cell">
                      {m.fmtVenue(v.id, data.byVenue.find((x) => x.venueId === v.id)?.taxCents ?? 0)}
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
