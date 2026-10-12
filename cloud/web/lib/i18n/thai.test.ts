// Thai locale: dates in the Buddhist Era, the data-language slot (the catalog's
// `*_fr` columns hold Thai at a Thai + English client, French everywhere
// else), field labels, printed-menu languages and Thai in the PDF export.
// Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import React from "react";
import { renderToString } from "react-dom/server";
import { messages, type Locale, type MsgKey } from "./messages";
import { th } from "./messages.th";
import { dataLocaleOf, pickName, translate } from "./translate";
import { makeFmt } from "./format";
import { parseBrand, SUPPORTED_LOCALES } from "../brand/brand";
import { extraLangs, slotLabel } from "../menu-edit";
import { printLangsFor } from "../menu-print";
import { count, decimal, money, moneyCents } from "../format";
import { roomName } from "../rooms";
import { buildDocDefinition } from "../export/pdf";
import { pdfText, THAI_PDF_FONT } from "../export/pdf-text";
import type { ExportDoc } from "../export/doc";
import { col } from "../export/doc";

const WEB = path.resolve(__dirname, "../..");
const pack = (id: string) => JSON.parse(fs.readFileSync(path.join(WEB, "brands", id, "brand.json"), "utf8"));
const thaiPack = () => ({ ...pack("sagepoppy"), id: "thai-demo", locales: { default: "th", available: ["th", "en"] } });

test("Thai is a supported portal locale, every key translated", () => {
  assert.ok((SUPPORTED_LOCALES as readonly string[]).includes("th"));
  const keys = Object.keys(messages) as MsgKey[];
  for (const k of keys) assert.equal(translate("th", k), th[k], k);
  // the language is named in itself
  assert.equal(translate("th", "menu_lang_th"), "ไทย");
  assert.equal(translate("en", "menu_lang_th"), "Thai");
});

test("Thai dates: Thai month abbreviations and the Buddhist Era year (CE + 543)", () => {
  const fmt = makeFmt("th", (k) => translate("th", k));
  assert.equal(fmt.dayYear("2026-01-08"), "8 ม.ค. 2569");
  assert.equal(fmt.dayYear("2026-10-08"), "8 ต.ค. 2569");
  assert.equal(fmt.day("2026-04-13"), "13 เม.ย.");
  assert.equal(fmt.dateTime("2026-12-31T20:14:00.000+07:00"), "31 ธ.ค. 20:14");
  assert.equal(fmt.rangeLabel({ from: "2026-01-08", to: "2026-01-08" } as never), "8 ม.ค. 2569");
  // other languages keep the CE year
  assert.equal(makeFmt("en", (k) => translate("en", k)).dayYear("2026-01-08"), "8 Jan 2026");
  assert.equal(makeFmt("fr", (k) => translate("fr", k)).dayYear("2026-01-08"), "8 janv. 2026");
});

test("Thai money, counts and decimals stay North American", () => {
  assert.equal(money(123456, "USD", { locale: "th" }), "$1,234.56");
  assert.equal(moneyCents(-500, "USD", { locale: "th" }), "-$5.00");
  assert.equal(count(1234567, "th"), "1,234,567");
  assert.equal(decimal(9.975, 3, "th"), "9.975");
});

test("data language: explicit, else the first slot language, else French", () => {
  assert.equal(dataLocaleOf(["en", "fr", "es", "de", "af"]), "fr");
  assert.equal(dataLocaleOf(["en", "es"]), "fr");
  assert.equal(dataLocaleOf(["th", "en"]), "th");
  assert.equal(dataLocaleOf(["en", "th"]), "th");
  assert.equal(dataLocaleOf([]), "fr");
  assert.equal(dataLocaleOf(null), "fr");
  assert.equal(dataLocaleOf(["th", "en"], "fr"), "fr");
  assert.equal(dataLocaleOf(["en"], "nope"), "fr");
});

test("brand packs: existing ones read French from the slot; a Thai pack reads Thai", () => {
  assert.equal(parseBrand(pack("copperlantern")).dataLocale, "fr");
  assert.equal(parseBrand(pack("sagepoppy")).dataLocale, "fr");
  assert.equal(parseBrand(pack("pronghorn")).dataLocale, "fr");
  const b = parseBrand(thaiPack());
  assert.deepEqual(b.locales, { default: "th", available: ["th", "en"] });
  assert.equal(b.dataLocale, "th");
  assert.equal(parseBrand({ ...thaiPack(), dataLocale: "fr" }).dataLocale, "fr");
  assert.throws(() => parseBrand({ ...thaiPack(), dataLocale: "de" }), /dataLocale/);
});

test("pickName: a French store is unchanged; at a Thai store the fr slot is Thai", () => {
  // French store (default data language)
  assert.equal(pickName("fr", "Bière", "Beer"), "Bière");
  assert.equal(pickName("en", "Bière", "Beer"), "Beer");
  assert.equal(pickName("es", "Bière", "Beer", { es: "Cerveza" }), "Cerveza");
  // Thai store: the slot holds Thai
  assert.equal(pickName("th", "เบียร์", "Beer", null, "th"), "เบียร์");
  assert.equal(pickName("en", "เบียร์", "Beer", null, "th"), "Beer");
  assert.equal(pickName("th", "", "Beer", null, "th"), "Beer", "a blank Thai name falls back to English");
  assert.equal(pickName("th", null, "Beer", { th: "ignored" }, "th"), "Beer");
  // a French reader at a Thai store reads its translation, never the Thai slot as French
  assert.equal(pickName("fr", "เบียร์", "Beer", { fr: "Bière" }, "th"), "Bière");
  assert.equal(pickName("fr", "เบียร์", "Beer", null, "th"), "Beer");
  // rooms follow the same rule
  const room = { nameFr: "ห้องหลัก", nameEn: "Main room", names: {} };
  assert.equal(roomName("th", room, "th"), "ห้องหลัก");
  assert.equal(roomName("en", room, "th"), "Main room");
  assert.equal(roomName("fr", { ...room, nameFr: "Salle" }), "Salle");
});

