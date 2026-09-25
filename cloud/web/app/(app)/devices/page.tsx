"use client";

import { useEffect, useState } from "react";
import { ChevronDown, ChevronUp, KeyRound, Loader2, MonitorSmartphone } from "lucide-react";
import { post, del } from "@/lib/api";
import { toast, toastError } from "@/lib/toast";
import { useApi } from "@/lib/hooks";
import { useFmt, useT } from "@/lib/i18n/context";
import type { Fmt } from "@/lib/i18n/format";
import type { MsgKey } from "@/lib/i18n/messages";
import type { PairedDevice, PairingCodeResponse, StorePos, StorePosResponse, Venue } from "@/lib/types";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { useStores } from "@/lib/store";
import { cn } from "@/lib/utils";

type T = (key: MsgKey, vars?: Record<string, string | number>) => string;

/** "5 min ago" style, from whole minutes; beyond a week, the date itself. */
function agoLabel(t: T, fmt: Fmt, diffMin: number, at: string): string {
  if (diffMin < 60) return t("devices_seen_min", { n: diffMin });
  if (diffMin < 24 * 60) return t("devices_seen_hr", { n: Math.floor(diffMin / 60) });
  if (diffMin < 7 * 24 * 60) return t("devices_seen_day", { n: Math.floor(diffMin / (24 * 60)) });
  return fmt.dayYear(at);
}

// API timestamps are instants carrying the venue offset: Date.parse gives the
// exact instant for diffs, and the leading wall clock is venue-local for display.
function lastSeenLabel(t: T, fmt: Fmt, lastSeenAt: string | null): string {
  if (!lastSeenAt) return t("devices_seen_never");
  const diffMin = Math.floor((Date.now() - Date.parse(lastSeenAt)) / 60_000);
  const when = diffMin < 1 ? t("devices_seen_just_now") : agoLabel(t, fmt, diffMin, lastSeenAt);
  return t("devices_last_seen", { when });
}

/** A store's last heartbeat, from the server's own age (immune to browser clock skew). */
function storeSeenLabel(t: T, fmt: Fmt, s: StorePos): string {
  if (s.secondsSinceSeen === null || !s.lastSeenAt) return t("devices_pos_never");
  const sec = s.secondsSinceSeen;
  const when =
    sec < 5 ? t("devices_seen_just_now")
    : sec < 60 ? t("devices_pos_seen_sec", { n: sec })
    : agoLabel(t, fmt, Math.floor(sec / 60), s.lastSeenAt);
  return t("devices_last_seen", { when });
}

const shortId = (id: string) => id.slice(0, 8);

// ── page ────────────────────────────────────────────────────────────────

export default function DevicesPage() {
  const t = useT();
  // store-scoped like every portal page: ?store= → that store, none → all stores
  const { data, error, isLoading, mutate } = useApi<StorePosResponse>("/v1/devices", {
    refreshInterval: 10_000,
  });
  const stores = data?.stores ?? [];

  return (
    <div className="space-y-4">
      <PageHeader title={t("devices_title")} sub={t("devices_stores_sub")} />

      {isLoading && !data ? (
        <Card>
          <TableSkeleton rows={4} />
        </Card>
      ) : error && !data ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : stores.length === 0 ? (
        <Card>
          <EmptyState title={t("devices_pos_empty")} />
        </Card>
      ) : (
        <>
          <h2 className="pt-1 text-sm font-semibold text-neutral-600">{t("devices_stores_title")}</h2>
          {stores.map((s) => (
            <StoreCard key={s.venueId} store={s} onChanged={() => mutate()} />
          ))}
          <ExtraTerminalCard venueIds={stores.map((s) => s.venueId)} />
        </>
      )}
    </div>
  );
}

// ── one store's POS ─────────────────────────────────────────────────────

const STATUS_DOT: Record<StorePos["status"], string> = {
  online: "bg-emerald-500",
  stale: "bg-amber-500",
  offline: "bg-neutral-300",
};

function StatusBadge({ status }: { status: StorePos["status"] }) {
  const t = useT();
  if (status === "online") return <Badge variant="success">{t("devices_pos_online")}</Badge>;
  if (status === "stale") return <Badge variant="warning">{t("devices_pos_stale")}</Badge>;
  return <Badge variant="default">{t("devices_pos_offline")}</Badge>;
}

function Detail({ label, value, mono, title }: { label: string; value: string | null; mono?: boolean; title?: string }) {
  const t = useT();
  return (
    <div className="min-w-0">
      <dt className="text-[11px] font-medium uppercase tracking-wide text-neutral-400">{label}</dt>
      <dd
        className={cn("truncate text-sm", value ? "text-ink" : "text-neutral-400", mono && value && "font-mono text-[13px]")}
        title={title ?? value ?? undefined}
      >
        {value ?? t("devices_pos_none_reported")}
      </dd>
    </div>
  );
}

