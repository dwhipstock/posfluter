"use client";

import { Suspense, useEffect } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { BarChart3, BookOpen, CircleUser, LayoutGrid, TabletSmartphone, Users } from "lucide-react";
import { ApiError } from "@/lib/api";
import { useMe } from "@/lib/hooks";
import { useStoreHref } from "@/lib/store";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { LangToggle } from "@/components/lang-toggle";
import { BrandMark } from "@/components/brand-mark";
import { StorePicker } from "@/components/store-picker";
import { PageFallback } from "@/components/states";
import { cn } from "@/lib/utils";

const NAV: { href: string; labelKey: MsgKey; icon: typeof LayoutGrid }[] = [
  { href: "/", labelKey: "nav_dashboard", icon: LayoutGrid },
  { href: "/reports", labelKey: "nav_reports", icon: BarChart3 },
  { href: "/menu", labelKey: "nav_menu", icon: BookOpen },
  { href: "/staff", labelKey: "nav_staff", icon: Users },
  { href: "/devices", labelKey: "nav_devices", icon: TabletSmartphone },
  { href: "/account", labelKey: "nav_account", icon: CircleUser },
];

// The bottom bar is a fixed 5-column grid — Devices lives under Account there.
const MOBILE_NAV = NAV.filter(({ href }) => href !== "/devices");

function isActive(pathname: string, href: string) {
  return href === "/" ? pathname === "/" : pathname.startsWith(href);
}

/** Nav links keep the picked store, so switching pages never silently widens the scope. */
function NavLinks({ variant }: { variant: "side" | "bottom" }) {
  const pathname = usePathname();
  const t = useT();
  const storeHref = useStoreHref();
  const items = variant === "side" ? NAV : MOBILE_NAV;
  return (
    <>
      {items.map(({ href, labelKey, icon: Icon }) => {
        const active = isActive(pathname, href);
        return variant === "side" ? (
          <Link
            key={href}
            href={storeHref(href)}
            aria-current={active ? "page" : undefined}
            className={cn(
              "relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors",
              active ? "bg-white/10 text-white" : "text-navy-muted hover:bg-white/5 hover:text-white"
            )}
          >
            {/* the copper accent, used sparingly: the current page */}
            {active && <span className="absolute inset-y-2 left-0 w-[3px] rounded-full bg-copper" aria-hidden />}
            <Icon className="h-[18px] w-[18px]" />
            {t(labelKey)}
          </Link>
        ) : (
          <Link
            key={href}
            href={storeHref(href)}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex flex-col items-center gap-1 py-2.5 text-[10px] font-medium transition-colors",
              active ? "text-white" : "text-navy-muted"
            )}
          >
            <Icon className={cn("h-5 w-5", active && "text-copper-soft")} />
            {t(labelKey)}
          </Link>
        );
      })}
    </>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const t = useT();
  const me = useMe();
  const groupName = me.data?.tenantName ?? me.data?.venueName ?? "";

  // 401s hard-redirect in the api layer; this catches totp_pending (403).
  // Only auth failures bounce — a transient network error must not throw a
  // signed-in owner out (SWR retries and the session cookie is still good).
  useEffect(() => {
    if (me.error instanceof ApiError && (me.error.status === 401 || me.error.status === 403)) {
      router.replace("/login");
    }
  }, [me.error, router]);

  return (
    <div className="min-h-dvh bg-paper">
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-60 flex-col bg-navy text-white md:flex">
        <div className="flex h-16 items-center bg-navy-deep px-5">
          <BrandMark />
        </div>
        <nav className="flex-1 space-y-1 px-3 py-4">
          <Suspense fallback={null}>
            <NavLinks variant="side" />
          </Suspense>
        </nav>
        <div className="space-y-2 border-t border-white/10 px-5 py-4 text-xs text-navy-muted">
          <LangToggle tone="dark" />
          <div className="truncate">{groupName || " "}</div>
        </div>
      </aside>

      <header className="sticky top-0 z-40 flex h-14 items-center justify-between gap-2 bg-navy px-4 text-white md:hidden">
        <BrandMark compact />
        <div className="flex min-w-0 items-center gap-2">
          <Suspense fallback={null}>
            <StorePicker tone="dark" className="w-36" />
          </Suspense>
          <LangToggle tone="dark" />
        </div>
      </header>

      <main className="pb-24 md:pb-12 md:pl-60">
        {/* desktop header: the store picker sits above every page */}
        <div className="sticky top-0 z-30 hidden h-16 items-center justify-between gap-3 border-b border-neutral-200 bg-surface/95 px-8 backdrop-blur md:flex">
          <span className="truncate text-sm font-semibold text-navy">{groupName}</span>
          <div className="flex items-center gap-3">
            <span className="text-xs font-medium text-neutral-500">{t("store_label")}</span>
            <Suspense fallback={null}>
              <StorePicker className="w-60" />
            </Suspense>
          </div>
        </div>
        <div className="mx-auto w-full max-w-6xl px-4 pt-5 md:px-8 md:pt-8">
          <motion.div
            key={pathname}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.15, ease: "easeOut" }}
          >
            {/* pages read ?store= (useSearchParams) — keep them under Suspense */}
            <Suspense fallback={<PageFallback />}>{children}</Suspense>
          </motion.div>
        </div>
      </main>

      <nav className="fixed inset-x-0 bottom-0 z-40 bg-navy pb-[env(safe-area-inset-bottom)] md:hidden">
        <div className="grid grid-cols-5">
          <Suspense fallback={null}>
            <NavLinks variant="bottom" />
          </Suspense>
        </div>
      </nav>
    </div>
  );
}
