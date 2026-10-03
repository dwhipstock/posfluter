"use client";

// The Menu page's AI assistant ("Ask AI", /v1/menu-ai): type or say a menu
// change, review the proposal as a checklist (before → after), apply the
// ticked ones — the cloud runs them through the same menu edits as the item
// sheet, so the store gets them on its next sync — and undo right after.
// One store at a time (the picked one, or one chosen here in "All stores").
import { useEffect, useRef, useState } from "react";
import { ArrowRight, Check, Loader2, Mic, Send, Sparkles, Square, Undo2 } from "lucide-react";
import { ApiError, post, postForm } from "@/lib/api";
import { useApi, useMe } from "@/lib/hooks";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { aiErrorKey, allTicked, basisItems, clock, encodeWav, MAX_RECORD_SECONDS, salesHeadline, salesNoteKey, tickedIds, toggleChange, toMono } from "@/lib/menu-ai";
import { count } from "@/lib/format";
import { useMoney } from "@/lib/money";
import { shortStoreName, useStores } from "@/lib/store";
import type { AiApplyResult, AiChange, AiChangeDetail, AiProposal, AiRevertResult, AiSalesBasis, AiStatus } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";
import { PhotoAsksView } from "@/components/ai-photo";

type T = ReturnType<typeof useT>;

/** Is the assistant on, and may this user use it. */
export function useAiStatus() {
  const { data } = useApi<AiStatus>("/v1/menu-ai/status", { revalidateOnFocus: false });
  return data;
}

/**
 * The strip under the Menu page's title: "Ask AI" for owners and managers
 * when it is set up; for an owner when it isn't, a one-line note. A strip, not
 * another header button: the header's buttons must never squeeze the title on
 * a phone.
 */
export function AiStrip({ onOpen }: { onOpen: () => void }) {
  const t = useT();
  const status = useAiStatus();
  const { data: me } = useMe();
  if (!status) return null;
  if (!status.enabled) {
    if (me?.role !== "owner") return null;
    return (
      <p className="flex items-center gap-2 text-xs text-neutral-600">
        <Sparkles className="h-3.5 w-3.5 shrink-0" /> {t("ai_not_setup")}
      </p>
    );
  }
  if (!status.canUse) return null;
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-neutral-200 bg-surface px-4 py-3 sm:flex-row sm:items-center">
      <span className="flex min-w-0 flex-1 items-start gap-3">
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-copper-soft text-copper-text">
          <Sparkles className="h-4 w-4" />
        </span>
        <span className="min-w-0 text-sm text-neutral-700">{t("ai_banner")}</span>
      </span>
      <Button className="w-full shrink-0 sm:w-auto" onClick={onOpen}>
        <Sparkles /> {t("ai_ask")}
      </Button>
    </div>
  );
}

export function AiSheet({ open, onOpenChange, onApplied }: { open: boolean; onOpenChange: (o: boolean) => void; onApplied: () => void }) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent>{open && <AiAssistant onApplied={onApplied} />}</SheetContent>
    </Sheet>
  );
}

type Phase = "ask" | "busy" | "review" | "done";

export function errorText(t: T, e: unknown): string {
  if (e instanceof ApiError) {
    const k = aiErrorKey(e.code);
    if (k) return t(k);
    return e.message;
  }
  return t("ai_err_busy");
}

