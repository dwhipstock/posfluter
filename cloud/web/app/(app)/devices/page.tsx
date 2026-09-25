"use client";

import { useEffect, useState } from "react";
import { KeyRound, Loader2 } from "lucide-react";
import { post, del } from "@/lib/api";
import { toast, toastError } from "@/lib/toast";
import { useApi } from "@/lib/hooks";
import { useFmt, useT } from "@/lib/i18n/context";
import type { Fmt } from "@/lib/i18n/format";
import type { MsgKey } from "@/lib/i18n/messages";
import type {
  DevicesResponse,
  PairedDevice,
  PairingCodeResponse,
  Venue,
  VenuesResponse,
} from "@/lib/types";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { useStoreId } from "@/lib/store";

type T = (key: MsgKey, vars?: Record<string, string | number>) => string;

// API timestamps are instants carrying the venue offset: Date.parse gives the
// exact instant for diffs, and the leading wall clock is venue-local for display.
function lastSeenLabel(t: T, fmt: Fmt, lastSeenAt: string | null): string {
  if (!lastSeenAt) return t("devices_seen_never");
  const diffMin = Math.floor((Date.now() - Date.parse(lastSeenAt)) / 60_000);
  let when: string;
  if (diffMin < 1) when = t("devices_seen_just_now");
  else if (diffMin < 60) when = t("devices_seen_min", { n: diffMin });
  else if (diffMin < 24 * 60) when = t("devices_seen_hr", { n: Math.floor(diffMin / 60) });
  else if (diffMin < 7 * 24 * 60) when = t("devices_seen_day", { n: Math.floor(diffMin / (24 * 60)) });
  else when = fmt.dayYear(lastSeenAt);
  return t("devices_last_seen", { when });
}

// ── page ────────────────────────────────────────────────────────────────

