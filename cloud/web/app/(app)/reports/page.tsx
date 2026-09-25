"use client";

import Link from "next/link";
import {
  ArrowLeftRight,
  ChevronRight,
  Clock3,
  Landmark,
  ListOrdered,
  Receipt,
  ScrollText,
  ShieldAlert,
  Table2,
  Undo2,
  Wallet,
} from "lucide-react";
import { useT } from "@/lib/i18n/context";
import { useStoreHref } from "@/lib/store";
import type { MsgKey } from "@/lib/i18n/messages";
import { PageHeader } from "@/components/page-header";
import { Card } from "@/components/ui/card";

const REPORTS: { href: string; titleKey: MsgKey; descKey: MsgKey; icon: typeof Landmark }[] = [
  { href: "/reports/vat", titleKey: "report_vat_title", descKey: "report_vat_desc", icon: Landmark },
  { href: "/reports/payments", titleKey: "report_payments_title", descKey: "report_payments_desc", icon: Wallet },
  { href: "/reports/items", titleKey: "report_items_title", descKey: "report_items_desc", icon: ListOrdered },
  { href: "/reports/tables", titleKey: "report_tables_title", descKey: "report_tables_desc", icon: Table2 },
  { href: "/reports/hourly", titleKey: "report_hourly_title", descKey: "report_hourly_desc", icon: Clock3 },
  { href: "/reports/exceptions", titleKey: "report_exceptions_title", descKey: "report_exceptions_desc", icon: ShieldAlert },
  { href: "/reports/shifts", titleKey: "report_shifts_title", descKey: "report_shifts_desc", icon: Receipt },
  { href: "/reports/refunds", titleKey: "report_refunds_title", descKey: "report_refunds_desc", icon: Undo2 },
  { href: "/reports/cash-movements", titleKey: "report_cash_title", descKey: "report_cash_desc", icon: ArrowLeftRight },
  { href: "/reports/journal", titleKey: "report_journal_title", descKey: "report_journal_desc", icon: ScrollText },
];

export default function ReportsPage() {
  const t = useT();
  const storeHref = useStoreHref();
  return (
    <div>
      <PageHeader title={t("reports_title")} sub={t("reports_sub")} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {REPORTS.map(({ href, titleKey, descKey, icon: Icon }) => (
          <Link key={href} href={storeHref(href)} className="group">
            <Card className="flex items-center gap-4 p-4 transition-colors group-hover:border-accent/50">
              <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-ink text-white">
                <Icon className="h-5 w-5" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-semibold">{t(titleKey)}</div>
                <div className="truncate text-xs text-neutral-500">{t(descKey)}</div>
              </div>
              <ChevronRight className="h-4 w-4 shrink-0 text-neutral-300 transition-colors group-hover:text-accent" />
            </Card>
          </Link>
        ))}
      </div>
    </div>
  );
}
