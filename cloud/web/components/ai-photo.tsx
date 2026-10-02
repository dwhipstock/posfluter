"use client";

// AI item photos (/v1/menu-ai/photos): a picture in the store's house style,
// shown first; "Use this photo" makes it the item's photo (the store gets it
// on its next sync), "Try again" makes another, "Discard" drops it, and Undo
// puts the previous photo back. Used by the AI assistant ("generate a picture
// for the iced tea", "photos for every drink") and the item sheet's
// "Generate photo" / "Enhance photo". One store at a time.
import { useEffect, useRef, useState } from "react";
import { Check, ImagePlus, Loader2, RefreshCw, Sparkles, Undo2, Wand2, X } from "lucide-react";
import { ApiError, post } from "@/lib/api";
import { useI18n, useT } from "@/lib/i18n/context";
import {
  initialStates,
  needsBatchConfirm,
  nextWaiting,
  photoErrorKey,
  previewSrc,
  progress,
  readyIndexes,
  update,
  type PhotoState,
} from "@/lib/ai-photo";
import { aiErrorKey } from "@/lib/menu-ai";
import type { AiPhotoAcceptResult, AiPhotoAsk, AiPhotoPreview, MenuItem } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

type T = ReturnType<typeof useT>;

/** Limits that stop a whole batch, not just one picture. */
const STOPS_BATCH = new Set(["menu_ai_photo_daily_limit", "menu_ai_daily_limit", "menu_ai_too_many", "rate_limited", "menu_ai_photos_disabled"]);

export function photoErrorText(t: T, e: unknown): string {
  if (e instanceof ApiError) {
    const k = photoErrorKey(e.code) ?? aiErrorKey(e.code);
    return k ? t(k) : e.message;
  }
  return t("ai_err_busy");
}

/** The pictures of one request, made one at a time, each with its own Use / Try again / Discard. */
function usePhotoRunner(venue: string | null, onChanged: () => void, everyStore = false) {
  const t = useT();
  const { locale } = useI18n();
  const [states, setStates] = useState<PhotoState[]>([]);
  const [running, setRunning] = useState(false);
  const [busy, setBusy] = useState(false);
  const statesRef = useRef(states);
  statesRef.current = states;
  const inFlight = useRef(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true; // (again: React's dev double mount runs the cleanup once)
    return () => {
      alive.current = false;
    };
  }, []);

  const path = (p: string) => `${p}?venue=${encodeURIComponent(venue ?? "")}`;
  const set = (i: number, patch: Partial<PhotoState>) => setStates((s) => update(s, i, patch));

  const make = async (i: number) => {
    const s = statesRef.current[i];
    if (!venue || !s || inFlight.current) return;
    inFlight.current = true;
    set(i, { status: "making", error: undefined });
    try {
      const preview = await post<AiPhotoPreview>(path("/v1/menu-ai/photos/generate"), {
        itemId: s.ask.itemId,
        mode: s.ask.mode,
        lang: locale,
      });
      if (alive.current) set(i, { status: "ready", preview });
    } catch (e) {
      if (!alive.current) return;
      const error = photoErrorText(t, e);
      set(i, { status: "failed", error });
      if (e instanceof ApiError && STOPS_BATCH.has(e.code)) {
        // the limit is reached: the rest of the batch would only fail the same way
        setStates((all) => all.map((x) => (x.status === "waiting" ? { ...x, status: "failed", error } : x)));
        setRunning(false);
      }
    } finally {
      inFlight.current = false;
    }
  };

  // the batch, one picture at a time, in order
  useEffect(() => {
    if (!running) return;
    const i = nextWaiting(states);
    if (i >= 0) void make(i);
    else if (!states.some((s) => s.status === "making")) setRunning(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running, states]);

  const act = async (i: number, fn: () => Promise<Partial<PhotoState>>) => {
    setBusy(true);
    set(i, { error: undefined });
    try {
      set(i, await fn());
    } catch (e) {
      set(i, { error: photoErrorText(t, e) });
    } finally {
      setBusy(false);
    }
  };

  const accept = (i: number) =>
    act(i, async () => {
      // "All stores": the photo also goes to every other store that carries the item (Undo puts each one's back)
      const accept = path(`/v1/menu-ai/photos/${encodeURIComponent(statesRef.current[i].preview!.photoId)}/accept`);
      await post<AiPhotoAcceptResult>(everyStore ? `${accept}&everyStore=1` : accept, {});
      onChanged();
      return { status: "accepted" };
    });

  const discard = (i: number) =>
    act(i, async () => {
      const id = statesRef.current[i].preview?.photoId;
      if (id) await post(path(`/v1/menu-ai/photos/${encodeURIComponent(id)}/discard`), {});
      return { status: "discarded" };
    });

  const undo = (i: number) =>
    act(i, async () => {
      await post(path(`/v1/menu-ai/photos/${encodeURIComponent(statesRef.current[i].preview!.photoId)}/undo`), {});
      onChanged();
      return { status: "undone" };
    });

  const acceptAll = async () => {
    for (const i of readyIndexes(statesRef.current)) await accept(i);
  };

  return {
    states,
    running,
    busy,
    load: (asks: AiPhotoAsk[], start: boolean) => {
      setStates(initialStates(asks));
      setRunning(start);
    },
    start: () => setRunning(true),
    retry: (i: number) => void make(i),
    accept,
    discard,
    undo,
    acceptAll,
  };
}

