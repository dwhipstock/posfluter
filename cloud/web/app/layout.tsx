import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import localFont from "next/font/local";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";
import { LocaleProvider } from "@/lib/i18n/context";
import type { Locale } from "@/lib/i18n/messages";
import { BRAND } from "@/lib/theme";

// Inter, the tablet's face, bundled from app/fonts (a Latin subset: English and
// French, accents included). No font CDN is called, at build time or run time.
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

export const metadata: Metadata = {
  title: "Copper Lantern",
  description: "Owner portal",
};

export const viewport: Viewport = {
  themeColor: BRAND.navy,
  width: "device-width",
  initialScale: 1,
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // English is the default; the owner can opt into French. Reading the cookie here
  // makes <html lang> correct on the first server render, before any JS loads
  // (screen readers, hyphenation and :lang() rules key off it).
  const store = await cookies();
  const locale: Locale = store.get("locale")?.value === "fr" ? "fr" : "en";

  return (
    <html lang={locale} className={inter.variable}>
      <body>
        <LocaleProvider initialLocale={locale}>
          {children}
          <Toaster />
        </LocaleProvider>
      </body>
    </html>
  );
}
