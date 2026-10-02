// Pure helpers for the Menu page's AI assistant (components/menu-ai.tsx):
// the voice clip's conversion to what the cloud and Gemini take (16 kHz mono
// 16-bit WAV — browsers record webm/opus or mp4/aac), the change checklist's
// ticking rules, and the error codes' message keys. No React, no fetch:
// lib/menu-ai.test.ts covers them.
import type { AiChange } from "./types";
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

/** "0:07" for a seconds count. */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