/** One picture: making it, the preview with its actions, or what became of it. */
function PhotoCard({
  state,
  busy,
  onAccept,
  onRetry,
  onDiscard,
  onUndo,
  showTitle = true,
}: {
  state: PhotoState;
  busy: boolean;
  onAccept: () => void;
  onRetry: () => void;
  onDiscard: () => void;
  onUndo: () => void;
  showTitle?: boolean;
}) {
  const t = useT();
  const { ask, status, preview, error } = state;
  const enhanced = (preview?.source ?? (ask.mode === "enhance" ? "ai_enhanced" : "ai_generated")) === "ai_enhanced";
  return (
    <div className="space-y-2 rounded-lg border border-neutral-200 bg-surface p-3">
      {showTitle && (
        <p className="flex flex-wrap items-center gap-1.5">
          <Badge variant="copper">{enhanced ? t("ai_photo_kind_enhance") : t("ai_photo_kind_generate")}</Badge>
          <span className="min-w-0 break-words text-sm font-medium text-ink">{ask.title}</span>
          {ask.category && <span className="text-xs text-neutral-600">· {ask.category}</span>}
        </p>
      )}

      {status === "waiting" && <p className="text-xs text-neutral-600">{t("ai_photo_waiting")}</p>}

      {status === "making" && (
        <div className="grid aspect-square w-full max-w-[16rem] place-items-center rounded-lg bg-neutral-100 p-4 text-center" role="status">
          <span className="space-y-2 text-xs text-neutral-700">
            <Loader2 className="mx-auto h-5 w-5 animate-spin" />
            <span className="block">{t("ai_photo_making")}</span>
          </span>
        </div>
      )}

      {preview && (status === "ready" || status === "accepted" || status === "undone") && (
        <span className="relative block w-full max-w-[16rem]">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={previewSrc(preview)}
            alt={t("ai_photo_alt", { name: ask.title })}
            className={`aspect-square w-full rounded-lg bg-neutral-100 object-cover ${status === "undone" ? "opacity-40" : ""}`}
          />
          <span className="absolute bottom-1.5 right-1.5 rounded bg-ink px-1 text-[10px] font-bold leading-4 text-white">
            {t("menu_ai_badge")}
          </span>
        </span>
      )}

      {status === "ready" && preview?.replaces && <p className="text-xs text-neutral-600">{t("ai_photo_replaces")}</p>}
      {status === "accepted" && (
        <p className="flex items-start gap-1.5 text-xs text-emerald-800" role="status">
          <Check className="mt-0.5 h-3.5 w-3.5 shrink-0" /> {t("ai_photo_accepted")}
        </p>
      )}
      {status === "undone" && <p className="text-xs text-neutral-700">{t("ai_photo_undone")}</p>}
      {status === "discarded" && <p className="text-xs text-neutral-600">{t("ai_photo_discarded")}</p>}
      {error && (
        <p className="rounded-md bg-red-50 px-2 py-1.5 text-xs text-red-800" role="alert">
          {error}
        </p>
      )}

      {(status === "ready" || status === "failed") && (
        <div className="flex flex-wrap gap-2">
          {status === "ready" && (
            <Button size="sm" disabled={busy} onClick={onAccept}>
              <Check /> {t("ai_photo_accept")}
            </Button>
          )}
          <Button size="sm" variant="secondary" disabled={busy} onClick={onRetry}>
            <RefreshCw /> {t("ai_photo_retry")}
          </Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={onDiscard}>
            <X /> {t("ai_photo_discard")}
          </Button>
        </div>
      )}
      {status === "accepted" && (
        <Button size="sm" variant="secondary" disabled={busy} onClick={onUndo}>
          {busy ? <Loader2 className="animate-spin" /> : <Undo2 />} {t("ai_photo_undo")}
        </Button>
      )}
    </div>
  );
}

/**
 * The assistant's picture requests. One picture is made straight away; for
 * several ("photos for every drink") the manager confirms first, then they are
 * made one at a time with a progress line, each to use or discard.
 */
