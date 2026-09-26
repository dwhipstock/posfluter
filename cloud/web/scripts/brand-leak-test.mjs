#!/usr/bin/env node
// No-leak test: one portal build, served once per brand pack, must never show
// another client's name. For each pack in brands/ this starts the production
// server (the same standalone bundle the image runs) with PORTAL_BRAND=<id>,
// renders the key pages — sign-in, dashboard, reports, stock, account, the
// staff-app fallback, the manifest — fetches every script and stylesheet those
// pages load, and fails if any of it names another pack (its name, full name
// or id), or if another pack's image is served.
//
//   npm run build && npm run test:brands
//
// No API is needed: pages render their shell server-side and fetch data in the
// browser; the staff-app route finds no store and serves its offline page.
import { spawn } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const STANDALONE = path.join(WEB, ".next", "standalone");
if (!fs.existsSync(path.join(STANDALONE, "server.js"))) {
  console.error("No standalone build — run `npm run build` first.");
  process.exit(2);
}
// lay the bundle out like the image does (cloud/infra/Dockerfile.web*)
fs.cpSync(path.join(WEB, ".next", "static"), path.join(STANDALONE, ".next", "static"), { recursive: true });
fs.cpSync(path.join(WEB, "public"), path.join(STANDALONE, "public"), { recursive: true });
fs.rmSync(path.join(STANDALONE, "brands"), { recursive: true, force: true });
fs.cpSync(path.join(WEB, "brands"), path.join(STANDALONE, "brands"), { recursive: true });

const packs = fs
  .readdirSync(path.join(WEB, "brands"))
  .filter((d) => fs.existsSync(path.join(WEB, "brands", d, "brand.json")))
  .map((d) => JSON.parse(fs.readFileSync(path.join(WEB, "brands", d, "brand.json"), "utf8")));
if (packs.length < 2) {
  console.error("Need at least two brand packs to test for leaks.");
  process.exit(2);
}

const PAGES = ["/login", "/", "/reports", "/reports/payments", "/reports/fuel", "/stock", "/menu", "/staff", "/devices", "/account", "/staff-app", "/manifest.webmanifest"];

const escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
/** Everything that would give a pack away: its names (plain, HTML- and JSON-escaped) and its id. */
function tellTales(p) {
  const words = new Set([p.name, p.legalName, p.id].filter(Boolean));
  for (const w of [...words]) {
    words.add(escapeHtml(w));
    words.add(w.replace(/&/g, "\\u0026"));
    words.add(w.replace(/\s+/g, "")); // "CopperLantern"
  }
  return new RegExp([...words].map(escapeRe).join("|"), "i");
}

const freePort = () =>
  new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, "127.0.0.1", () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });

async function waitUp(base, child) {
  for (let i = 0; i < 100; i++) {
    if (child.exitCode !== null) throw new Error(`server exited (${child.exitCode})`);
    try {
      const r = await fetch(`${base}/manifest.webmanifest`);
      if (r.ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error("server did not start");
}

let failures = 0;
const fail = (msg) => {
  failures++;
  console.error(`  ✗ ${msg}`);
};

for (const brand of packs) {
  const others = packs.filter((p) => p.id !== brand.id);
  const port = await freePort();
  const base = `http://127.0.0.1:${port}`;
  const child = spawn(process.execPath, ["server.js"], {
    cwd: STANDALONE,
    env: {
      ...process.env,
      PORT: String(port),
      HOSTNAME: "127.0.0.1",
      PORTAL_BRAND: brand.id,
      PORTAL_BRAND_DIR: "",
      API_ORIGIN: "http://127.0.0.1:9", // nothing listens: the staff-app route falls back
      NODE_ENV: "production",
    },
    stdio: ["ignore", "ignore", "pipe"],
  });
  let stderr = "";
  child.stderr.on("data", (d) => (stderr += d));
  console.log(`${brand.id} (${brand.name})`);
  try {
    await waitUp(base, child);
    const assets = new Set();
    const bodies = [];
    for (const page of PAGES) {
      const lang = brand.locales?.available ?? ["en"];
      // every language the client offers
      for (const locale of lang) {
        const r = await fetch(base + page, { headers: { cookie: `locale=${locale}` }, redirect: "manual" });
        const body = await r.text();
        if (r.status >= 500 && page !== "/staff-app") fail(`${page} [${locale}] → ${r.status}`);
        bodies.push({ where: `${page} [${locale}]`, body });
        for (const m of body.matchAll(/(?:src|href)="(\/_next\/static\/[^"]+\.(?:js|css))"/g)) assets.add(m[1]);
      }
    }
    for (const a of assets) {
      const r = await fetch(base + a);
      bodies.push({ where: a, body: await r.text() });
    }
    // the page names its own brand…
    const login = bodies.find((b) => b.where.startsWith("/login"))?.body ?? "";
    if (!login.includes(`<title>${escapeHtml(brand.name)}</title>`)) fail(`/login <title> is not "${brand.name}"`);
    // …and nobody else's, anywhere
    for (const other of others) {
      const re = tellTales(other);
      for (const { where, body } of bodies) {
        const m = body.match(re);
        if (m) fail(`${where} names ${other.id}: "${body.slice(Math.max(0, m.index - 40), m.index + 40).replace(/\s+/g, " ")}"`);
      }
      // another pack's images are not served
      for (const f of Object.values(other.assets ?? {})) {
        if (Object.values(brand.assets).includes(f)) continue; // same file name in both packs
        const r = await fetch(`${base}/brand/${f}`);
        if (r.status !== 404) fail(`/brand/${f} (${other.id}'s) → ${r.status}, want 404`);
      }
    }
    // its own images are
    for (const f of Object.values(brand.assets)) {
      const r = await fetch(`${base}/brand/${f}`);
      if (r.status !== 200) fail(`/brand/${f} → ${r.status}`);
    }
    const manifest = await (await fetch(`${base}/manifest.webmanifest`)).json();
    if (manifest.name !== brand.name) fail(`manifest name "${manifest.name}"`);
    console.log(`  checked ${PAGES.length} pages × ${(brand.locales?.available ?? ["en"]).length} languages, ${assets.size} scripts/styles`);
  } catch (e) {
    fail(`${brand.id}: ${e.message}\n${stderr}`);
  } finally {
    child.kill();
  }
}

if (failures) {
  console.error(`\n${failures} leak check(s) failed`);
  process.exit(1);
}
console.log("\nno brand leaks");