export default function DevicesPage() {
  const t = useT();
  const { data, error, isLoading, mutate } = useApi<VenuesResponse>("/v1/venues");
  // the header's store picker: one store, or every store's terminals in turn
  const storeId = useStoreId();

  const all = data?.venues ?? [];
  const shown = storeId ? all.filter((v) => v.id === storeId) : all;
  const venue = shown[0] ?? null;

  return (
    <div className="space-y-4">
      <PageHeader title={t("devices_title")} sub={t("devices_sub")} />

      {isLoading ? (
        <Card>
          <TableSkeleton rows={4} />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : !venue ? (
        <Card>
          <EmptyState title={t("devices_no_venues")} />
        </Card>
      ) : (
        shown.map((v) => (
          <section key={v.id} className="space-y-4">
            {shown.length > 1 && <h2 className="pt-2 text-sm font-semibold text-neutral-600">{v.name}</h2>}
            <PairCard key={`pair-${v.id}`} venue={v} />
            <DeviceList key={`devices-${v.id}`} venue={v} />
          </section>
        ))
      )}
    </div>
  );
}

// ── pair a terminal ─────────────────────────────────────────────────────

const CODE_TTL_MS = 15 * 60_000;

interface ActiveCode {
  code: string;
  url: string | null;
  /** Browser-clock expiry; derived once from the server's expiresAt instant. */
  expiryEpochMs: number;
}

function PairCard({ venue }: { venue: Venue }) {
  const t = useT();
  const [label, setLabel] = useState("");
  const [busy, setBusy] = useState(false);
  const [code, setCode] = useState<ActiveCode | null>(null);

  const generate = async () => {
    setBusy(true);
    try {
      const res = await post<PairingCodeResponse>(
        `/v1/venues/${venue.id}/pairing-codes`,
        label.trim() ? { label: label.trim() } : {}
      );
      // Trust the server's expiry when it lands inside a sane window; if the
      // clocks disagree wildly, fall back to the fixed 15-minute TTL.
      const raw = Date.parse(res.expiresAt) - Date.now();
      const remaining = raw > 0 && raw <= CODE_TTL_MS + 60_000 ? raw : CODE_TTL_MS;
      setCode({ code: res.code, url: res.url, expiryEpochMs: Date.now() + remaining });
    } catch (err) {
      toastError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("devices_pair_title")}</CardTitle>
        <CardDescription>{t("devices_pair_sub")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-col gap-3 md:flex-row md:items-end">
          <div className="space-y-1.5 md:w-72">
            <Label htmlFor="device-label">{t("devices_label")}</Label>
            <Input
              id="device-label"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder={t("devices_label_ph")}
              maxLength={60}
            />
          </div>
          <Button onClick={generate} disabled={busy}>
            {busy ? <Loader2 className="animate-spin" /> : <KeyRound />}
            {t(code ? "devices_pair_again" : "devices_pair_button")}
          </Button>
        </div>

        {code && (
          <div className="space-y-2">
            <div className="rounded-xl bg-ink px-4 py-6 text-center">
              <div className="font-mono text-4xl font-bold tracking-[0.18em] text-white md:text-5xl">
                {code.code}
              </div>
              {code.url && <p className="mt-3 break-all font-mono text-xs text-neutral-400">{code.url}</p>}
            </div>
            <Countdown expiryEpochMs={code.expiryEpochMs} />
            <p className="text-center text-xs text-neutral-500">{t("devices_code_hint")}</p>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Countdown({ expiryEpochMs }: { expiryEpochMs: number }) {
  const t = useT();
  const [left, setLeft] = useState(() => Math.max(0, expiryEpochMs - Date.now()));

  useEffect(() => {
    setLeft(Math.max(0, expiryEpochMs - Date.now()));
    const id = setInterval(() => setLeft(Math.max(0, expiryEpochMs - Date.now())), 1000);
    return () => clearInterval(id);
  }, [expiryEpochMs]);

  if (left <= 0) {
    return <p className="text-center text-sm font-medium text-red-600">{t("devices_code_expired")}</p>;
  }
  const totalSec = Math.ceil(left / 1000);
  const mmss = `${Math.floor(totalSec / 60)}:${String(totalSec % 60).padStart(2, "0")}`;
  return (
    <p className="text-center text-sm font-medium text-ink">
      {t("devices_code_expires", { time: mmss })}
    </p>
  );
}

// ── paired devices ──────────────────────────────────────────────────────

function DeviceList({ venue }: { venue: Venue }) {
  const t = useT();
  const fmt = useFmt();
  // Poll so a revoke's store-side confirmation (usually <30s) shows up live.
  const { data, error, isLoading, mutate } = useApi<DevicesResponse>(
    `/v1/venues/${venue.id}/devices`,
    { refreshInterval: 10_000 }
  );
  const [confirm, setConfirm] = useState<PairedDevice | null>(null);
  const [busy, setBusy] = useState(false);

  const revoke = async () => {
    if (!confirm) return;
    setBusy(true);
    try {
      await post(`/v1/venues/${venue.id}/devices/${confirm.deviceId}/revoke`);
      toast("success", t("devices_revoke_sent"));
      setConfirm(null);
      mutate();
    } catch (err) {
      setConfirm(null);
      toastError(err);
      mutate();
    } finally {
      setBusy(false);
    }
  };

  // Remove a ghost row: a device the store no longer reports lingers (stuck in
  // "revoking…" if the owner tried to revoke it). A live store re-creates the
  // row on its next heartbeat, so this is safe to use freely.
  const remove = async (deviceId: string) => {
    try {
      await del(`/v1/venues/${venue.id}/devices/${deviceId}`);
      mutate();
    } catch (err) {
      toastError(err);
      mutate();
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("devices_list_title")}</CardTitle>
      </CardHeader>

      {isLoading && !data ? (
        <TableSkeleton rows={3} />
      ) : error && !data ? (
        <ErrorState message={error.message} onRetry={() => mutate()} />
      ) : data && data.devices.length > 0 ? (
        <div className="divide-y divide-neutral-100 border-t border-neutral-100">
          {data.devices.map((d) => {
            const status = d.revoked ? "revoked" : d.revokeRequestedAt ? "revoking" : "active";
            return (
              <div key={d.deviceId} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="truncate text-sm font-medium">{d.name || d.deviceId}</span>
                    {status === "active" && <Badge variant="success">{t("devices_status_active")}</Badge>}
                    {status === "revoking" && <Badge variant="default">{t("devices_status_revoking")}</Badge>}
                    {status === "revoked" && <Badge variant="destructive">{t("devices_status_revoked")}</Badge>}
                  </div>
                  <span className="text-xs text-neutral-500">
                    {lastSeenLabel(t, fmt, d.lastSeenAt)}
                    {d.pairedAt && <> · {t("devices_paired_on", { date: fmt.dayYear(d.pairedAt) })}</>}
                  </span>
                </div>
                {status === "active" ? (
                  <Button variant="destructive-outline" size="sm" onClick={() => setConfirm(d)}>
                    {t("devices_revoke")}
                  </Button>
                ) : (
                  // revoked or stuck-revoking → let the owner clear the row
                  <Button variant="secondary" size="sm" onClick={() => remove(d.deviceId)}>
                    {t("devices_remove")}
                  </Button>
                )}
              </div>
            );
          })}
        </div>
      ) : (
        <EmptyState title={t("devices_none")} hint={t("devices_none_hint")} />
      )}

      <Dialog open={confirm !== null} onOpenChange={(o) => !o && setConfirm(null)}>
        <DialogContent>
          <DialogTitle>
            {t("devices_revoke_q", { name: confirm ? confirm.name || confirm.deviceId : "" })}
          </DialogTitle>
          <DialogDescription>{t("devices_revoke_confirm")}</DialogDescription>
          <DialogFooter>
            <Button variant="secondary" size="sm" onClick={() => setConfirm(null)} disabled={busy}>
              {t("keep_it")}
            </Button>
            <Button variant="destructive" size="sm" onClick={revoke} disabled={busy}>
              {busy && <Loader2 className="animate-spin" />}
              {t("devices_revoke")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}
