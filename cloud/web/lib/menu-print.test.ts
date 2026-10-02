// The Menu page's "Print menus" helpers. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  aiNoteKey,
  base64Bytes,
  brandPayload,
  defaultLang,
  logoFile,
  pdfFileName,
  printErrorKey,
  printRequest,
} from "./menu-print";
import { parseBrand } from "./brand/brand";
import { messages } from "./i18n/messages";

const brand = parseBrand({
  id: "testpack",
  name: "Test Pack",
  assets: { mark: "m.png", markLarge: "ml.png", icon: "i.png" },
  palette: Object.fromEntries(
    [
      "primary", "primaryDeep", "onPrimaryMuted", "accent", "accentText", "accentSoft", "action", "actionHover",
      "background", "surface", "surfaceAlt", "border", "text", "textMuted", "destructive", "destructiveDeep",
      "attention", "success", "successDeep", "selection",
    ].map((k, i) => [k, `#${(0x101010 + i).toString(16).padStart(6, "0")}`])
  ),
  neutral: Object.fromEntries(["50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "950"].map((k) => [k, "#333333"])),
  series: ["#111111", "#222222"],
  chart: { grid: "#eeeeee", axis: "#666666" },
  font: "barlow",
});

const base = {
  type: "full" as const,
  lang: "de" as const,
  paper: "letter" as const,
  photos: false,
  fillPhotos: true,
  savePhotos: true,
  notes: "  fall theme  ",
  style: "auto" as const,
  brand: brandPayload(brand),
};

test("photo filling needs photos on, and saving needs filling", () => {
  const off = printRequest(base);
  assert.equal(off.fillPhotos, false);
  assert.equal(off.savePhotos, false);
  const on = printRequest({ ...base, photos: true });
  assert.equal(on.fillPhotos, true);
  assert.equal(on.savePhotos, true);
  assert.equal(printRequest({ ...base, photos: true, fillPhotos: false }).savePhotos, false);
});

test("notes are trimmed, capped, and left out when blank", () => {
  assert.equal(printRequest(base).notes, "fall theme");
  assert.equal(printRequest({ ...base, notes: "x".repeat(400) }).notes?.length, 300);
  assert.equal("notes" in printRequest({ ...base, notes: "   " }), false);
});

test("the printed page wears the brand pack: its colours, font and logo", () => {
  const p = brandPayload(brand, "data:image/png;base64,AAAA");
  assert.equal(p.name, "Test Pack");
  assert.equal(p.primary, brand.palette.primary);
  assert.equal(p.accent, brand.palette.accentText);
  assert.equal(p.muted, brand.neutral["700"]);
  assert.equal(p.font, "barlow");
  assert.equal(p.logo, "data:image/png;base64,AAAA");
  assert.equal("logo" in brandPayload(brand), false);
  assert.equal(logoFile(brand), "ml.png");
  assert.equal(logoFile({ ...brand, assets: { ...brand.assets, logo: "logo.png" } }), "logo.png");
});

test("the menu language starts as the UI's", () => {
  assert.equal(defaultLang("fr"), "fr");
  assert.equal(defaultLang("af"), "af");
  assert.equal(defaultLang("xx"), "en");
});

test("notes and errors map to real message keys", () => {
  assert.equal(aiNoteKey("used", null), null);
  assert.equal(aiNoteKey("off", "not_setup"), "print_note_no_ai_setup");
  assert.equal(aiNoteKey("fallback", "timeout"), "print_note_no_ai");
  for (const code of ["menu_print_too_many", "menu_print_expired", "menu_print_empty", "menu_print_busy", "network", "venue_required"]) {
    const k = printErrorKey(code);
    assert.ok(k && k in messages, code);
  }
  assert.equal(printErrorKey("something_else"), null);
});

test("PDF bytes and file names", () => {
  assert.deepEqual([...base64Bytes(Buffer.from("%PDF-1.7").toString("base64"))], [...Buffer.from("%PDF-1.7")]);
  assert.equal(pdfFileName("riverside-full-2026-10-06.pdf"), "riverside-full-2026-10-06.pdf");
  assert.equal(pdfFileName("../../etc/passwd"), "menu.pdf");
  assert.equal(pdfFileName(undefined), "menu.pdf");
});
