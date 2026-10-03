// Pure helpers for the Menu page's AI assistant (components/menu-ai.tsx):
// the voice clip's conversion to what the cloud and Gemini take (16 kHz mono
// 16-bit WAV — browsers record webm/opus or mp4/aac), the change checklist's
// ticking rules, and the error codes' message keys. No React, no fetch:
// lib/menu-ai.test.ts covers them.
import type { AiChange, AiSalesBasis, AiSalesNote } from "./types";
import type { MsgKey } from "./i18n/messages";

/** The clip the cloud gets: 16 kHz is plenty for speech and keeps 30 s under 1 MB. */
export const VOICE_RATE = 16_000;
/** The longest recording, in seconds (the store's tablets use the same). */
export const MAX_RECORD_SECONDS = 30;

/** Average the channels and resample to [rate] (linear interpolation). */
export function toMono(channels: Float32Array[], fromRate: number, rate = VOICE_RATE): Float32Array {
  if (channels.length === 0 || channels[0].length === 0) return new Float32Array(0);
  const n = channels[0].length;
  const mono = new Float32Array(n);
  for (const ch of channels) for (let i = 0; i < n; i++) mono[i] += ch[i] / channels.length;
  if (fromRate === rate) return mono;
  const outLen = Math.max(1, Math.floor((n * rate) / fromRate));
  const out = new Float32Array(outLen);
  const step = fromRate / rate;
  for (let i = 0; i < outLen; i++) {
    const pos = i * step;
    const i0 = Math.floor(pos);
    const i1 = Math.min(i0 + 1, n - 1);
    const f = pos - i0;
    out[i] = mono[i0] * (1 - f) + mono[i1] * f;
  }
  return out;
}

/** 16-bit PCM mono WAV bytes of [samples] (-1..1). */
export function encodeWav(samples: Float32Array, rate = VOICE_RATE): Uint8Array {
  const data = samples.length * 2;
  const buf = new ArrayBuffer(44 + data);
  const v = new DataView(buf);
  const ascii = (at: number, s: string) => [...s].forEach((c, i) => v.setUint8(at + i, c.charCodeAt(0)));
  ascii(0, "RIFF");
  v.setUint32(4, 36 + data, true);
  ascii(8, "WAVE");
  ascii(12, "fmt ");
  v.setUint32(16, 16, true); // fmt chunk size
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, rate, true);
  v.setUint32(28, rate * 2, true); // byte rate
  v.setUint16(32, 2, true); // block align
  v.setUint16(34, 16, true); // bits per sample
  ascii(36, "data");
  v.setUint32(40, data, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buf);
}

/** Every change ticked, as the store's tablet starts. */
export function allTicked(changes: AiChange[]): Set<string> {
  return new Set(changes.map((c) => c.id));
}

/**
 * Tick or untick [id]. Ticking a change ticks the new category it needs;
 * unticking a new category unticks what needs it (it could not be applied).
 */
export function toggleChange(ticked: Set<string>, changes: AiChange[], id: string): Set<string> {
  const next = new Set(ticked);
  if (next.has(id)) {
    next.delete(id);
    for (const c of changes) if (c.needs === id) next.delete(c.id);
  } else {
    next.add(id);
    const need = changes.find((c) => c.id === id)?.needs;
    if (need) next.add(need);
  }
  return next;
}

/** The ticked ids in the proposal's own order. */
export function tickedIds(ticked: Set<string>, changes: AiChange[]): string[] {
  return changes.filter((c) => ticked.has(c.id)).map((c) => c.id);
}

/** An API error code → the message to show (null: the caller's generic one). */
export function aiErrorKey(code: string): MsgKey | null {
  switch (code) {
    case "menu_ai_too_many":
    case "rate_limited":
      return "ai_err_too_many";
    case "menu_ai_daily_limit":
      return "ai_err_daily";
    case "menu_ai_disabled":
      return "ai_not_setup";
    case "menu_ai_expired":
    case "menu_ai_already_applied":
      return "ai_err_expired";
    case "menu_ai_already_reverted":
      return "ai_undone";
    case "menu_ai_audio_type":
    case "menu_ai_no_audio":
    case "menu_ai_audio_too_large":
      return "ai_err_audio";
    case "menu_ai_timeout":
    case "menu_ai_unavailable":
    case "menu_ai_quota":
    case "menu_ai_auth":
    case "menu_ai_error":
    case "network":
      return "ai_err_busy";
    default:
      return null;
  }
}

