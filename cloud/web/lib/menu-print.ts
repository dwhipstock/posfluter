// Pure helpers for the Menu page's "Print menus" (components/menu-print.tsx):
// the request the cloud gets, the brand bits a printed page wears, the error
// codes' message keys, and the PDF bytes. No React, no fetch:
// lib/menu-print.test.ts covers them.
import type { Brand } from "./brand/brand";
import type { MsgKey } from "./i18n/messages";

export const PRINT_TYPES = ["full", "today", "drinks", "highlights", "flyer"] as const;
export type PrintType = (typeof PRINT_TYPES)[number];

export const PRINT_STYLES = ["classic", "modern", "chalkboard", "autumn", "summer"] as const;
export type PrintStyle = (typeof PRINT_STYLES)[number];

export const PRINT_LANGS = ["en", "fr", "es", "de", "af"] as const;
export type PrintLang = (typeof PRINT_LANGS)[number];

/** Each language named in itself (the same in every UI language). */
export const LANG_NAMES: Record<PrintLang, string> = {
  en: "English",
  fr: "Français",
  es: "Español",
  de: "Deutsch",
  af: "Afrikaans",
};

export interface PrintStatus {
  canUse: boolean;
  /** AI wording is set up here. */
  ai: boolean;
  /** AI artwork is set up here (else each style's own decorations). */
  art: boolean;
  styles: string[];
}

export interface PrintBrandPayload {
  name: string;
  primary: string;
  accent: string;
  text: string;
  muted: string;
  font: string;
  logo?: string;
}

export interface PrintRequest {
  type: PrintType;
  lang: PrintLang;
  paper: "letter" | "a4";
  photos: boolean;
  fillPhotos: boolean;
  savePhotos: boolean;
  notes?: string;
  style: PrintStyle | "auto";
  brand: PrintBrandPayload;
}

export interface PrintPlan {
  jobId: string;
  style: PrintStyle;
  styleBy: "manager" | "ai" | "notes" | "default";
  /** used | off | fallback */
  ai: string;
  aiReason?: string | null;
  notesIgnored: boolean;
  items: number;
  artToMake: number;
  artAvailable: boolean;
  elapsedMs: number;
}

export interface PrintArt {
  jobId: string;
  made: number;
  reused: number;
  builtIn: number;
  photos: number;
  photosSaved: number;
  elapsedMs: number;
}

export interface PrintResult {
  pdf: string;
  fileName: string;
  pages: number;
  previews: string[];
  ai: string;
  aiReason?: string | null;
  notesIgnored: boolean;
  items: number;
  elapsedMs: number;
}

export const NOTES_MAX = 300;

/** The form's choices → the cloud's request. Photo filling needs photos on; saving needs filling. */
export function printRequest(f: {
  type: PrintType;
  lang: PrintLang;
  paper: "letter" | "a4";
  photos: boolean;
  fillPhotos: boolean;
  savePhotos: boolean;
  notes: string;
  style: PrintStyle | "auto";
  brand: PrintBrandPayload;
}): PrintRequest {
  const fill = f.photos && f.fillPhotos;
  const notes = f.notes.trim().slice(0, NOTES_MAX);
  return {
    type: f.type,
    lang: f.lang,
    paper: f.paper,
    photos: f.photos,
    fillPhotos: fill,
    savePhotos: fill && f.savePhotos,
    ...(notes ? { notes } : {}),
    style: f.style,
    brand: f.brand,
  };
}

/** The brand pack's colours and font for the printed page (the cloud checks them and keeps text readable). */
export function brandPayload(b: Brand, logo?: string): PrintBrandPayload {
  return {
    name: b.name,
    primary: b.palette.primary,
    accent: b.palette.accentText,
    text: b.palette.text,
    // secondary text stays a dark neutral on paper
    muted: b.neutral["700"],
    font: b.font,
    ...(logo ? { logo } : {}),
  };
}

/** The brand pack's logo file for print: the full logo when it has one, else the large mark. */
export function logoFile(b: Brand): string {
  return b.assets.logo ?? b.assets.markLarge;
}

/** The language the menu starts in: the UI's, when it is one a menu can be printed in. */
export function defaultLang(locale: string): PrintLang {
  return (PRINT_LANGS as readonly string[]).includes(locale) ? (locale as PrintLang) : "en";
}

/** Why there is no AI wording → its quiet note (null: the AI wrote it). */
export function aiNoteKey(ai: string, reason: string | null | undefined): MsgKey | null {
  if (ai === "used") return null;
  if (ai === "off" || reason === "not_setup") return "print_note_no_ai_setup";
  return "print_note_no_ai";
}

/** The cloud's error codes → their message keys (null: show the cloud's own words). */
export function printErrorKey(code: string): MsgKey | null {
  switch (code) {
    case "menu_print_too_many":
    case "menu_print_art_too_many":
    case "menu_print_busy":
    case "rate_limited":
      return "print_err_busy";
    case "menu_print_expired":
      return "print_err_expired";
    case "menu_print_empty":
      return "print_err_empty";
    case "venue_required":
      return "print_pick_store";
    case "network":
      return "print_err_network";
    default:
      return null;
  }
}

/** base64 → bytes (the PDF the cloud sent). */
export function base64Bytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** A safe download name ("riverside-full-2026-10-06.pdf"). */
export function pdfFileName(name: string | undefined): string {
  const n = (name ?? "").toLowerCase().replace(/[^a-z0-9.-]+/g, "-").replace(/^-+|-+$/g, "");
  return n.endsWith(".pdf") && n.length > 4 ? n : "menu.pdf";
}
