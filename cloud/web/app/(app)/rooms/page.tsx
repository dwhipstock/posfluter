"use client";

import { useState } from "react";
import { Camera, Check, Lock, RefreshCw, Sparkles } from "lucide-react";
import { useApi } from "@/lib/hooks";
import { useI18n, useT } from "@/lib/i18n/context";
import { roomName } from "@/lib/rooms";
import { shortStoreName, useStores } from "@/lib/store";
import type { RoomDto, RoomsResponse } from "@/lib/types";
import { isRetail } from "@/components/money-scope";
import { useAiStatus } from "@/components/menu-ai";
import { AskRoomSheet, PhotoRoomSheet } from "@/components/room-ai";
import { RoomDrawing } from "@/components/room-drawing";
import { PageHeader } from "@/components/page-header";
import { EmptyState, ErrorState } from "@/components/states";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * Each restaurant's rooms (floor plans) as the point of sale has them, drawn
 * read-only. Owners and managers can make a room from a photo or change one by
 * typing or speaking to the AI; the store gets it on its next sync. No
 * drag-and-drop editor, on purpose. One store at a time.
 */
export default function RoomsPage() {
  const t = useT();
  const { storeId, venues } = useStores();
  const floors = venues.filter((v) => !isRetail(v));
  const [picked, setPicked] = useState<string | null>(null);
  const venue = storeId ?? picked ?? (floors.length === 1 ? floors[0].id : null);
  const { data, error, isLoading, mutate } = useApi<RoomsResponse>(venue ? `/v1/rooms?venue=${encodeURIComponent(venue)}` : null);
  const status = useAiStatus();
  const [photoOpen, setPhotoOpen] = useState(false);
  const [asking, setAsking] = useState<RoomDto | null>(null);

  const canAi = !!data && data.canEdit && data.editable && !!status?.enabled && status.canUse;
  const rooms = [...(data?.rooms ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
  const refresh = () => mutate();

  return (
    <div className="space-y-4">
      <PageHeader title={t("rooms_title")} sub={t("rooms_sub")} />

      {!storeId && floors.length > 1 && (
        <Card className="space-y-2 p-4">
          <p className="text-sm font-medium text-ink">{t("rooms_pick_store")}</p>
          <div className="flex flex-wrap gap-2">
            {floors.map((v) => (
              <Button key={v.id} size="sm" variant={venue === v.id ? "dark" : "secondary"} onClick={() => setPicked(v.id)}>
                {venue === v.id && <Check />} {shortStoreName(v.name)}
              </Button>
            ))}
          </div>
        </Card>
      )}

      {data && !data.editable && data.canEdit && (
        <p className="rounded-lg bg-amber-50 px-3 py-2.5 text-sm text-amber-900" role="status">
          {t("rooms_waiting_store")}
        </p>
      )}

      {canAi && venue && (
        // a strip under the title, not a header button: header buttons squeezed the title on phones
        <div className="flex flex-col gap-3 rounded-2xl border border-neutral-200 bg-surface px-4 py-3 sm:flex-row sm:items-center">
          <span className="flex min-w-0 flex-1 items-start gap-3">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-copper-soft text-copper-text">
              <Camera className="h-4 w-4" />
            </span>
            <span className="min-w-0 text-sm text-neutral-700">{t("rooms_new_photo_hint")}</span>
          </span>
          <Button className="h-auto min-h-10 w-full shrink-0 whitespace-normal py-2 sm:w-auto" onClick={() => setPhotoOpen(true)}>
            <Camera /> {t("rooms_new_photo")}
          </Button>
        </div>
      )}

      {canAi && <p className="text-xs text-neutral-600">{t("rooms_sync_hint")}</p>}

      {!venue ? null : isLoading && !data ? (
        <div className="grid gap-4 md:grid-cols-2">
          {Array.from({ length: 2 }).map((_, i) => (
            <Skeleton key={i} className="aspect-square w-full rounded-2xl" />
          ))}
        </div>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={refresh} />
        </Card>
      ) : rooms.length === 0 ? (
        <Card>
          <EmptyState title={t("rooms_empty")} hint={canAi ? t("rooms_empty_hint") : undefined} />
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {rooms.map((r) => (
            <RoomCard key={r.id} room={r} onAsk={canAi ? () => setAsking(r) : undefined} />
          ))}
        </div>
      )}

      {data && (
        <div className="flex justify-end">
          <Button variant="ghost" size="sm" onClick={refresh}>
            <RefreshCw /> {t("rooms_refresh")}
          </Button>
        </div>
      )}

      {venue && canAi && (
        <>
          <PhotoRoomSheet open={photoOpen} onOpenChange={setPhotoOpen} venue={venue} onChanged={refresh} />
          <AskRoomSheet open={asking !== null} onOpenChange={(o) => !o && setAsking(null)} venue={venue} room={asking} onChanged={refresh} />
        </>
      )}
    </div>
  );
}

function RoomCard({ room, onAsk }: { room: RoomDto; onAsk?: () => void }) {
  const t = useT();
  const { locale } = useI18n();
  const seats = room.tables.reduce((s, x) => s + x.seats, 0);
  const locked = room.tables.filter((x) => x.locked).length;
  return (
    <Card className="min-w-0 overflow-hidden">
      <div className="flex flex-wrap items-start gap-x-3 gap-y-2 border-b border-neutral-100 px-4 py-3">
        <div className="min-w-0 flex-1">
          <h2 className="break-words text-sm font-semibold text-ink">{roomName(locale, room)}</h2>
          <p className="text-xs tabular-nums text-neutral-700">
            {t("rooms_stats", { tables: room.tables.length, seats, objects: room.objects.length })}
          </p>
          {locked > 0 && (
            <p className="mt-0.5 flex items-center gap-1 text-xs text-amber-900">
              <Lock className="h-3 w-3 shrink-0" aria-hidden /> {t("rooms_locked_legend")}
            </p>
          )}
        </div>
        {onAsk && (
          <Button size="sm" variant="secondary" onClick={onAsk} className="shrink-0">
            <Sparkles /> {t("ai_ask")}
          </Button>
        )}
      </div>
      <div className="p-3">
        <RoomDrawing room={room} />
      </div>
    </Card>
  );
}
