"use client";

import { Fragment, Suspense, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ChevronDown, Search } from "lucide-react";
import { useApi, useRange } from "@/lib/hooks";
import { get } from "@/lib/api";
import { CAD } from "@/lib/format";
import { useI18n, useT, useFmt } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import type { JournalReport, JournalRow } from "@/lib/types";
import { ExportMenu } from "@/components/export-menu";
import { useExportMeta } from "@/lib/export/report";
import { col, type ExportDoc } from "@/lib/export/doc";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { StoreBreakdown, StoreTag } from "@/components/store-breakdown";
import { DateRangePicker } from "@/components/date-range-picker";
import { EmptyState, ErrorState, PageFallback, TableSkeleton } from "@/components/states";

const PAGE_SIZE = 50;

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <JournalPage />
    </Suspense>
  );
}

function JournalPage() {
  const t = useT();
  const fmt = useFmt();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const range = useRange();
  const meta = useExportMeta();

  const q = searchParams.get("q") ?? "";
  const page = Math.max(0, Number(searchParams.get("page") ?? "0") || 0);
  const [input, setInput] = useState(q);
  const [expanded, setExpanded] = useState<string | null>(null); // venueId/checkId — ids repeat across stores

  useEffect(() => {
    const t = setTimeout(() => {
      if (input === q) return;
      const params = new URLSearchParams(searchParams.toString());
      if (input) params.set("q", input);
      else params.delete("q");
      params.delete("page");
      router.replace(`${pathname}?${params.toString()}`, { scroll: false });
    }, 350);
    return () => clearTimeout(t);
  }, [input, q, pathname, router, searchParams]);

  const key =
    `/v1/reports/journal?from=${range.from}&to=${range.to}` +
    `&limit=${PAGE_SIZE}&offset=${page * PAGE_SIZE}` +
    (q ? `&q=${encodeURIComponent(q)}` : "");
  const { data, error, isLoading, mutate } = useApi<JournalReport>(key);

  const setPage = (p: number) => {
    const params = new URLSearchParams(searchParams.toString());
    if (p > 0) params.set("page", String(p));
    else params.delete("page");
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  };

  const total = data?.total ?? 0;
  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  // Export pulls EVERY check for the range (and active search), not just the
  // page on screen — the journal is the "every check" record.
  const buildDoc = async (): Promise<ExportDoc | null> => {
    if (!data) return null;
    const allKey =
      `/v1/reports/journal?from=${range.from}&to=${range.to}` +
      `&limit=${Math.max(data.total, 1)}&offset=0` +
      (q ? `&q=${encodeURIComponent(q)}` : "");
    const all = await get<JournalReport>(allKey);
    return {
      ...meta("journal"),
      reportTitle: t("journal_title"),
      sections: [
        {
          columns: [
            col.text<JournalRow>(t("col_check"), (r) => `#${r.checkId}`),
            col.text<JournalRow>(t("col_status"), (r) =>
              r.status === "VOID" ? t("badge_void") : t("badge_closed")
            ),
            col.text<JournalRow>(t("col_closed"), (r) => fmt.dateTime(r.closedAt)),
            col.text<JournalRow>(t("col_table"), (r) => `${r.tableLabel} · ${r.zoneNameEn}`),
            col.text<JournalRow>(t("col_tender"), (r) =>
              r.tenderTypes.map((tt) => t(`tender_${tt}` as MsgKey)).join(" · ") || "—"
            ),
            col.money<JournalRow>(t("col_total_short"), (r) => r.grandTotalCents),
          ],
          rows: all.rows,
        },
      ],
    };
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("journal_title")}
        sub={fmt.rangeLabel(range)}
        back={{ href: "/reports", label: t("reports_title") }}
        action={<ExportMenu build={buildDoc} disabled={!data || data.rows.length === 0} />}
      />
      <DateRangePicker />
      <StoreBreakdown />

      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400" />
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={t("journal_search")}
          className="pl-9"
        />
      </div>

      <Card>
        {isLoading ? (
          <TableSkeleton rows={8} />
        ) : error ? (
          <ErrorState message={error.message} onRetry={() => mutate()} />
        ) : data && data.rows.length > 0 ? (
          <>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-8" />
                  <TableHead>{t("col_check")}</TableHead>
                  <TableHead>{t("col_closed")}</TableHead>
                  <TableHead>{t("col_table")}</TableHead>
                  <TableHead className="hidden sm:table-cell">{t("col_tender")}</TableHead>
                  <TableHead className="text-right">{t("col_total_short")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.rows.map((r) => (
                  <JournalRowView
                    key={`${r.venueId}/${r.checkId}-${r.status}`}
                    row={r}
                    expanded={expanded === `${r.venueId}/${r.checkId}`}
                    onToggle={() => setExpanded(expanded === `${r.venueId}/${r.checkId}` ? null : `${r.venueId}/${r.checkId}`)}
                  />
                ))}
              </TableBody>
            </Table>
            <div className="flex items-center justify-between border-t border-neutral-100 px-4 py-3">
              <span className="text-xs text-neutral-500">
                {t("journal_pagination", {
                  from: page * PAGE_SIZE + 1,
                  to: Math.min((page + 1) * PAGE_SIZE, total),
                  total,
                })}
              </span>
              <div className="flex gap-2">
                <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  {t("prev")}
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page >= lastPage}
                  onClick={() => setPage(page + 1)}
                >
                  {t("next")}
                </Button>
              </div>
            </div>
          </>
        ) : (
          <EmptyState
            title={q ? t("journal_empty_search") : t("journal_empty")}
            hint={q ? t("journal_empty_search_hint") : undefined}
          />
        )}
      </Card>
    </div>
  );
}

