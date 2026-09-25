import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import { Noto_Sans } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";
import { LocaleProvider } from "@/lib/i18n/context";
import type { Locale } from "@/lib/i18n/messages";

// Latin subset of Noto Sans: covers English and French (accents included), so
// both locales render from one face; the system sans in globals.css is the
// fallback while it loads.
const notoSans = Noto_Sans({
  subsets: ["latin"],
  variable: "--font-french",
  display: "swap",
});

export const metadata: Metadata = {
  title: "The Copper Lantern Pub",
  description: "Owner portal",
};

export const viewport: Viewport = {
  themeColor: "#17263A",
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
    <html lang={locale} className={notoSans.variable}>
      <body>
        <LocaleProvider initialLocale={locale}>
          {children}
          <Toaster />
        </LocaleProvider>
      </body>
    </html>
  );
}