function AiAssistant({ onApplied }: { onApplied: () => void }) {
  const t = useT();
  const { locale } = useI18n();
  const { storeId, venues, nameOf } = useStores();
  const [picked, setPicked] = useState<string | null>(storeId ?? (venues.length === 1 ? venues[0].id : null));
  const venue = storeId ?? picked;
  const [phase, setPhase] = useState<Phase>("ask");
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [proposal, setProposal] = useState<AiProposal | null>(null);
  const [ticked, setTicked] = useState<Set<string>>(new Set());
  const [confirmBulk, setConfirmBulk] = useState<string[] | null>(null);
  const [applied, setApplied] = useState<AiApplyResult | null>(null);
  const [undone, setUndone] = useState<AiRevertResult | null>(null);
  const [busy, setBusy] = useState(false);
  const rec = useVoice((blob) => sendVoice(blob), (k) => setError(t(k)));

  const path = (p: string) => `${p}?venue=${encodeURIComponent(venue ?? "")}`;

  const show = (p: AiProposal) => {
    setProposal(p);
    setTicked(allTicked(p.changes));
    setPhase("review");
  };

  const ask = async (request: string) => {
    if (!venue || !request.trim()) return;
    setError(null);
    setPhase("busy");
    try {
      show(await post<AiProposal>(path("/v1/menu-ai/chat"), { text: request.trim(), lang: locale }));
    } catch (e) {
      setError(errorText(t, e));
      setPhase("ask");
    }
  };

  async function sendVoice(blob: Blob) {
    if (!venue) return;
    setError(null);
    setPhase("busy");
    try {
      const wav = await toWav(blob);
      const form = new FormData();
      form.append("lang", locale);
      form.append("audio", new Blob([wav as BlobPart], { type: "audio/wav" }), "request.wav");
      show(await postForm<AiProposal>(path("/v1/menu-ai/chat/voice"), form));
    } catch (e) {
      setError(e instanceof ApiError ? errorText(t, e) : t("ai_err_audio"));
      setPhase("ask");
    }
  }

  const apply = async (confirm: boolean) => {
    if (!proposal) return;
    setBusy(true);
    setError(null);
    try {
      const r = await post<AiApplyResult>(path("/v1/menu-ai/apply"), {
        proposalId: proposal.proposalId,
        changeIds: tickedIds(ticked, proposal.changes),
        confirmBulk: confirm,
      });
      setConfirmBulk(null);
      setApplied(r);
      setPhase("done");
      onApplied();
    } catch (e) {
      if (e instanceof ApiError && e.code === "menu_ai_confirm_required") setConfirmBulk(proposal.bulkReasons);
      else setError(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };

  const undo = async () => {
    if (!applied) return;
    setBusy(true);
    setError(null);
    try {
      setUndone(await post<AiRevertResult>(path(`/v1/menu-ai/revert/${encodeURIComponent(applied.applyId)}`), {}));
      onApplied();
    } catch (e) {
      setError(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };

  const again = () => {
    setProposal(null);
    setApplied(null);
    setUndone(null);
    setError(null);
    setText("");
    setPhase("ask");
  };

  const count = proposal ? tickedIds(ticked, proposal.changes).length : 0;

  return (
    <>
      <SheetHeader>
        <div className="min-w-0">
          <SheetTitle className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 shrink-0 text-copper-text" /> {t("ai_title")}
          </SheetTitle>
          {venue && <p className="mt-0.5 text-xs text-neutral-600">{t("menu_scope_one", { store: shortStoreName(nameOf(venue)) })}</p>}
        </div>
      </SheetHeader>
      <SheetBody className="space-y-4">
        {!storeId && venues.length > 1 && phase === "ask" && (
          <div className="space-y-2">
            <p className="text-sm font-medium text-ink">{t("ai_pick_store")}</p>
            <div className="flex flex-wrap gap-2">
              {venues.map((v) => (
                <Button key={v.id} size="sm" variant={picked === v.id ? "dark" : "secondary"} onClick={() => setPicked(v.id)}>
                  {picked === v.id && <Check />} {shortStoreName(v.name)}
                </Button>
              ))}
            </div>
          </div>
        )}

        {phase === "ask" && (
          <AskForm
            text={text}
            setText={setText}
            disabled={!venue}
            recording={rec.recording}
            seconds={rec.seconds}
            onSend={() => ask(text)}
            onMic={rec.recording ? rec.stop : rec.start}
          />
        )}

        {phase === "busy" && (
          <p className="flex items-center gap-2 py-6 text-sm text-neutral-700" role="status">
            <Loader2 className="h-4 w-4 animate-spin" /> {t("ai_thinking")}
          </p>
        )}

        {proposal && (phase === "review" || phase === "done") && (
          <ProposalView proposal={proposal} ticked={ticked} setTicked={setTicked} readOnly={phase === "done"} />
        )}

        {proposal && venue && !proposal.refusal && (proposal.photos?.length ?? 0) > 0 && (phase === "review" || phase === "done") && (
          <PhotoAsksView key={proposal.proposalId || proposal.photos![0].itemId} venue={venue} asks={proposal.photos!} onChanged={onApplied} />
        )}

        {phase === "done" && applied && (
          <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2.5 text-sm text-emerald-900" role="status">
            {undone
              ? undone.skipped > 0
                ? t("ai_undo_partial", { n: undone.skipped })
                : t("ai_undone")
              : t("ai_applied", { n: applied.applied })}
          </div>
        )}

        {error && (
          <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">
            {error}
          </p>
        )}
      </SheetBody>

      {(phase === "review" || phase === "done") && (
        <SheetFooter className="flex flex-wrap items-center justify-end gap-2">
          {phase === "review" && proposal && proposal.changes.length > 0 ? (
            <>
              <Button variant="secondary" disabled={busy} onClick={again}>
                {t("ai_discard")}
              </Button>
              <Button disabled={busy || count === 0} onClick={() => apply(false)}>
                {busy ? <Loader2 className="animate-spin" /> : <Check />} {t("ai_apply", { n: count })}
              </Button>
            </>
          ) : (
            <>
              {phase === "done" && !undone && (
                <Button variant="secondary" disabled={busy} onClick={undo}>
                  {busy ? <Loader2 className="animate-spin" /> : <Undo2 />} {t("ai_undo")}
                </Button>
              )}
              <Button disabled={busy} onClick={again}>
                {t("ai_again")}
              </Button>
            </>
          )}
        </SheetFooter>
      )}

      <Dialog open={confirmBulk !== null} onOpenChange={(o) => !o && setConfirmBulk(null)}>
        <DialogContent>
          <DialogTitle>{t("ai_bulk_title", { n: count })}</DialogTitle>
          <DialogDescription asChild>
            <div className="space-y-1 text-sm text-neutral-700">
              {(confirmBulk ?? []).map((r) => (
                <p key={r}>{t(BULK_KEY[r] ?? "ai_bulk_many")}</p>
              ))}
              <p className="text-neutral-600">{t("ai_bulk_undo_hint")}</p>
            </div>
          </DialogDescription>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirmBulk(null)}>
              {t("cancel")}
            </Button>
            <Button disabled={busy} onClick={() => apply(true)}>
              {t("ai_bulk_confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

const BULK_KEY: Record<string, MsgKey> = {
  many_changes: "ai_bulk_many",
  removals: "ai_bulk_removals",
  price_jumps: "ai_bulk_prices",
};

function AskForm({
  text,
  setText,
  disabled,
  recording,
  seconds,
  onSend,
  onMic,
}: {
  text: string;
  setText: (s: string) => void;
  disabled: boolean;
  recording: boolean;
  seconds: number;
  onSend: () => void;
  onMic: () => void;
}) {
  const t = useT();
  const status = useAiStatus();
  const examples: MsgKey[] = ["ai_example_sales", "ai_example_1", "ai_example_2", "ai_example_3", ...(status?.photos ? (["ai_example_photo"] as MsgKey[]) : [])];
  return (
    <div className="space-y-3">
      <textarea
        value={text}
        rows={3}
        maxLength={2000}
        disabled={disabled || recording}
        placeholder={t("ai_placeholder")}
        aria-label={t("ai_title")}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            onSend();
          }
        }}
        className="block w-full resize-none rounded-control border border-neutral-200 bg-surface px-3 py-2 text-sm text-ink placeholder:text-neutral-500 focus-visible:border-accent/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/25 disabled:opacity-50"
      />
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant={recording ? "destructive" : "secondary"}
          disabled={disabled}
          onClick={onMic}
          aria-pressed={recording}
          className="min-w-0"
        >
          {recording ? <Square /> : <Mic />}
          {recording ? t("ai_mic_stop") : t("ai_mic")}
        </Button>
        {recording && (
          <span className="flex items-center gap-1.5 text-xs tabular-nums text-neutral-700" role="status">
            <span className="h-2 w-2 animate-pulse rounded-full bg-red-600" />
            {t("ai_recording", { s: Math.max(0, MAX_RECORD_SECONDS - Math.floor(seconds)) })} · {clock(seconds)}
          </span>
        )}
        <Button className="ml-auto" disabled={disabled || recording || !text.trim()} onClick={onSend}>
          <Send /> {t("ai_send")}
        </Button>
      </div>
      {!recording && (
        <div className="flex flex-wrap gap-1.5">
          {examples.map((k) => (
            <button
              key={k}
              type="button"
              disabled={disabled}
              onClick={() => setText(t(k))}
              className="rounded-full border border-neutral-200 px-3 py-1 text-xs text-neutral-700 transition-colors hover:bg-neutral-50 disabled:opacity-50"
            >
              {t(k)}
            </button>
          ))}
        </div>
      )}
      <p className="text-xs text-neutral-600">{t("ai_review_hint")}</p>
    </div>
  );
}

const KIND: Record<string, { key: MsgKey; variant: "success" | "destructive" | "default" | "pink" | "copper" }> = {
  add_item: { key: "ai_kind_add_item", variant: "success" },
  add_category: { key: "ai_kind_add_category", variant: "copper" },
  update_item: { key: "ai_kind_update_item", variant: "default" },
  remove_item: { key: "ai_kind_remove_item", variant: "destructive" },
  rename_category: { key: "ai_kind_rename_category", variant: "copper" },
  reorder_categories: { key: "ai_kind_reorder_categories", variant: "copper" },
  set_name: { key: "ai_kind_set_name", variant: "pink" },
};

function ProposalView({
  proposal,
  ticked,
  setTicked,
  readOnly,
}: {
  proposal: AiProposal;
  ticked: Set<string>;
  setTicked: (s: Set<string>) => void;
  readOnly: boolean;
}) {
  const t = useT();
  return (
    <div className="space-y-3">
      {proposal.transcript && <p className="text-sm italic text-neutral-700">{t("ai_heard", { text: proposal.transcript })}</p>}
      {proposal.refusal ? (
        <p className="rounded-lg bg-neutral-100 px-3 py-2.5 text-sm text-neutral-800">{proposal.message}</p>
      ) : (
        <>
          {proposal.summary && <p className="text-sm font-medium text-ink">{proposal.summary}</p>}
          <SalesView proposal={proposal} />
          {proposal.changes.length > 0 && (
            <div className="space-y-2">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-neutral-600">{t("ai_changes")}</h3>
              {proposal.changes.map((c) => (
                <ChangeRow
                  key={c.id}
                  change={c}
                  currency={proposal.currency}
                  checked={ticked.has(c.id)}
                  disabled={readOnly}
                  onToggle={() => setTicked(toggleChange(ticked, proposal.changes, c.id))}
                />
              ))}
            </div>
          )}
        </>
      )}
      {proposal.rejected.length > 0 && <p className="text-xs text-neutral-600">{t("ai_skipped", { n: proposal.rejected.length })}</p>}
    </div>
  );
}

/**
 * What a sales-based proposal or answer is based on — every number computed by
 * the cloud from the store's sales, never the model's. A proposal gets one
 * basis line above its changes; an answer gets a plain list.
 */
function SalesView({ proposal }: { proposal: AiProposal }) {
  const t = useT();
  const { fmt, locale } = useI18n();
  const m = useMoney();
  const bases = proposal.sales ?? [];
  const notes = (proposal.salesNotes ?? []).filter((n) => salesNoteKey(n) !== null);
  if (bases.length === 0 && notes.length === 0) return null;
  const period = (b: AiSalesBasis) => ({ from: fmt.day(b.from), to: fmt.day(b.to), store: shortStoreName(b.store) });
  const units = (n: number) => count(n, locale);
  const headline = (b: AiSalesBasis) => {
    const h = salesHeadline(b);
    return t(h.key, h.vars);
  };
  const shown = bases.filter((b) => b.rows.length > 0);
  const noteVars = bases[0] ? period(bases[0]) : { from: "", to: "", store: "" };
  return (
    <div className="space-y-2">
      {proposal.answer && shown.length > 0 && (
        <h3 className="text-xs font-semibold uppercase tracking-wide text-neutral-600">{t("ai_sales_answer")}</h3>
      )}
      {shown.map((b, i) =>
        proposal.answer ? (
          <div key={i} className="space-y-1.5 rounded-lg border border-neutral-200 bg-surface px-3 py-2.5">
            <p className="text-sm font-medium text-ink">{headline(b)}</p>
            <ol className="space-y-1">
              {b.rows.map((r, j) => (
                <li key={r.itemId} className="flex flex-wrap items-baseline gap-x-2 text-sm text-neutral-800">
                  <span className="w-5 shrink-0 tabular-nums text-neutral-600">{j + 1}.</span>
                  <span className="min-w-0 break-words font-medium text-ink">{r.name}</span>
                  <span className="tabular-nums text-neutral-700">
                    {b.rank === "unsold"
                      ? r.lastSold
                        ? t("ai_sales_last_sold", { date: fmt.day(r.lastSold) })
                        : t("ai_sales_never")
                      : t("ai_sales_row", { units: units(r.units), revenue: m.fmtIn(b.currency, r.revenueMinor) })}
                  </span>
                </li>
              ))}
            </ol>
            <p className="text-xs text-neutral-600">{t("ai_sales_period", period(b))}</p>
          </div>
        ) : (
          <div key={i} className="space-y-1 rounded-lg bg-neutral-50 px-3 py-2.5">
            <p className="break-words text-sm text-neutral-800">
              <span className="font-medium text-ink">
                {headline(b)}, {period(b).from} – {period(b).to}, {period(b).store}:
              </span>{" "}
              {basisItems(b, units, (minor) => m.fmtIn(b.currency, minor))}
            </p>
            <p className="text-xs text-neutral-600">{t("ai_sales_period", period(b))}</p>
          </div>
        )
      )}
      {notes.map((n, i) => (
        <p key={i} className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-950">
          {t(salesNoteKey(n)!, {
            ...noteVars,
            item: n.item ?? "",
            size: n.size ?? "",
            n: n.n ?? 0,
            want: n.want ?? 0,
          })}
        </p>
      ))}
    </div>
  );
}

function ChangeRow({
  change,
  currency,
  checked,
  disabled,
  onToggle,
}: {
  change: AiChange;
  currency: string;
  checked: boolean;
  disabled: boolean;
  onToggle: () => void;
}) {
  const t = useT();
  const kind = KIND[change.kind];
  return (
    <label
      className={`flex items-start gap-3 rounded-lg border px-3 py-2.5 ${checked ? "border-neutral-300 bg-surface" : "border-neutral-100 bg-neutral-50"} ${disabled ? "" : "cursor-pointer"}`}
    >
      <input
        type="checkbox"
        className="mt-0.5 h-4 w-4 shrink-0 accent-[var(--c-accent)]"
        checked={checked}
        disabled={disabled}
        onChange={onToggle}
      />
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-1.5">
          {kind && <Badge variant={kind.variant}>{t(kind.key)}</Badge>}
          {change.title && <span className="break-words text-sm font-medium text-ink">{change.title}</span>}
          {change.category && <span className="text-xs text-neutral-600">· {change.category}</span>}
        </span>
        {change.details.length > 0 && (
          <span className="mt-1 block space-y-0.5">
            {change.details.map((d, i) => (
              <DetailLine key={i} d={d} currency={currency} />
            ))}
          </span>
        )}
        {change.needs && <span className="mt-1 block text-xs text-neutral-600">{t("ai_with_category")}</span>}
      </span>
    </label>
  );
}

function fieldLabel(t: T, d: AiChangeDetail): string {
  switch (d.field) {
    case "nameEn":
      return t("menu_name_en");
    case "nameFr":
      return t("menu_name_fr");
    case "descriptionEn":
      return t("menu_desc_en");
    case "descriptionFr":
      return t("menu_desc_fr");
    case "category":
      return t("menu_col_category");
    case "available":
      return t("menu_available");
    case "price":
      return d.label ? `${t("menu_size_price")} (${d.label})` : t("menu_size_price");
    case "name": {
      const k = `menu_lang_${d.label}` as MsgKey;
      const lang = t(k);
      return t("menu_name_in", { lang: lang === k ? (d.label ?? "").toUpperCase() : lang });
    }
    case "order":
      return t("ai_field_order");
    default:
      return d.field;
  }
}

function DetailLine({ d, currency }: { d: AiChangeDetail; currency: string }) {
  const t = useT();
  const m = useMoney();
  const value = (raw: string | null | undefined, minor: number | null | undefined) => {
    if (d.field === "price" && minor != null) return m.fmtIn(currency, minor);
    if (d.field === "available") return raw === "true" ? t("ai_on") : raw === "false" ? t("ai_off") : raw;
    return raw;
  };
  const before = value(d.before, d.beforeMinor);
  const after = value(d.after, d.afterMinor);
  return (
    <span className="flex flex-wrap items-baseline gap-x-1.5 text-xs text-neutral-700">
      <span className="text-neutral-600">{fieldLabel(t, d)}:</span>
      {before && (
        <>
          <span className="break-words text-neutral-600 line-through">{before}</span>
          <ArrowRight className="h-3 w-3 shrink-0 self-center text-neutral-500" aria-hidden />
        </>
      )}
      <span className="break-words font-medium text-ink">{after}</span>
    </span>
  );
}

/** The browser's recording (webm/opus, mp4/aac…) → 16 kHz mono WAV, decoded by the browser itself. */
export async function toWav(blob: Blob): Promise<Uint8Array> {
  const Ctx: typeof AudioContext | undefined =
    window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctx) throw new Error("no audio context");
  const ctx = new Ctx();
  try {
    const audio = await ctx.decodeAudioData(await blob.arrayBuffer());
    const channels = Array.from({ length: audio.numberOfChannels }, (_, i) => audio.getChannelData(i));
    return encodeWav(toMono(channels, audio.sampleRate));
  } finally {
    ctx.close().catch(() => {});
  }
}

/** The mic: asks permission on first use, records up to [MAX_RECORD_SECONDS], hands the clip to [onClip]. */
export function useVoice(onClip: (b: Blob) => void, onError: (k: MsgKey) => void) {
  const [recording, setRecording] = useState(false);
  const [seconds, setSeconds] = useState(0);
  const recorder = useRef<MediaRecorder | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const clipRef = useRef(onClip);
  clipRef.current = onClip;

  const cleanup = () => {
    if (timer.current) clearInterval(timer.current);
    timer.current = null;
  };

  useEffect(
    () => () => {
      cleanup();
      const r = recorder.current;
      if (r && r.state !== "inactive") {
        r.onstop = null;
        r.stop();
      }
      r?.stream.getTracks().forEach((tr) => tr.stop());
    },
    []
  );

  const stop = () => {
    const r = recorder.current;
    if (r && r.state !== "inactive") r.stop();
  };

  const start = async () => {
    if (typeof window === "undefined" || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      onError("ai_mic_unsupported");
      return;
    }
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      onError("ai_mic_denied");
      return;
    }
    const chunks: Blob[] = [];
    const r = new MediaRecorder(stream);
    recorder.current = r;
    r.ondataavailable = (e) => {
      if (e.data.size > 0) chunks.push(e.data);
    };
    r.onstop = () => {
      cleanup();
      stream.getTracks().forEach((tr) => tr.stop());
      setRecording(false);
      const blob = new Blob(chunks, { type: r.mimeType || "audio/webm" });
      if (blob.size > 0) clipRef.current(blob);
    };
    const began = Date.now();
    setSeconds(0);
    setRecording(true);
    r.start();
    timer.current = setInterval(() => {
      const s = (Date.now() - began) / 1000;
      setSeconds(s);
      if (s >= MAX_RECORD_SECONDS) stop();
    }, 250);
  };

  return { recording, seconds, start, stop };
}
