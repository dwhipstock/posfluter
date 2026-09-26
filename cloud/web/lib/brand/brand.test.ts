// Brand config resolution: which pack an instance wears, validation, and the
// CSS variables it turns into. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { BrandError, brandCss, brandCssVars, hexChannels, parseBrand, pickLocale } from "./brand";
import { DEFAULT_BRAND, brandDir, loadBrandFrom } from "./server";

const WEB = path.resolve(__dirname, "../..");
const pack = (id: string) => JSON.parse(fs.readFileSync(path.join(WEB, "brands", id, "brand.json"), "utf8"));

test("every shipped brand pack parses and has its files", () => {
  const ids = fs.readdirSync(path.join(WEB, "brands")).filter((d) => fs.existsSync(path.join(WEB, "brands", d, "brand.json")));
  assert.ok(ids.includes("copperlantern") && ids.includes("sagepoppy"), ids.join(","));
  for (const id of ids) {
    const b = loadBrandFrom(path.join(WEB, "brands", id));
    assert.equal(b.id, id, "the pack's id matches its folder");
  }
});

test("PORTAL_BRAND picks a shipped pack; unset keeps the first client's", () => {
  assert.equal(brandDir({}, "/app"), path.join("/app", "brands", DEFAULT_BRAND));
  assert.equal(brandDir({ PORTAL_BRAND: "sagepoppy" }, "/app"), path.join("/app", "brands", "sagepoppy"));
  assert.equal(brandDir({ PORTAL_BRAND: "  sagepoppy " }, "/app"), path.join("/app", "brands", "sagepoppy"));
});

test("PORTAL_BRAND_DIR (a mounted pack) wins over PORTAL_BRAND", () => {
  assert.equal(brandDir({ PORTAL_BRAND: "sagepoppy", PORTAL_BRAND_DIR: "/brand" }, "/app"), "/brand");
});

test("PORTAL_BRAND can't walk out of brands/", () => {
  for (const bad of ["../etc", "Sage", "a/b", "x".repeat(41)]) {
    assert.throws(() => brandDir({ PORTAL_BRAND: bad }, "/app"), BrandError, bad);
  }
});

test("a mounted custom pack loads", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "brand-"));
  const raw = { ...pack("sagepoppy"), id: "acme", name: "Acme Wines", legalName: "Acme Wines Ltd" };
  fs.writeFileSync(path.join(dir, "brand.json"), JSON.stringify(raw));
  for (const f of ["mark-192.png", "mark-384.png", "icon.png", "logo.png"]) fs.writeFileSync(path.join(dir, f), "x");
  const b = loadBrandFrom(dir);
  assert.equal(b.name, "Acme Wines");
  fs.rmSync(path.join(dir, "logo.png"));
  assert.throws(() => loadBrandFrom(dir), /logo\.png is missing/);
});

test("the two clients differ where it shows", () => {
  const cl = parseBrand(pack("copperlantern"));
  const sp = parseBrand(pack("sagepoppy"));
  assert.deepEqual(cl.locales, { default: "en", available: ["fr", "en"] });
  assert.deepEqual(sp.locales, { default: "en", available: ["en", "es"] });
  assert.equal(cl.currency, "CAD");
  assert.equal(sp.currency, "USD");
  assert.equal(cl.layout, "sidebar");
  assert.equal(sp.layout, "topbar");
  assert.equal(cl.login, "band");
  assert.equal(sp.login, "split");
  assert.equal(cl.font, "inter");
  assert.equal(sp.font, "jakarta");
  assert.equal(sp.shape.control, "9999px", "pill buttons");
  assert.equal(sp.shape.shadow, "none", "flat");
});

test("Copper Lantern's variables are exactly its previous hard-coded look", () => {
  const v = brandCssVars(parseBrand(pack("copperlantern")));
  assert.equal(v["--c-navy"], "23 69 110"); // #17456E
  assert.equal(v["--c-copper"], hexChannels("#A65A23"));
  assert.equal(v["--c-paper"], hexChannels("#F6F0E5"));
  assert.equal(v["--c-n-200"], hexChannels("#DCCFB9"));
  assert.equal(v["--radius-lg"], "0.5rem"); // Tailwind's default
  assert.equal(v["--radius-control"], "0.5rem");
  assert.equal(v["--shadow-raised"], "0 3px 10px rgba(23, 69, 110, 0.10)");
  assert.equal(v["--font-brand"], "var(--font-inter)");
});

test("defaults: a minimal pack gets sidebar/band/inter, English, CAD and stock radii", () => {
  const raw = pack("sagepoppy");
  delete raw.layout;
  delete raw.login;
  delete raw.font;
  delete raw.shape;
  delete raw.currency;
  raw.locales = { available: ["en"] };
  const b = parseBrand(raw);
  assert.equal(b.layout, "sidebar");
  assert.equal(b.login, "band");
  assert.equal(b.font, "inter");
  assert.equal(b.currency, "CAD");
  assert.equal(b.locales.default, "en");
  assert.equal(b.shape.radius.lg, "0.5rem");
});

test("validation names the bad field", () => {
  const bad = (mut: (r: any) => void, re: RegExp) => {
    const r = pack("copperlantern");
    mut(r);
    assert.throws(() => parseBrand(r), re);
  };
  bad((r) => (r.palette.primary = "navy"), /palette\.primary/);
  bad((r) => delete r.neutral["500"], /neutral\.500/);
  bad((r) => (r.locales = { default: "de", available: ["en"] }), /locales\.default/);
  bad((r) => (r.locales = { available: ["de"] }), /locales\.available/);
  bad((r) => (r.layout = "floating"), /brand\.layout/);
  bad((r) => (r.assets.mark = "../../etc/passwd"), /assets\.mark/);
  bad((r) => (r.shape = { control: "1px;}body{display:none" }), /shape\.control/);
  bad((r) => (r.shape = { shadow: "url(https://x)" }), /shape\.shadow/);
  bad((r) => (r.series = ["#000000"]), /series/);
  bad((r) => (r.currency = "dollars"), /currency/);
});

test("brandCss is one :root rule with every variable", () => {
  const css = brandCss(parseBrand(pack("sagepoppy")));
  assert.match(css, /^:root\{[^{}]+\}$/);
  assert.match(css, /--c-navy:74 107 71/);
  assert.match(css, /--font-brand:var\(--font-jakarta\)/);
});

test("pickLocale honours a saved choice only when the client offers it", () => {
  const sp = parseBrand(pack("sagepoppy"));
  const cl = parseBrand(pack("copperlantern"));
  assert.equal(pickLocale(sp, "es"), "es");
  assert.equal(pickLocale(sp, "fr"), "en", "no French at the shop");
  assert.equal(pickLocale(cl, "es"), "en", "no Spanish at the pub");
  assert.equal(pickLocale(cl, "fr"), "fr");
  assert.equal(pickLocale(cl, undefined), "en");
});