function JournalRowView({
  row,
  expanded,
  onToggle,
}: {
  row: JournalRow;
  expanded: boolean;
  onToggle: () => void;
}) {
  const t = useT();
  const fmt = useFmt();
  const { name, nameAlt } = useI18n();
  const voided = row.status === "VOID";
  return (
    <Fragment>
      <TableRow
        onClick={onToggle}
        className={cn("cursor-pointer", voided && "bg-red-50/60 hover:bg-red-50")}
      >
        <TableCell className="pr-0">
          <ChevronDown
            className={cn("h-4 w-4 text-neutral-400 transition-transform", expanded && "rotate-180")}
          />
        </TableCell>
        <TableCell>
          <span className="flex items-center gap-2 font-medium">
            #{row.checkId}
            {voided && <Badge variant="destructive">{t("badge_void")}</Badge>}
            <StoreTag venueId={row.venueId} />
          </span>
        </TableCell>
        <TableCell className="whitespace-nowrap text-xs text-neutral-500">
          {fmt.dateTime(row.closedAt)}
        </TableCell>
        <TableCell>
          <span className="text-sm">{row.tableLabel}</span>
          <span className="ml-1.5 text-xs text-neutral-400">{row.zoneNameEn}</span>
        </TableCell>
        <TableCell className="hidden sm:table-cell">
          <span className="flex flex-wrap gap-1">
            {row.tenderTypes.map((tt) => (
              <Badge key={tt} variant="outline">
                {t(`tender_${tt}` as MsgKey)}
              </Badge>
            ))}
          </span>
        </TableCell>
        <TableCell
          className={cn("text-right font-semibold tabular-nums", voided && "text-red-600")}
        >
          {CAD(row.grandTotalCents)}
        </TableCell>
      </TableRow>
      {expanded && (
        <TableRow className={cn("hover:bg-transparent", voided && "bg-red-50/40")}>
          <TableCell colSpan={6} className="bg-neutral-50/60 px-4 py-3">
            <div className="space-y-1.5">
              {row.lines.map((l, i) => (
                <div key={i} className="flex items-baseline gap-2 text-sm">
                  <span className="tabular-nums text-neutral-500">{l.qty}×</span>
                  <span className="min-w-0 flex-1 truncate">
                    {name(l.nameFr, l.nameEn) || t("journal_open_item")}
                    {nameAlt(l.nameFr, l.nameEn) && (
                      <span className="ml-1.5 text-xs text-neutral-400">{nameAlt(l.nameFr, l.nameEn)}</span>
                    )}
                  </span>
                  <span className="text-xs text-neutral-400">@ {CAD(l.unitPriceCents)}</span>
                  <span className="w-20 text-right tabular-nums">{CAD(l.lineTotalCents)}</span>
                </div>
              ))}
              <div className="flex items-center justify-between border-t border-neutral-200/70 pt-2 text-xs text-neutral-500">
                <span>
                  {t("journal_vat_included")} <span className="tabular-nums">{CAD(row.taxIncludedCents)}</span>
                </span>
                <span className="sm:hidden">
                  {row.tenderTypes.map((tt) => t(`tender_${tt}` as MsgKey)).join(" · ") || "—"}
                </span>
              </div>
            </div>
          </TableCell>
        </TableRow>
      )}
    </Fragment>
  );
}
