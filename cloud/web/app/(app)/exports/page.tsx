"use client";

import { Suspense, useState } from "react";
import {
  Archive,
  ArrowLeftRight,
  BookOpen,
  Landmark,
  ListOrdered,
  Lock,
  Receipt,
  ScrollText,
  Undo2,
  Users,
  Wallet,
} from "lucide-react";
import { useMe, useRange } from "@/lib/hooks";
import { useI18n } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { shortStoreName, useStores } from "@/lib/store";
import { toast } from "@/lib/toast";
import {
  ALL_DATA_PATH,
  EXPORT_DATASETS,
  ExportError,
  ZIP_PER_HOUR,
  canExport,
  downloadExport,
  exportPath,
  type ExportDatasetId,
  type ExportFormat,
} from "@/lib/export/server";
import { PageHeader } from "@/components/page-header";
import { DateRangePicker } from "@/components/date-range-picker";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { PageFallback } from "@/components/states";

export default function Page() {
  return (
    <Suspense fallback={<PageFallback />}>
      <ExportsPage />
    </Suspense>
  );
}

const LABELS: Record<ExportDatasetId, { title: MsgKey; desc: MsgKey; icon: typeof Landmark }> = {
  sales: { title: "exports_ds_sales", desc: "exports_ds_sales_desc", icon: ScrollText },
  "sale-lines": { title: "exports_ds_lines", desc: "exports_ds_lines_desc", icon: ListOrdered },
  refunds: { title: "exports_ds_refunds", desc: "exports_ds_refunds_desc", icon: Undo2 },
  tenders: { title: "exports_ds_tenders", desc: "exports_ds_tenders_desc", icon: Wallet },
  shifts: { title: "exports_ds_shifts", desc: "exports_ds_shifts_desc", icon: Receipt },
  "cash-movements": { title: "exports_ds_cash", desc: "exports_ds_cash_desc", icon: ArrowLeftRight },
  "tax-summary": { title: "exports_ds_tax", desc: "exports_ds_tax_desc", icon: Landmark },
  "menu-items": { title: "exports_ds_menu", desc: "exports_ds_menu_desc", icon: BookOpen },
  staff: { title: "exports_ds_staff", desc: "exports_ds_staff_desc", icon: Users },
};

function ExportsPage() {
  const { t, fmt } = useI18n();
  const range = useRange();
  const me = useMe();
  const { storeId, store, venues } = useStores();
  // one download at a time: "<dataset>.<format>" or "all"
  const [busy, setBusy] = useState<string | null>(null);

  const role = me.data?.role;
  const isOwner = !!me.data && (role === undefined || role === "owner");

  const scope = storeId ? shortStoreName(store?.name ?? storeId) : venues.length > 1 ? t("store_all") : (venues[0]?.name ?? "");

  const run = async (key: string, path: string, fallback: string) => {
    if (busy) return;
    setBusy(key);
    try {
      const file = await downloadExport(path, fallback);
      toast("success", t("exports_done", { file }));
    } catch (e) {
      const err = e instanceof ExportError ? e : null;
      if (err?.status === 401) return; // on the way to sign-in
      const message =
        err?.status === 403
          ? t("exports_forbidden")
          : err?.code === "export_too_large"
            ? t("exports_too_large")
            : err?.status === 429
              ? t("exports_rate_limited", { minutes: Math.max(1, Math.ceil((err.retryAfterSeconds ?? 60) / 60)) })
              : t("exports_failed");
      toast("error", message);
    } finally {
      setBusy(null);
    }
  };

  if (me.data && !canExport(role)) {
    return (
      <div>
        <PageHeader title={t("exports_title")} />
        <Card className="flex items-center gap-3 p-5 text-sm text-neutral-700">
          <Lock className="h-4 w-4 shrink-0 text-neutral-600" />
          {t("exports_forbidden")}
        </Card>
      </div>
    );
  }

  const button = (id: ExportDatasetId, format: ExportFormat) => {
    const key = `${id}.${format}`;
    return (
      <Button
        variant="secondary"
        size="sm"
        disabled={busy !== null}
        aria-label={`${t(LABELS[id].title)} · ${format === "csv" ? "CSV" : "Excel"}`}
        onClick={() => run(key, exportPath(id, format, range, storeId), key)}
      >
        {busy === key ? t("exports_preparing") : t(format === "csv" ? "export_csv" : "export_excel")}
      </Button>
    );
  };

  return (
    <div className="space-y-4">
      <PageHeader title={t("exports_title")} sub={t("exports_sub")} />
      <DateRangePicker />
      <p className="text-sm text-neutral-700">
        {t("exports_scope_note", { scope: `${scope} · ${fmt.rangeLabel(range)}` })}
      </p>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        {EXPORT_DATASETS.map(({ id, dated }) => {
          const { title, desc, icon: Icon } = LABELS[id];
          return (
            <Card key={id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center" data-testid={`export-${id}`}>
              <div className="flex min-w-0 flex-1 items-center gap-4">
                <div className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-navy text-white">
                  <Icon className="h-5 w-5" />
                </div>
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-ink">{t(title)}</div>
                  <div className="text-xs text-neutral-600">{t(desc)}</div>
                  {!dated && <div className="mt-0.5 text-xs text-neutral-600">{t("exports_current_state")}</div>}
                </div>
              </div>
              <div className="flex shrink-0 gap-2 sm:justify-end">
                {button(id, "csv")}
                {button(id, "xlsx")}
              </div>
            </Card>
          );
        })}
      </div>

      {isOwner && (
        <Card className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center" data-testid="export-all">
          <div className="flex min-w-0 flex-1 items-center gap-4">
            <div className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-copper text-white">
              <Archive className="h-5 w-5" />
            </div>
            <div className="min-w-0">
              <div className="text-sm font-semibold text-ink">{t("exports_all_title")}</div>
              <div className="text-xs text-neutral-600">{t("exports_all_desc")}</div>
              <div className="mt-0.5 text-xs text-neutral-600">{t("exports_all_limit", { n: ZIP_PER_HOUR })}</div>
            </div>
          </div>
          <Button
            variant="dark"
            className="shrink-0"
            disabled={busy !== null}
            onClick={() => run("all", ALL_DATA_PATH, "all-data.zip")}
          >
            {busy === "all" ? t("exports_preparing") : t("exports_all_button")}
          </Button>
        </Card>
      )}
    </div>
  );
}