function StoreCard({ store: s, onChanged }: { store: StorePos; onChanged: () => void }) {
  const t = useT();
  const fmt = useFmt();
  const never = s.secondsSinceSeen === null;
  const version = [s.appVersion, s.contractVersion !== null ? t("devices_pos_contract", { n: s.contractVersion }) : null]
    .filter(Boolean)
    .join(" · ");

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn("h-2.5 w-2.5 shrink-0 rounded-full", STATUS_DOT[s.status])} aria-hidden />
          <CardTitle className="truncate">{s.venueName}</CardTitle>
          <StatusBadge status={s.status} />
        </div>
        <CardDescription>{storeSeenLabel(t, fmt, s)}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {never && <p className="text-xs text-neutral-500">{t("devices_pos_never_hint")}</p>}
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 md:grid-cols-3">
          {s.lanUrl || !s.publicUrl ? (
            <Detail label={t("devices_pos_lan")} value={s.lanUrl} mono />
          ) : (
            <Detail label={t("devices_pos_public")} value={s.publicUrl} mono />
          )}
          <Detail
            label={t("devices_pos_install")}
            value={s.installId ? shortId(s.installId) : null}
            title={s.installId ?? undefined}
            mono
          />
          <Detail label={t("devices_pos_version")} value={version || null} />
        </dl>

        <div className="space-y-1.5">
          <div className="flex items-center gap-1.5 text-xs font-medium text-neutral-500">
            <MonitorSmartphone className="h-3.5 w-3.5" />
            {t("devices_pos_devices")}
          </div>
          {s.devices.length > 0 ? (
            <DeviceRows venueId={s.venueId} devices={s.devices} onChanged={onChanged} />
          ) : (
            <p className="text-xs text-neutral-400">{t("devices_pos_devices_none")}</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

// ── a store's heartbeat-reported terminals (remote lock) ────────────────

function DeviceRows({
  venueId,
  devices,
  onChanged,
}: {
  venueId: string;
  devices: PairedDevice[];
  onChanged: () => void;
}) {
  const t = useT();
  const fmt = useFmt();
  const [confirm, setConfirm] = useState<PairedDevice | null>(null);
  const [busy, setBusy] = useState(false);

  const revoke = async () => {
    if (!confirm) return;
    setBusy(true);
    try {
      await post(`/v1/venues/${venueId}/devices/${confirm.deviceId}/revoke`);
      toast("success", t("devices_revoke_sent"));
    } catch (err) {
      toastError(err);
    } finally {
      setConfirm(null);
      setBusy(false);
      onChanged();
    }
  };

  // Remove a ghost row: a device the store no longer reports lingers (stuck in
  // "revoking…" if the owner tried to revoke it). A live store re-creates the
  // row on its next heartbeat, so this is safe to use freely.
  const remove = async (deviceId: string) => {
    try {
      await del(`/v1/venues/${venueId}/devices/${deviceId}`);
    } catch (err) {
      toastError(err);
    } finally {
      onChanged();
    }
  };

  return (
    <>
      <div className="divide-y divide-neutral-100 rounded-lg border border-neutral-100">
        {devices.map((d) => {
          const status = d.revoked ? "revoked" : d.revokeRequestedAt ? "revoking" : "active";
          return (
            <div key={d.deviceId} className="flex items-center gap-3 px-3 py-2.5">
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
    </>
  );
}

// ── optional: pair an extra terminal ────────────────────────────────────

function ExtraTerminalCard({ venueIds }: { venueIds: string[] }) {
  const t = useT();
  const { venues } = useStores();
  const [open, setOpen] = useState(false);
  const choices = venues.filter((v) => venueIds.includes(v.id));
  const [picked, setPicked] = useState<string | null>(null);
  const venue = choices.find((v) => v.id === picked) ?? choices[0] ?? null;

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle>{t("devices_extra_title")}</CardTitle>
              <Badge variant="outline">{t("devices_extra_optional")}</Badge>
            </div>
            <CardDescription>{t("devices_extra_sub")}</CardDescription>
          </div>
          <Button variant="secondary" size="sm" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
            {open ? <ChevronUp /> : <ChevronDown />}
            {t(open ? "devices_extra_hide" : "devices_extra_show")}
          </Button>
        </div>
      </CardHeader>
      {open && venue && (
        <CardContent className="space-y-4">
          {choices.length > 1 && (
            <div className="space-y-1.5 md:w-72">
              <Label>{t("devices_extra_store")}</Label>
              <Select value={venue.id} onValueChange={setPicked}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {choices.map((v) => (
                    <SelectItem key={v.id} value={v.id}>
                      {v.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}
          {/* keyed: switching store drops any code minted for the other one */}
          <PairForm key={venue.id} venue={venue} />
        </CardContent>
      )}
    </Card>
  );
}

const CODE_TTL_MS = 15 * 60_000;

interface ActiveCode {
  code: string;
  url: string | null;
  /** Browser-clock expiry; derived once from the server's expiresAt instant. */
  expiryEpochMs: number;
}

function PairForm({ venue }: { venue: Venue }) {
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
    <div className="space-y-4">
      <p className="text-xs text-neutral-500">{t("devices_pair_sub")}</p>
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
          <div className="rounded-xl bg-navy px-4 py-6 text-center">
            <div className="font-mono text-4xl font-bold tracking-[0.18em] text-white md:text-5xl">{code.code}</div>
            {code.url && <p className="mt-3 break-all font-mono text-xs text-neutral-400">{code.url}</p>}
          </div>
          <Countdown expiryEpochMs={code.expiryEpochMs} />
          <p className="text-center text-xs text-neutral-500">{t("devices_code_hint")}</p>
        </div>
      )}
    </div>
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
  return <p className="text-center text-sm font-medium text-ink">{t("devices_code_expires", { time: mmss })}</p>;
}
