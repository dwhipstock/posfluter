// What the PDF export can draw. pdfmake ships one Latin/Greek/Cyrillic font
// (Roboto) and has no per-glyph fallback, so an emoji, Hebrew, Arabic or CJK
// name would print as blank space. Bundling fallbacks is not worth it here:
// Noto CJK alone is ~16 MB, pdfmake does no Arabic/Hebrew shaping or
// right-to-left ordering (the letters would print disconnected and
// backwards), and it cannot draw colour emoji at all. So such characters are
// swapped for "�" (one per run) and the PDF says so; the CSV / Excel exports
// keep every character.

import { PDF_FONT_RANGES } from "./pdf-coverage";

export const PDF_PLACEHOLDER = "�";

function drawable(cp: number): boolean {
  let lo = 0;
  let hi = PDF_FONT_RANGES.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const [a, b] = PDF_FONT_RANGES[mid];
    if (cp < a) hi = mid - 1;
    else if (cp > b) lo = mid + 1;
    else return true;
  }
  return false;
}

/** User-perceived characters; falls back to code points where Intl.Segmenter is missing. */
function graphemes(s: string): string[] {
  const Seg = (Intl as unknown as { Segmenter?: new (l?: string, o?: { granularity: string }) => { segment(s: string): Iterable<{ segment: string }> } }).Segmenter;
  if (Seg) return Array.from(new Seg(undefined, { granularity: "grapheme" }).segment(s), (x) => x.segment);
  return Array.from(s);
}

const isMark = (ch: string) => /\p{M}/u.test(ch);
/** Zero-width joiners and variation selectors: emoji plumbing, nothing to draw. */
const isInvisible = (ch: string) => /[‌‍︀-️]/u.test(ch);

/**
 * [s] made printable by the bundled font: composed (NFC) so "e" + accent
 * becomes "é", accents the font lacks are dropped (zalgo), control characters
 * other than a line break become spaces, and each run of characters it cannot
 * draw becomes one "�". [replaced] says whether anything visible was lost.
 */
export function pdfText(s: string): { text: string; replaced: boolean } {
  let out = "";
  let replaced = false;
  let inRun = false;
  for (const g of graphemes(s.normalize("NFC"))) {
    if (g === "\n") {
      out += g;
      inRun = false;
      continue;
    }
    const cps = Array.from(g);
    const base = cps[0];
    const baseCp = base.codePointAt(0)!;
    if (baseCp < 0x20 || (baseCp >= 0x7f && baseCp < 0xa0)) {
      out += " ";
      inRun = false;
      continue;
    }
    if (drawable(baseCp) && !isMark(base)) {
      // keep the letter; keep only the accents the font has
      out += cps.filter((c, i) => i === 0 || (drawable(c.codePointAt(0)!) && !isInvisible(c))).join("");
      inRun = false;
      continue;
    }
    if (isMark(base) || isInvisible(base)) continue; // a stray accent or joiner: nothing to show
    replaced = true;
    if (!inRun) out += PDF_PLACEHOLDER;
    inRun = true;
  }
  return { text: out, replaced };
}

/** Every font-drawn string in a pdfmake content tree, run through [pdfText] in place. */
export function sanitizeContent<T>(node: T, onReplace: () => void): T {
  const walk = (n: unknown): unknown => {
    if (typeof n === "string") {
      const r = pdfText(n);
      if (r.replaced) onReplace();
      return r.text;
    }
    if (Array.isArray(n)) return n.map(walk);
    if (n && typeof n === "object") {
      const o = n as Record<string, unknown>;
      const copy: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(o)) {
        // a string is drawn text only under `text`; style names, colours,
        // widths and layouts are copied as-is (nested nodes are walked)
        copy[k] = k === "text" || (v !== null && typeof v === "object") ? walk(v) : v;
      }
      return copy;
    }
    return n;
  };
  return walk(node) as T;
}
