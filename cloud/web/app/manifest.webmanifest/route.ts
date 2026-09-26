import { NextResponse } from "next/server";
import { brandAssetUrl } from "@/lib/brand/brand";
import { getBrand } from "@/lib/brand/server";
import { translate } from "@/lib/i18n/translate";

// The web app manifest ("add to home screen"): this client's name, colours and icon.
export const dynamic = "force-dynamic";

export async function GET() {
  const b = getBrand();
  const manifest = {
    name: b.name,
    short_name: b.name,
    description: translate(b.locales.default, "brand_tagline"),
    start_url: "/",
    display: "standalone",
    background_color: b.palette.background,
    theme_color: b.layout === "topbar" ? b.palette.surface : b.palette.primary,
    icons: [
      { src: brandAssetUrl(b.assets.markLarge), sizes: "384x384", purpose: "any" },
      { src: brandAssetUrl(b.assets.mark), sizes: "192x192", purpose: "any" },
    ],
  };
  return NextResponse.json(manifest, {
    headers: { "content-type": "application/manifest+json", "cache-control": "public, max-age=3600" },
  });
}
