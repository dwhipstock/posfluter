// Pure helpers for AI item photos (components/ai-photo.tsx): the per-picture
// state of a batch ("photos for every drink"), what to make next, and the
// error codes' message keys. No React, no fetch: lib/ai-photo.test.ts.
import type { AiPhotoAsk, AiPhotoPreview } from "./types";
import type { MsgKey } from "./i18n/messages";

export type PhotoStatus = "waiting" | "making" | "ready" | "accepted" | "discarded" | "failed" | "undone";

export interface PhotoState {
  ask: AiPhotoAsk;
  status: PhotoStatus;
  preview?: AiPhotoPreview;
  error?: string;
}

/** The most pictures the portal makes from one request (the cloud caps the request at the same). */
export const MAX_BATCH = 10;

export function initialStates(asks: AiPhotoAsk[]): PhotoState[] {
  return asks.slice(0, MAX_BATCH).map((ask) => ({ ask, status: "waiting" }));
}

/** One picture makes itself at once; several wait for the manager's go-ahead. */
export function needsBatchConfirm(asks: AiPhotoAsk[]): boolean {
  return asks.length > 1;
}

/** The next picture to make (one at a time, in order), or -1. */
export function nextWaiting(states: PhotoState[]): number {
  if (states.some((s) => s.status === "making")) return -1;
  return states.findIndex((s) => s.status === "waiting");
}

/** The pictures ready to use (shown, not yet accepted or discarded). */
export function readyIndexes(states: PhotoState[]): number[] {
  return states.flatMap((s, i) => (s.status === "ready" ? [i] : []));
}

/** "2 of 5" while a batch is being made: the one being made now, counting the finished ones. */
export function progress(states: PhotoState[]): { done: number; total: number } {
  const total = states.length;
  const done = states.filter((s) => s.status !== "waiting" && s.status !== "making").length;
  return { done, total };
}

export function update(states: PhotoState[], i: number, patch: Partial<PhotoState>): PhotoState[] {
  return states.map((s, j) => (j === i ? { ...s, ...patch } : s));
}

/** A data: URL for the preview image (the cloud sends a checked JPEG or PNG). */
export function previewSrc(p: AiPhotoPreview): string {
  const type = p.contentType === "image/png" ? "image/png" : "image/jpeg";
  return `data:${type};base64,${p.dataBase64}`;
}

/** An API error code → the message to show (null: the assistant's own mapping or a generic one). */
export function photoErrorKey(code: string): MsgKey | null {
  switch (code) {
    case "menu_ai_photo_refused":
      return "ai_photo_err_refused";
    case "menu_ai_photo_name":
      return "ai_photo_err_name";
    case "menu_ai_photo_daily_limit":
      return "ai_photo_err_daily";
    case "menu_ai_photo_changed":
      return "ai_photo_err_changed";
    case "menu_ai_photo_expired":
      return "ai_photo_err_expired";
    case "menu_ai_photo_none":
      return "ai_photo_err_none";
    case "menu_ai_photos_disabled":
      return "ai_photo_off";
    case "menu_ai_photo_failed":
      return "ai_err_busy";
    default:
      return null;
  }
}
