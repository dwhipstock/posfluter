import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import { Noto_Sans } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";
import { LocaleProvider } from "@/lib/i18n/context";
import type { Era, Locale } from "@/lib/i18n/messages";

// French-only subset: the font file carries just French glyphs, so Latin text
// falls through to the system sans (kept in globals.css) and only the brand's
// look is unchanged — while every French character renders from one clean face.
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
  // makes <html lang> correct on the first server render, which is what turns
  // on the browser's French line-breaking dictionary before any JS loads.
  const store = await cookies();
  const locale: Locale = store.get("locale")?.value === "fr" ? "fr" : "en";
  const era: Era = "CE";

  return (
    <html lang={locale} className={notoSans.variable}>
      <body>
        <LocaleProvider initialLocale={locale} initialEra={era}>
          {children}
          <Toaster />
        </LocaleProvider>
      </body>
    </html>
  );
}