test("menu editor: the slot's field is labelled with the data language", () => {
  const en = (k: MsgKey, v?: Record<string, string | number>) => translate("en", k, v);
  const thT = (k: MsgKey, v?: Record<string, string | number>) => translate("th", k, v);
  const fr = (k: MsgKey, v?: Record<string, string | number>) => translate("fr", k, v);
  // French stores: exactly the old labels
  assert.equal(slotLabel(en, "name", "fr"), "Name (French)");
  assert.equal(slotLabel(en, "desc", "fr"), "Description (French)");
  assert.equal(slotLabel(en, "size", "fr"), "Size (French)");
  assert.equal(slotLabel(fr, "name", "fr"), translate("fr", "menu_name_fr"));
  // Thai stores
  assert.equal(slotLabel(en, "name", "th"), "Name (Thai)");
  assert.equal(slotLabel(en, "desc", "th"), "Description (Thai)");
  assert.equal(slotLabel(en, "size", "th"), "Size (Thai)");
  assert.equal(slotLabel(thT, "name", "th"), "ชื่อ (ไทย)");
  assert.equal(slotLabel(thT, "size", "th"), "ขนาด (ไทย)");
  // Thai is never offered as an extra translation field at a Thai store
  assert.deepEqual(extraLangs(["th", "en"]), []);
});

test("nameAlt: the second line is the other catalog language (French or Thai beside English)", async () => {
  (globalThis as unknown as { React: typeof React }).React = React;
  const { LocaleProvider, useI18n } = await import("./context");
  const alt = (locale: Locale, available: Locale[], fr: string, en: string) => {
    let out = "";
    function Probe() {
      out = useI18n().nameAlt(fr, en);
      return null;
    }
    renderToString(React.createElement(LocaleProvider, { initialLocale: locale, available, children: React.createElement(Probe) }));
    return out;
  };
  // French store: unchanged
  assert.equal(alt("en", ["en", "fr"], "Bière", "Beer"), "Bière");
  assert.equal(alt("fr", ["en", "fr"], "Bière", "Beer"), "Beer");
  assert.equal(alt("es", ["en", "fr", "es"], "Bière", "Beer"), "");
  assert.equal(alt("en", ["en", "es"], "Bière", "Beer"), "");
  // Thai store
  assert.equal(alt("en", ["th", "en"], "เบียร์", "Beer"), "เบียร์");
  assert.equal(alt("th", ["th", "en"], "เบียร์", "Beer"), "Beer");
});

test("printed menus: Thai is offered only where the portal offers Thai", () => {
  assert.deepEqual(printLangsFor(["en", "fr", "es", "de", "af"]), ["en", "fr", "es", "de", "af"]);
  assert.deepEqual(printLangsFor(["th", "en"]), ["en", "fr", "es", "de", "af", "th"]);
});

function thaiDoc(names: string[]): ExportDoc {
  return {
    filenameBase: "items",
    reportTitle: "รายการสินค้า",
    venue: "ร้านทดสอบ",
    scopeLabel: "สาขา: ทดสอบ",
    rangeLabel: "8 ม.ค. 2569",
    generatedLabel: "สร้างเมื่อ 8 ม.ค. 2569 09:00",
    locale: "th",
    sections: [
      {
        title: "รายการ",
        columns: [col.text<{ name: string }>("รายการ", (r) => r.name)],
        rows: names.map((name) => ({ name })),
      },
    ],
  };
}

test("PDF export: Thai is kept (no \\uFFFD) and drawn in the Thai face, marks included", () => {
  // a cluster with an above vowel and a tone mark survives whole
  assert.deepEqual(pdfText("น้ำ ผัดไท"), { text: "น้ำ ผัดไท", replaced: false });
  const def = buildDocDefinition(thaiDoc(["ต้มยำกุ้ง", "Pad Thai ผัดไทย", "ข้าวเหนียวมะม่วง 🍚"]));
  const json = JSON.stringify(def.content);
  assert.ok(json.includes(`{"text":"ต้มยำกุ้ง","font":"${THAI_PDF_FONT}"}`), json);
  assert.ok(json.includes(`"Pad Thai "`), "the Latin run stays in the default face");
  assert.ok(json.includes("�"), "the emoji is still replaced");
  // every Thai run's characters exist in the bundled Noto Sans Thai (no tofu)
  const req = createRequire(import.meta.url);
  const fontkit = req("@foliojs-fork/fontkit");
  for (const file of ["NotoSansThai-Regular.ttf", "NotoSansThai-Bold.ttf"]) {
    const font = fontkit.create(fs.readFileSync(path.join(WEB, "public", "fonts", file)));
    const missing: string[] = [];
    const walk = (n: unknown): void => {
      if (Array.isArray(n)) n.forEach(walk);
      else if (n && typeof n === "object") {
        const o = n as Record<string, unknown>;
        if (o.font === THAI_PDF_FONT && typeof o.text === "string")
          for (const ch of o.text) if (!font.hasGlyphForCodePoint(ch.codePointAt(0)!)) missing.push(ch);
        for (const v of Object.values(o)) walk(v);
      }
    };
    walk(def.content);
    assert.deepEqual(missing, [], file);
  }
});
