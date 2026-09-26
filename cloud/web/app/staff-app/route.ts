import { NextResponse } from "next/server";
import { brandAssetUrl } from "@/lib/brand/brand";
import { getBrand } from "@/lib/brand/server";
import { translate } from "@/lib/i18n/translate";

// The stable staff entry point (M7). Staff bookmark <portal>/staff-app on their
// phones; this route asks the cloud API for the store's current reachable LAN
// base URL and 302-redirects to <base>/staff-app — the in-store ordering app.
// The direct LAN URL stays the offline fallback; ordering never leaves the store.
//
// Server-side only, so it hits the API by origin (not the browser rewrite). Same
// API_ORIGIN default the next.config rewrite uses.
const API_ORIGIN = process.env.API_ORIGIN ?? "http://localhost:8081";

export const dynamic = "force-dynamic"; // never cache: the LAN IP changes with DHCP

// Multi-store: /staff-app?store=<venueId> picks the store (one QR per store).
export async function GET(req: Request) {
  const store = new URL(req.url).searchParams.get("store");
  const query = store ? `?venue=${encodeURIComponent(store)}` : "";
  try {
    const res = await fetch(`${API_ORIGIN}/v1/staff-endpoint${query}`, { cache: "no-store" });
    if (res.ok) {
      const data = (await res.json()) as { base?: string };
      const target = safeStaffUrl(data.base);
      if (target) return NextResponse.redirect(target, 302);
    }
  } catch {
    // fall through to the offline page — the store hasn't reported an address yet
  }
  return new NextResponse(offlinePage(), {
    status: 503,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
  });
}

// The base is the store's self-reported LAN URL (behind a store API key). We
// still fix the path ourselves and only follow http(s) — the store never dictates
// where on the portal a phone lands, only which in-store host serves it.
function safeStaffUrl(base: string | undefined): string | null {
  if (!base) return null;
  try {
    const u = new URL("/staff-app", base);
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    return u.toString();
  } catch {
    return null;
  }
}

// Calm, in this client's colours and languages (its default first, then the
// others) — the store is simply not reachable right now (offline, or hasn't
// synced since boot). No app chrome; this page is only ever hit when there's
// nothing to redirect to.
function offlinePage(): string {
  const b = getBrand();
  const [first, ...rest] = [b.locales.default, ...b.locales.available.filter((l) => l !== b.locales.default)];
  const title = `${esc(b.name)} — ${[first, ...rest].map((l) => esc(translate(l, "staff_offline_title"))).join(" / ")}`;
  const hints = [first, ...rest].map((l) => esc(translate(l, "staff_offline_hint"))).join("<br>");
  return `<!doctype html>
<html lang="${first}"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<link rel="icon" href="${brandAssetUrl(b.assets.icon)}">
<style>
  *{box-sizing:border-box;margin:0;font-family:system-ui,-apple-system,'Segoe UI',sans-serif}
  body{background:${b.palette.background};color:${b.palette.text};min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
  .card{max-width:420px;text-align:center}
  .icon{font-size:44px;margin-bottom:16px}
  .main{font-size:20px;font-weight:700;line-height:1.5}
  .alt{font-size:15px;color:${b.palette.textMuted};margin-top:10px;line-height:1.5}
  .hint{font-size:13px;color:${b.palette.textMuted};margin-top:20px;line-height:1.6}
  code{background:${b.palette.surfaceAlt};border-radius:6px;padding:2px 6px;font-size:13px}
</style></head><body>
  <div class="card">
    <div class="icon">📶</div>
    <div class="main" lang="${first}">${esc(translate(first, "staff_offline_body"))}</div>
${rest.map((l) => `    <div class="alt" lang="${l}">${esc(translate(l, "staff_offline_body"))}</div>`).join("\n")}
    <div class="hint">${hints}<br><code>http://&lt;store-ip&gt;:8080/staff-app</code></div>
  </div>
</body></html>`;
}

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}
