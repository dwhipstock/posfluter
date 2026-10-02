"use client";

// The Rooms page's AI: "New room from photo" (the phone's camera → the AI
// draws the room → the manager checks the drawing and creates it) and, per
// room, "Ask AI" (type or say a change → before / after drawings and the
// change list → apply). Everything goes through the cloud's room edits and
// reaches the store on its next sync; every apply can be undone right after.
// No drag-and-drop editor, on purpose: the drawings are read-only.
import { useEffect, useRef, useState } from "react";
import { ArrowRight, Camera, Check, ImagePlus, Loader2, Mic, Send, Sparkles, Square, Undo2 } from "lucide-react";
import { ApiError, post, postForm } from "@/lib/api";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { clock, MAX_RECORD_SECONDS } from "@/lib/menu-ai";
import { fitSize, ghostRoom, highlights, photoRoom, roomErrorKey, roomName, roomStats } from "@/lib/rooms";
import type { FloorChange, FloorChangeDetail, FloorEditProposal, RoomAiApplyResult, RoomAiRevertResult, RoomDto, RoomPhotoProposal } from "@/lib/types";
import { toWav, useVoice } from "@/components/menu-ai";
import { DrawingLegend, RoomDrawing } from "@/components/room-drawing";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";

type T = ReturnType<typeof useT>;

function errorText(t: T, e: unknown): string {
  if (e instanceof ApiError) {
    const k = roomErrorKey(e.code);
    return k ? t(k) : e.message;
  }
  return t("ai_err_busy");
}

const withVenue = (path: string, venue: string, extra = "") =>
  `${path}?venue=${encodeURIComponent(venue)}${extra}`;

/** The photo, upright and at most 1600 px on its long side, as JPEG; the file itself if the browser can't. */
async function shrink(file: File): Promise<Blob> {
  try {
    const bmp = await createImageBitmap(file, { imageOrientation: "from-image" });
    const { width, height } = fitSize(bmp.width, bmp.height);
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return file;
    ctx.drawImage(bmp, 0, 0, width, height);
    bmp.close?.();
    const blob = await new Promise<Blob | null>((res) => canvas.toBlob(res, "image/jpeg", 0.85));
    return blob ?? file;
  } catch {
    return file;
  }
}

function Stats({ room }: { room: RoomDto }) {
  const t = useT();
  const s = roomStats(room);
  return <p className="text-xs tabular-nums text-neutral-700">{t("rooms_stats", { tables: s.tables, seats: s.seats, objects: s.objects })}</p>;
}

function Alert({ kind, children }: { kind: "error" | "ok" | "info"; children: React.ReactNode }) {
  const cls =
    kind === "error"
      ? "bg-red-50 text-red-800"
      : kind === "ok"
        ? "border border-emerald-200 bg-emerald-50 text-emerald-900"
        : "bg-neutral-100 text-neutral-800";
  return (
    <p className={`rounded-lg px-3 py-2.5 text-sm ${cls}`} role={kind === "error" ? "alert" : "status"}>
      {children}
    </p>
  );
}

// ── New room from a photo ────────────────────────────────────────────────

export function PhotoRoomSheet({
  open,
  onOpenChange,
  venue,
  onChanged,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  venue: string;
  onChanged: () => void;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent>{open && <PhotoRoom venue={venue} onChanged={onChanged} onClose={() => onOpenChange(false)} />}</SheetContent>
    </Sheet>
  );
}

type PhotoPhase = "pick" | "busy" | "review" | "done";

