"use client";

import { useState } from "react";
import Link from "next/link";
import { ChevronRight, LogOut, Loader2, TabletSmartphone } from "lucide-react";
import { post } from "@/lib/api";
import { useMe } from "@/lib/hooks";
import { toastError } from "@/lib/toast";
import { useI18n, useT } from "@/lib/i18n/context";
import { SegmentedToggle } from "@/components/lang-toggle";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { PageHeader } from "@/components/page-header";

const APP_VERSION = "0.1.0";

export default function AccountPage() {
  const t = useT();
  const { locale, setLocale } = useI18n();
  const me = useMe();
  const [busy, setBusy] = useState(false);

  const logout = async () => {
    setBusy(true);
    try {
      await post("/v1/auth/logout");
      window.location.assign("/login");
    } catch (err) {
      toastError(err);
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader title={t("account_title")} />

      <Card>
        <CardHeader>
          <CardTitle>{t("account_signed_in")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {me.isLoading ? (
            <>
              <Skeleton className="h-5 w-40" />
              <Skeleton className="h-5 w-52" />
              <Skeleton className="h-5 w-32" />
            </>
          ) : me.data ? (
            <>
              <InfoRow label={t("account_name")} value={me.data.displayName} />
              <InfoRow label={t("account_email")} value={me.data.email} />
              <InfoRow label={t("account_venue")} value={me.data.tenantName} />
            </>
          ) : (
            <p className="text-sm text-neutral-500">{t("account_not_signed_in")}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("account_display")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <span className="text-sm text-neutral-600">{t("account_language")}</span>
            <SegmentedToggle
              value={locale}
              onChange={setLocale}
              ariaLabel={t("account_language")}
              options={[
                { value: "fr", label: "français" },
                { value: "en", label: "EN" },
              ]}
            />
          </div>
        </CardContent>
      </Card>

      {/* The mobile bottom nav is a fixed 5-slot grid, so Devices links from here. */}
      <Link href="/devices" className="block">
        <Card className="flex items-center gap-3 p-4 transition-colors hover:bg-neutral-50">
          <div className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-neutral-100 text-neutral-600">
            <TabletSmartphone className="h-[18px] w-[18px]" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-ink">{t("account_devices")}</p>
            <p className="text-xs text-neutral-500">{t("account_devices_hint")}</p>
          </div>
          <ChevronRight className="h-4 w-4 shrink-0 text-neutral-400" />
        </Card>
      </Link>

      <Button variant="secondary" className="w-full md:w-auto" onClick={logout} disabled={busy}>
        {busy ? <Loader2 className="animate-spin" /> : <LogOut />}
        {t("account_sign_out")}
      </Button>

      <p className="pt-6 text-center text-xs text-neutral-400">
        {t("account_footer", { version: APP_VERSION })}
      </p>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 text-sm">
      <span className="text-neutral-500">{label}</span>
      <span className="font-medium">{value}</span>
    </div>
  );
}