// --- sales-based answers and proposals (the server computes every number) ---

/** "Top 3", not "Top 5", when only 3 qualified (a note says so). */
function shownN(b: AiSalesBasis): number {
  return b.rows.length > 0 ? Math.min(b.n, b.rows.length) : b.n;
}

/** The headline of a sales basis ("Top 5 by units sold"): its message key and placeholders. */
export function salesHeadline(b: AiSalesBasis): { key: MsgKey; vars: Record<string, string | number> } {
  switch (b.rank) {
    case "top":
      return { key: b.by === "revenue" ? "ai_sales_top_revenue" : "ai_sales_top_units", vars: { n: shownN(b) } };
    case "bottom":
      return { key: b.by === "revenue" ? "ai_sales_bottom_revenue" : "ai_sales_bottom_units", vars: { n: shownN(b) } };
    case "unsold":
      return { key: "ai_sales_unsold", vars: { days: b.days ?? 0 } };
    case "specials":
      return { key: "ai_sales_specials", vars: {} };
    case "happy_hour":
      return { key: "ai_sales_happy_hour", vars: { n: shownN(b) } };
    default:
      return { key: "ai_sales_list", vars: {} };
  }
}

/** A sales note → its message key (null: not one the portal knows, not shown). */
export function salesNoteKey(n: AiSalesNote): MsgKey | null {
  switch (n.code) {
    case "no_sales":
      return "ai_sales_note_no_sales";
    case "fewer_items":
      return "ai_sales_note_fewer";
    case "pick_corrected":
      return "ai_sales_note_corrected";
    case "size_refused":
      return n.size ? "ai_sales_note_size_refused" : "ai_sales_note_item_refused";
    case "already_lower":
      return n.size ? "ai_sales_note_size_already_lower" : "ai_sales_note_already_lower";
    case "item_skipped":
      return "ai_sales_note_item_skipped";
    case "too_many":
      return "ai_sales_note_too_many";
    case "none_match":
      return "ai_sales_note_none_match";
    case "bad_request":
      return "ai_sales_note_bad";
    case "no_specials":
      return "ai_sales_note_no_specials";
    case "no_happy_hour":
      return "ai_sales_note_no_happy_hour";
    case "all_working":
      return "ai_sales_note_all_working";
    case "assumes_whole_period":
      return "ai_sales_note_whole_period";
    default:
      return null;
  }
}

/**
 * The basis line's items: "Lantern House Lager (412), Copper Burger (388), …"
 * — at most [max] names, each with its units (or revenue, for a revenue
 * ranking), formatted by the caller.
 */
export function basisItems(b: AiSalesBasis, units: (n: number) => string, money: (minor: number) => string, max = 5): string {
  const shown = b.rows.slice(0, max).map((r) => {
    if (b.rank === "unsold") return r.name;
    if (b.rank === "specials") return `${r.name} (${r.special ?? ""}): ${r.liftPct != null ? signedPct(r.liftPct) : "–"}`;
    return `${r.name} (${b.by === "revenue" ? money(r.revenueMinor) : units(r.units)})`;
  });
  return shown.join(", ") + (b.rows.length > max ? ", …" : "");
}

/** A lift: "+140%", "−5%", "0%" (a real minus sign). */
export function signedPct(n: number): string {
  return n > 0 ? `+${n}%` : n < 0 ? `−${Math.abs(n)}%` : "0%";
}

/** The message comparing a special's days with the other days (by what the other days are). */
export function liftKey(baseline: string | null | undefined): MsgKey {
  return baseline === "weekdays" ? "ai_sales_lift_weekdays" : baseline === "weekend" ? "ai_sales_lift_weekend" : "ai_sales_lift_other";
}

/** "0:07" for a seconds count. */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