export function PhotoAsksView({ venue, asks, onChanged }: { venue: string; asks: AiPhotoAsk[]; onChanged: () => void }) {
  const t = useT();
  const r = usePhotoRunner(venue, onChanged);
  const [confirmed, setConfirmed] = useState(!needsBatchConfirm(asks));
  const loaded = useRef(false);
  useEffect(() => {
    if (loaded.current) return;
    loaded.current = true;
    r.load(asks, !needsBatchConfirm(asks));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { done, total } = progress(r.states);
  const ready = readyIndexes(r.states).length;
  return (
    <div className="space-y-2">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-neutral-600">{t("ai_photo_section")}</h3>
      {!confirmed ? (
        <div className="space-y-2 rounded-lg border border-neutral-200 bg-surface p-3">
          <p className="text-sm font-medium text-ink">{t("ai_photo_bulk_title", { n: asks.length })}</p>
          <p className="break-words text-xs text-neutral-700">{asks.map((a) => a.title).join(" · ")}</p>
          <p className="text-xs text-neutral-600">{t("ai_photo_bulk_body")}</p>
          <Button
            size="sm"
            onClick={() => {
              setConfirmed(true);
              r.start();
            }}
          >
            <Sparkles /> {t("ai_photo_bulk_go", { n: asks.length })}
          </Button>
        </div>
      ) : (
        <>
          {total > 1 && (r.running || done < total) && (
            <p className="flex items-center gap-2 text-xs text-neutral-700" role="status">
              {r.running && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              {t("ai_photo_progress", { i: Math.min(done + 1, total), n: total })}
            </p>
          )}
          {r.states.map((s, i) => (
            <PhotoCard
              key={s.ask.id}
              state={s}
              busy={r.busy || s.status === "making"}
              onAccept={() => r.accept(i)}
              onRetry={() => r.retry(i)}
              onDiscard={() => r.discard(i)}
              onUndo={() => r.undo(i)}
            />
          ))}
          {ready > 1 && !r.running && (
            <Button disabled={r.busy} onClick={r.acceptAll} className="w-full sm:w-auto">
              <Check /> {t("ai_photo_accept_all", { n: ready })}
            </Button>
          )}
        </>
      )}
      <p className="text-xs text-neutral-600">{t("ai_photo_hint")}</p>
    </div>
  );
}

/**
 * The item sheet's "Generate photo" / "Enhance photo" (an existing item). The
 * picture is made at [venue] (a store that carries the item); with
 * [everyStore] ("All stores") accepting it also makes it the photo at every
 * other store that carries the item. [appliesTo] names the stores that get it.
 */
export function ItemPhotoPanel({
  venue,
  item,
  title,
  onChanged,
  everyStore = false,
  appliesTo = [],
}: {
  venue: string | null;
  item: MenuItem;
  title: string;
  onChanged: () => void;
  everyStore?: boolean;
  appliesTo?: string[];
}) {
  const t = useT();
  const r = usePhotoRunner(venue, onChanged, everyStore);
  const state = r.states[0];
  const begin = (mode: "generate" | "enhance") =>
    r.load([{ id: "item", itemId: item.id, title, mode, hasPhoto: item.photoVersion !== null }], true);
  const working = state?.status === "making";
  return (
    <div className="space-y-2 rounded-lg border border-neutral-100 px-3 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-auto flex min-w-0 items-center gap-1.5 text-sm font-medium">
          <Sparkles className="h-4 w-4 shrink-0 text-copper-text" /> {t("ai_photo_title")}
        </span>
        {venue ? (
          <span className="flex flex-wrap gap-2">
            <Button size="sm" variant="secondary" disabled={working || r.busy} onClick={() => begin("generate")}>
              <ImagePlus /> {t("ai_photo_generate")}
            </Button>
            {item.photoVersion !== null && (
              <Button size="sm" variant="secondary" disabled={working || r.busy} onClick={() => begin("enhance")}>
                <Wand2 /> {t("ai_photo_enhance")}
              </Button>
            )}
          </span>
        ) : null}
      </div>
      {!venue && <p className="text-xs text-neutral-600">{t("ai_photo_pick_store")}</p>}
      {venue && appliesTo.length > 0 && (
        <p className="text-xs text-neutral-700">{t("ai_photo_applies_to", { stores: appliesTo.join(", ") })}</p>
      )}
      {venue && !state && <p className="text-xs text-neutral-600">{t("ai_photo_hint")}</p>}
      {state && (
        <PhotoCard
          state={state}
          busy={r.busy || working}
          showTitle={false}
          onAccept={() => r.accept(0)}
          onRetry={() => r.retry(0)}
          onDiscard={() => r.discard(0)}
          onUndo={() => r.undo(0)}
        />
      )}
    </div>
  );
}