function PhotoRoom({ venue, onChanged, onClose }: { venue: string; onChanged: () => void; onClose: () => void }) {
  const t = useT();
  const { locale } = useI18n();
  const [name, setName] = useState(() => t("rooms_name_default"));
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [phase, setPhase] = useState<PhotoPhase>("pick");
  const [proposal, setProposal] = useState<RoomPhotoProposal | null>(null);
  const [applied, setApplied] = useState<RoomAiApplyResult | null>(null);
  const [undone, setUndone] = useState<RoomAiRevertResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  // without `capture`: some phones open only the camera with it, never the photos
  const library = useRef<HTMLInputElement>(null);

  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview);
  }, [preview]);

  const pick = (f: File | null) => {
    setError(null);
    setFile(f);
    setPreview(f ? URL.createObjectURL(f) : null);
  };

  const draw = async () => {
    if (!file) {
      setError(t("rooms_err_no_image"));
      return;
    }
    setError(null);
    setPhase("busy");
    try {
      const form = new FormData();
      form.append("name", name.trim() || t("rooms_name_default"));
      form.append("lang", locale);
      form.append("image", await shrink(file), "room.jpg");
      const p = await postForm<RoomPhotoProposal>(withVenue("/v1/room-ai/photo", venue), form);
      setProposal(p);
      setPhase("review");
    } catch (e) {
      setError(errorText(t, e));
      setPhase("pick");
    }
  };

  const apply = async () => {
    if (!proposal) return;
    setBusy(true);
    setError(null);
    try {
      const r = await post<RoomAiApplyResult>(withVenue("/v1/room-ai/photo/apply", venue), {
        proposalId: proposal.proposalId,
        name: name.trim() || proposal.roomName,
      });
      setApplied(r);
      setPhase("done");
      onChanged();
    } catch (e) {
      setError(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };

  const undo = async () => {
    if (!applied) return;
    setBusy(true);
    setError(null);
    try {
      setUndone(await post<RoomAiRevertResult>(withVenue(`/v1/room-ai/revert/${encodeURIComponent(applied.applyId)}`, venue), {}));
      onChanged();
    } catch (e) {
      setError(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };

  const discard = () => {
    setProposal(null);
    setPhase("pick");
    setError(null);
  };

  const drawn = proposal && !proposal.refusal ? photoRoom(proposal, name) : null;

  return (
    <>
      <SheetHeader>
        <SheetTitle className="flex min-w-0 items-center gap-2">
          <Camera className="h-4 w-4 shrink-0 text-copper-text" /> <span className="min-w-0 break-words">{t("rooms_new_photo")}</span>
        </SheetTitle>
      </SheetHeader>
      <SheetBody className="space-y-4">
        {(phase === "pick" || phase === "review") && (
          <div className="space-y-1.5">
            <Label htmlFor="room-name">{t("rooms_name_label")}</Label>
            <Input id="room-name" value={name} maxLength={60} disabled={phase === "review" && busy} onChange={(e) => setName(e.target.value)} />
          </div>
        )}

        {phase === "pick" && (
          <div className="space-y-3">
            <input
              ref={input}
              type="file"
              accept="image/*"
              capture="environment"
              className="sr-only"
              tabIndex={-1}
              onChange={(e) => pick(e.target.files?.[0] ?? null)}
            />
            <input
              ref={library}
              type="file"
              accept="image/*"
              className="sr-only"
              tabIndex={-1}
              onChange={(e) => pick(e.target.files?.[0] ?? null)}
            />
            {preview ? (
              <div className="space-y-2">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={preview} alt={t("rooms_photo_alt")} className="max-h-64 w-full rounded-xl bg-neutral-100 object-contain" />
                <Button variant="secondary" className="w-full" onClick={() => input.current?.click()}>
                  <ImagePlus /> {t("rooms_retake")}
                </Button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => input.current?.click()}
                className="flex w-full flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-neutral-300 bg-neutral-50 px-4 py-8 text-center transition-colors hover:bg-neutral-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
              >
                <Camera className="h-8 w-8 text-neutral-600" aria-hidden />
                <span className="text-sm font-semibold text-ink">{t("rooms_take_photo")}</span>
              </button>
            )}
            <Button variant="ghost" size="sm" className="h-auto min-h-8 w-full whitespace-normal py-1.5" onClick={() => library.current?.click()}>
              <ImagePlus /> {t("rooms_choose_photo")}
            </Button>
            <p className="text-xs text-neutral-600">{t("rooms_photo_hint")}</p>
          </div>
        )}

        {phase === "busy" && (
          <p className="flex items-center gap-2 py-6 text-sm text-neutral-700" role="status">
            <Loader2 className="h-4 w-4 shrink-0 animate-spin" /> {t("rooms_drawing_busy")}
          </p>
        )}

        {proposal && (phase === "review" || phase === "done") && (
          <div className="space-y-3">
            {proposal.refusal ? (
              <Alert kind="info">{proposal.message}</Alert>
            ) : (
              drawn && (
                <>
                  <div className="mx-auto w-full max-w-md">
                    <RoomDrawing room={drawn} />
                  </div>
                  <Stats room={drawn} />
                  {proposal.notes && <p className="break-words text-sm text-neutral-700">{t("rooms_note", { text: proposal.notes })}</p>}
                </>
              )
            )}
            {proposal.rejected.length > 0 && <p className="text-xs text-neutral-600">{t("rooms_skipped", { n: proposal.rejected.length })}</p>}
          </div>
        )}

        {phase === "done" && applied && (
          <Alert kind="ok">{undone ? t("rooms_room_undone") : t("rooms_created")}</Alert>
        )}

        {error && <Alert kind="error">{error}</Alert>}
      </SheetBody>

      <SheetFooter className="flex flex-wrap items-center justify-end gap-2">
        {phase === "pick" && (
          <Button className="w-full sm:w-auto" disabled={!file} onClick={draw}>
            <Sparkles /> {t("rooms_draw")}
          </Button>
        )}
        {phase === "review" && proposal && (
          <>
            <Button variant="secondary" disabled={busy} onClick={discard}>
              {t("ai_discard")}
            </Button>
            {!proposal.refusal && drawn && (
              <Button disabled={busy} onClick={apply}>
                {busy ? <Loader2 className="animate-spin" /> : <Check />} {t("rooms_create")}
              </Button>
            )}
          </>
        )}
        {phase === "done" && (
          <>
            {!undone && (
              <Button variant="secondary" disabled={busy} onClick={undo}>
                {busy ? <Loader2 className="animate-spin" /> : <Undo2 />} {t("ai_undo")}
              </Button>
            )}
            <Button disabled={busy} onClick={onClose}>
              {t("rooms_done")}
            </Button>
          </>
        )}
      </SheetFooter>
    </>
  );
}

// ── Ask AI about one room ────────────────────────────────────────────────

export function AskRoomSheet({
  open,
  onOpenChange,
  venue,
  room,
  onChanged,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  venue: string;
  room: RoomDto | null;
  onChanged: () => void;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="md:max-w-3xl">{open && room && <AskRoom venue={venue} room={room} onChanged={onChanged} />}</SheetContent>
    </Sheet>
  );
}

type AskPhase = "ask" | "busy" | "review" | "done";

function AskRoom({ venue, room, onChanged }: { venue: string; room: RoomDto; onChanged: () => void }) {
  const t = useT();
  const { locale } = useI18n();
  const [phase, setPhase] = useState<AskPhase>("ask");
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [proposal, setProposal] = useState<FloorEditProposal | null>(null);
  // the room as it was when asked: the "before" drawing stays put while the page refreshes
  const [before, setBefore] = useState<RoomDto>(room);
  const [confirm, setConfirm] = useState(false);
  const [applied, setApplied] = useState<RoomAiApplyResult | null>(null);
  const [undone, setUndone] = useState<RoomAiRevertResult | null>(null);
  const [busy, setBusy] = useState(false);
  const rec = useVoice((blob) => sendVoice(blob), (k) => setError(t(k)));

  const extra = `&room=${encodeURIComponent(room.id)}`;

  const show = (p: FloorEditProposal) => {
    setBefore(room);
    setProposal(p);
    setPhase("review");
  };

  const ask = async () => {
    if (!text.trim()) return;
    setError(null);
    setPhase("busy");
    try {
      show(await post<FloorEditProposal>(withVenue("/v1/room-ai/chat", venue, extra), { text: text.trim(), lang: locale }));
    } catch (e) {
      setError(errorText(t, e));
      setPhase("ask");
    }
  };

  async function sendVoice(blob: Blob) {
    setError(null);
    setPhase("busy");
    try {
      const wav = await toWav(blob);
      const form = new FormData();
      form.append("lang", locale);
      form.append("audio", new Blob([wav as BlobPart], { type: "audio/wav" }), "request.wav");
      show(await postForm<FloorEditProposal>(withVenue("/v1/room-ai/chat/voice", venue, extra), form));
    } catch (e) {
      setError(e instanceof ApiError ? errorText(t, e) : t("ai_err_audio"));
      setPhase("ask");
    }
  }

  const apply = async (confirmed: boolean) => {
    if (!proposal) return;
    setBusy(true);
    setError(null);
    try {
      const r = await post<RoomAiApplyResult>(withVenue("/v1/room-ai/apply", venue), { proposalId: proposal.proposalId, confirmed });
      setConfirm(false);
      setApplied(r);
      setPhase("done");
      onChanged();
    } catch (e) {
      if (e instanceof ApiError && e.code === "menu_ai_confirm_required") setConfirm(true);
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
      setUndone(await post<RoomAiRevertResult>(withVenue(`/v1/room-ai/revert/${encodeURIComponent(applied.applyId)}`, venue), {}));
      onChanged();
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

  const usable = proposal && !proposal.refusal && proposal.changes.length > 0;
  const name = roomName(locale, room);

  return (
    <>
      <SheetHeader>
        <div className="min-w-0">
          <SheetTitle className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 shrink-0 text-copper-text" /> {t("rooms_ask_title")}
          </SheetTitle>
          <p className="mt-0.5 break-words text-xs text-neutral-600">{t("rooms_ask_sub", { room: name })}</p>
        </div>
      </SheetHeader>
      <SheetBody className="space-y-4">
        {phase === "ask" && (
          <AskForm
            text={text}
            setText={setText}
            recording={rec.recording}
            seconds={rec.seconds}
            onSend={ask}
            onMic={rec.recording ? rec.stop : rec.start}
          />
        )}

        {phase === "busy" && (
          <p className="flex items-center gap-2 py-6 text-sm text-neutral-700" role="status">
            <Loader2 className="h-4 w-4 shrink-0 animate-spin" /> {t("rooms_thinking")}
          </p>
        )}

        {proposal && (phase === "review" || phase === "done") && (
          <div className="space-y-3">
            {proposal.transcript && <p className="break-words text-sm italic text-neutral-700">{t("ai_heard", { text: proposal.transcript })}</p>}
            {proposal.refusal || !usable ? (
              proposal.message && <Alert kind="info">{proposal.message}</Alert>
            ) : (
              <>
                {proposal.summary && <p className="break-words text-sm font-medium text-ink">{proposal.summary}</p>}
                <BeforeAfter room={before} proposal={proposal} />
                <div className="space-y-2">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-neutral-600">{t("ai_changes")}</h3>
                  {proposal.changes.map((c) => (
                    <ChangeRow key={c.id} change={c} />
                  ))}
                </div>
              </>
            )}
            {proposal.rejected.length > 0 && (
              <ul className="space-y-0.5 text-xs text-neutral-600">
                {proposal.rejected.map((r, i) => (
                  <li key={i} className="break-words">
                    · {r}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {phase === "done" && applied && (
          <Alert kind="ok">
            {undone ? (undone.skipped > 0 ? t("ai_undo_partial", { n: undone.skipped }) : t("rooms_undone")) : t("rooms_applied", { n: applied.applied })}
          </Alert>
        )}

        {error && <Alert kind="error">{error}</Alert>}
      </SheetBody>

      {(phase === "review" || phase === "done") && (
        <SheetFooter className="flex flex-wrap items-center justify-end gap-2">
          {phase === "review" && usable ? (
            <>
              <Button variant="secondary" disabled={busy} onClick={again}>
                {t("ai_discard")}
              </Button>
              <Button disabled={busy} onClick={() => apply(false)}>
                {busy ? <Loader2 className="animate-spin" /> : <Check />} {t("rooms_apply_all")}
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

      <Dialog open={confirm} onOpenChange={(o) => !o && setConfirm(false)}>
        <DialogContent>
          <DialogTitle>{t("ai_bulk_title", { n: proposal?.changes.length ?? 0 })}</DialogTitle>
          <DialogDescription asChild>
            <div className="space-y-1 text-sm text-neutral-700">
              <p>{t("rooms_bulk_removals")}</p>
              <p className="text-neutral-600">{t("ai_bulk_undo_hint")}</p>
            </div>
          </DialogDescription>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirm(false)}>
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

function BeforeAfter({ room, proposal }: { room: RoomDto; proposal: FloorEditProposal }) {
  const t = useT();
  const after = ghostRoom(room, proposal);
  const h = highlights(room, proposal);
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <figure className="min-w-0 space-y-1">
          <figcaption className="text-xs font-semibold uppercase tracking-wide text-neutral-600">{t("rooms_before")}</figcaption>
          <RoomDrawing room={room} highlights={h} showRemoved />
        </figure>
        <figure className="min-w-0 space-y-1">
          <figcaption className="text-xs font-semibold uppercase tracking-wide text-neutral-600">{t("rooms_after")}</figcaption>
          <RoomDrawing room={after} highlights={h} />
        </figure>
      </div>
      <DrawingLegend />
    </div>
  );
}

function AskForm({
  text,
  setText,
  recording,
  seconds,
  onSend,
  onMic,
}: {
  text: string;
  setText: (s: string) => void;
  recording: boolean;
  seconds: number;
  onSend: () => void;
  onMic: () => void;
}) {
  const t = useT();
  const examples: MsgKey[] = ["rooms_example_1", "rooms_example_2", "rooms_example_3"];
  return (
    <div className="space-y-3">
      <textarea
        value={text}
        rows={3}
        maxLength={1000}
        disabled={recording}
        placeholder={t("rooms_ask_placeholder")}
        aria-label={t("rooms_ask_title")}
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
        <Button variant={recording ? "destructive" : "secondary"} onClick={onMic} aria-pressed={recording} className="min-w-0">
          {recording ? <Square /> : <Mic />}
          {recording ? t("ai_mic_stop") : t("ai_mic")}
        </Button>
        {recording && (
          <span className="flex items-center gap-1.5 text-xs tabular-nums text-neutral-700" role="status">
            <span className="h-2 w-2 animate-pulse rounded-full bg-red-600" />
            {t("ai_recording", { s: Math.max(0, MAX_RECORD_SECONDS - Math.floor(seconds)) })} · {clock(seconds)}
          </span>
        )}
        <Button className="ml-auto" disabled={recording || !text.trim()} onClick={onSend}>
          <Send /> {t("ai_send")}
        </Button>
      </div>
      {!recording && (
        <div className="flex flex-wrap gap-1.5">
          {examples.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => setText(t(k))}
              className="rounded-full border border-neutral-200 px-3 py-1 text-xs text-neutral-700 transition-colors hover:bg-neutral-50"
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

const KIND: Record<string, { key: MsgKey; variant: "success" | "destructive" | "default" | "copper" }> = {
  add_table: { key: "rooms_kind_add_table", variant: "success" },
  update_table: { key: "rooms_kind_update_table", variant: "default" },
  remove_table: { key: "rooms_kind_remove_table", variant: "destructive" },
  add_object: { key: "rooms_kind_add_object", variant: "success" },
  update_object: { key: "rooms_kind_update_object", variant: "copper" },
  remove_object: { key: "rooms_kind_remove_object", variant: "destructive" },
};

const FIELD: Record<string, MsgKey> = {
  number: "rooms_field_number",
  shape: "rooms_field_shape",
  seats: "rooms_field_seats",
  position: "rooms_field_position",
  size: "rooms_field_size",
  rotation: "rooms_field_rotation",
};

const SHAPE: Record<string, MsgKey> = {
  ROUND: "rooms_shape_round",
  SQUARE: "rooms_shape_square",
  RECT: "rooms_shape_rect",
  BAR: "rooms_shape_bar",
};

function ChangeRow({ change }: { change: FloorChange }) {
  const t = useT();
  const kind = KIND[change.kind];
  return (
    <div className="rounded-lg border border-neutral-200 bg-surface px-3 py-2.5">
      <span className="flex flex-wrap items-center gap-1.5">
        {kind && <Badge variant={kind.variant}>{t(kind.key)}</Badge>}
        {change.title && <span className="break-words text-sm font-medium text-ink">{change.title}</span>}
      </span>
      {change.details.length > 0 && (
        <span className="mt-1 block space-y-0.5">
          {change.details.map((d, i) => (
            <DetailLine key={i} d={d} />
          ))}
        </span>
      )}
    </div>
  );
}

function DetailLine({ d }: { d: FloorChangeDetail }) {
  const t = useT();
  const value = (v: string | null | undefined) => {
    if (v == null) return v;
    if (d.field === "shape" && SHAPE[v]) return t(SHAPE[v]);
    if (d.field === "rotation") return `${v}°`;
    return v;
  };
  const before = value(d.before);
  const after = value(d.after);
  const label = FIELD[d.field] ? t(FIELD[d.field]) : d.field;
  return (
    <span className="flex flex-wrap items-baseline gap-x-1.5 text-xs text-neutral-700">
      <span className="text-neutral-600">{label}:</span>
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
