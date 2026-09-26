import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import localFont from "next/font/local";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";
import { LocaleProvider } from "@/lib/i18n/context";
import { translate } from "@/lib/i18n/translate";
import { BrandProvider } from "@/lib/brand/context";
import { brandAssetUrl, brandCss, pickLocale } from "@/lib/brand/brand";
import { getBrand } from "@/lib/brand/server";

// The brand is read per request from this instance's brand pack (PORTAL_BRAND /
// PORTAL_BRAND_DIR), so one build serves every client.
export const dynamic = "force-dynamic";

// Both faces are bundled from app/fonts (Latin subsets, accents included); the
// brand pack picks one (--font-brand). No font CDN is called, at build or run time.
const inter = localFont({
  src: [
    { path: "./fonts/Inter-Regular.woff2", weight: "400", style: "normal" },
    { path: "./fonts/Inter-Medium.woff2", weight: "500", style: "normal" },
    { path: "./fonts/Inter-SemiBold.woff2", weight: "600", style: "normal" },
    { path: "./fonts/Inter-Bold.woff2", weight: "700", style: "normal" },
  ],
  variable: "--font-inter",
  display: "swap",
});

const jakarta = localFont({
  src: [
    { path: "./fonts/PlusJakartaSans-400.ttf", weight: "400", style: "normal" },
    { path: "./fonts/PlusJakartaSans-500.ttf", weight: "500", style: "normal" },
    { path: "./fonts/PlusJakartaSans-600.ttf", weight: "600", style: "normal" },
    { path: "./fonts/PlusJakartaSans-700.ttf", weight: "700", style: "normal" },
    { path: "./fonts/PlusJakartaSans-800.ttf", weight: "800", style: "normal" },
  ],
  variable: "--font-jakarta",
  display: "swap",
  // only the brands that use it download it
  preload: false,
});

export async function generateMetadata(): Promise<Metadata> {
  const brand = getBrand();
  const locale = pickLocale(brand, (await cookies()).get("locale")?.value);
  return {
    title: { default: brand.name, template: `%s · ${brand.name}` },
    description: translate(locale, "brand_tagline"),
    applicationName: brand.name,
    icons: { icon: brandAssetUrl(brand.assets.icon), apple: brandAssetUrl(brand.assets.markLarge) },
    manifest: "/manifest.webmanifest",
  };
}

export async function generateViewport(): Promise<Viewport> {
  const brand = getBrand();
  return {
    themeColor: brand.layout === "topbar" ? brand.palette.surface : brand.palette.primary,
    width: "device-width",
    initialScale: 1,
  };
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const brand = getBrand();
  // The visitor's saved language when this client offers it, else the client's
  // default. Reading the cookie here makes <html lang> right on the first server
  // render, before any JS loads (screen readers, hyphenation, :lang() rules).
  const locale = pickLocale(brand, (await cookies()).get("locale")?.value);

  return (
    <html lang={locale} className={`${inter.variable} ${jakarta.variable}`} data-brand={brand.id}>
      <head>
        {/* the brand's palette, radii, shadow and face as CSS variables (tailwind.config.ts) */}
        <style id="brand-vars" dangerouslySetInnerHTML={{ __html: brandCss(brand) }} />
      </head>
      <body>
        <BrandProvider brand={brand}>
          <LocaleProvider initialLocale={locale} available={brand.locales.available}>
            {children}
            <Toaster />
          </LocaleProvider>
        </BrandProvider>
      </body>
    </html>
  );
}
