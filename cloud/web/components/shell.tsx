"use client";

import { Suspense, useEffect } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { BarChart3, BookOpen, CircleUser, LayoutGrid, Package, TabletSmartphone, Users } from "lucide-react";
import { ApiError } from "@/lib/api";
import { useApi, useMe } from "@/lib/hooks";
import type { LowCountResponse } from "@/lib/types";
import { useStoreHref, useStores } from "@/lib/store";
import { isRetail } from "@/components/money-scope";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { LangToggle } from "@/components/lang-toggle";
import { BrandMark } from "@/components/brand-mark";
import { StorePicker } from "@/components/store-picker";
import { PageFallback } from "@/components/states";
import { cn } from "@/lib/utils";
import { useBrand } from "@/lib/brand/context";

const NAV: { href: string; labelKey: MsgKey; icon: typeof LayoutGrid }[] = [
  { href: "/", labelKey: "nav_dashboard", icon: LayoutGrid },
  { href: "/reports", labelKey: "nav_reports", icon: BarChart3 },
  { href: "/menu", labelKey: "nav_menu", icon: BookOpen },
  // retail stores only (see NavLinks): restaurants don't track stock
  { href: "/stock", labelKey: "nav_stock", icon: Package },
  { href: "/staff", labelKey: "nav_staff", icon: Users },
  { href: "/devices", labelKey: "nav_devices", icon: TabletSmartphone },
  { href: "/account", labelKey: "nav_account", icon: CircleUser },
];

// The bottom bar is a fixed 5-column grid — Devices lives under Account there,
// and Stock stays a desktop page.
const MOBILE_NAV = NAV.filter(({ href }) => href !== "/devices" && href !== "/stock");
// a group of shops only: Stock matters more on a phone than the (view-only) menu
const MOBILE_NAV_SHOPS = NAV.filter(({ href }) => href !== "/devices" && href !== "/menu");

function isActive(pathname: string, href: string) {
  return href === "/" ? pathname === "/" : pathname.startsWith(href);
}

/**
 * Nav links keep the picked store, so switching pages never silently widens the scope.
 * side/bottom: the dark sidebar + dark bottom bar; top/bottom-light: the light
 * top bar + light bottom bar (the brand pack's `layout`).
 */
function NavLinks({ variant }: { variant: "side" | "bottom" | "top" | "bottom-light" }) {
  const pathname = usePathname();
  const t = useT();
  const storeHref = useStoreHref();
  const { venues } = useStores();
  const hasRetail = venues.some((v) => isRetail(v));
  const wide = variant === "side" || variant === "top";
  const allRetail = venues.length > 0 && venues.every((v) => isRetail(v));
  const items = (wide ? NAV : allRetail ? MOBILE_NAV_SHOPS : MOBILE_NAV).filter(({ href }) => href !== "/stock" || hasRetail);
  // products at or below their reorder level, in the picked scope (retail only)
  const low = useApi<LowCountResponse>(hasRetail && wide ? "/v1/stock/low-count" : null, {
    refreshInterval: 5 * 60_000,
  });
  const lowCount = low.data?.retail ? low.data.lowCount : 0;
  return (
    <>
      {items.map(({ href, labelKey, icon: Icon }) => {
        const active = isActive(pathname, href);
        if (variant === "top") {
          return (
            <Link
              key={href}
              href={storeHref(href)}
              aria-current={active ? "page" : undefined}
              className={cn(
                "relative flex shrink-0 items-center gap-2 rounded-control px-3.5 py-2 text-sm font-semibold transition-colors",
                active ? "bg-navy-deep text-white" : "text-neutral-600 hover:bg-surface-alt hover:text-ink"
              )}
            >
              <Icon className="h-4 w-4" />
              {t(labelKey)}
              {href === "/stock" && lowCount > 0 && (
                <span
                  className="rounded-full bg-copper px-1.5 py-0.5 text-[10px] font-bold leading-none text-white tabular-nums"
                  title={t("stock_nav_low", { n: lowCount })}
                  aria-label={t("stock_nav_low", { n: lowCount })}
                >
                  {lowCount}
                </span>
              )}
            </Link>
          );
        }
        if (variant === "bottom-light") {
          return (
            <Link
              key={href}
              href={storeHref(href)}
              aria-current={active ? "page" : undefined}
              className={cn(
                "flex flex-col items-center gap-1 py-2 text-[10px] font-semibold transition-colors",
                active ? "text-navy-deep" : "text-neutral-500"
              )}
            >
              <span className={cn("flex h-7 w-12 items-center justify-center rounded-full", active && "bg-surface-alt")}>
                <Icon className="h-5 w-5" />
              </span>
              {t(labelKey)}
            </Link>
          );
        }
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
            {href === "/stock" && lowCount > 0 && (
              <span
                className="ml-auto rounded-full bg-amber-400 px-1.5 py-0.5 text-[10px] font-semibold leading-none text-navy-deep tabular-nums"
                title={t("stock_nav_low", { n: lowCount })}
                aria-label={t("stock_nav_low", { n: lowCount })}
              >
                {lowCount}
              </span>
            )}
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

function PageBody({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  return (
    <motion.div
      key={pathname}
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.15, ease: "easeOut" }}
    >
      {/* pages read ?store= (useSearchParams) — keep them under Suspense */}
      <Suspense fallback={<PageFallback />}>{children}</Suspense>
    </motion.div>
  );
}

/** Signed-in chrome, in the brand's layout: a dark sidebar, or a light top bar. */
export function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const me = useMe();
  const brand = useBrand();
  const groupName = me.data?.tenantName ?? me.data?.venueName ?? "";

  // 401s hard-redirect in the api layer; this catches totp_pending (403).
  // Only auth failures bounce — a transient network error must not throw a
  // signed-in owner out (SWR retries and the session cookie is still good).
  useEffect(() => {
    if (me.error instanceof ApiError && (me.error.status === 401 || me.error.status === 403)) {
      router.replace("/login");
    }
  }, [me.error, router]);

  return brand.layout === "topbar" ? (
    <TopBarShell groupName={groupName}>{children}</TopBarShell>
  ) : (
    <SidebarShell groupName={groupName}>{children}</SidebarShell>
  );
}

/**
 * The light top-bar layout: brand mark, horizontal pill navigation and the
 * store picker in one white bar; a light bottom bar on phones. Flat, no
 * sidebar — a different product feel, same pages.
 */
function TopBarShell({ groupName, children }: { groupName: string; children: React.ReactNode }) {
  const t = useT();
  return (
    <div className="min-h-dvh bg-paper">
      <header className="sticky top-0 z-40 border-b border-neutral-200 bg-surface">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center gap-4 px-4 md:px-8">
          <BrandMark tone="light" />
          <nav className="no-scrollbar hidden min-w-0 flex-1 items-center gap-1 overflow-x-auto lg:flex">
            <Suspense fallback={null}>
              <NavLinks variant="top" />
            </Suspense>
          </nav>
          <div className="ml-auto flex min-w-0 items-center gap-2">
            <Suspense fallback={null}>
              <StorePicker className="w-40 md:w-52" />
            </Suspense>
            <LangToggle tone="light" />
          </div>
        </div>
        {/* tablets: the nav gets its own row under the bar */}
        <nav className="no-scrollbar hidden items-center gap-1 overflow-x-auto border-t border-neutral-100 px-4 py-2 md:flex md:px-8 lg:hidden">
          <Suspense fallback={null}>
            <NavLinks variant="top" />
          </Suspense>
        </nav>
      </header>

      <main className="min-w-0 pb-24 md:pb-12">
        <div className="mx-auto w-full max-w-6xl px-4 pt-5 md:px-8 md:pt-8">
          <PageBody>{children}</PageBody>
        </div>
        <footer className="mx-auto hidden w-full max-w-6xl px-8 pt-10 text-xs text-neutral-500 md:block">
          {groupName || " "}
        </footer>
      </main>

      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-neutral-200 bg-surface pb-[env(safe-area-inset-bottom)] md:hidden">
        <div className="grid grid-cols-5">
          <Suspense fallback={null}>
            <NavLinks variant="bottom-light" />
          </Suspense>
        </div>
      </nav>
    </div>
  );
}

/** The dark sidebar layout (desktop) + dark bottom bar (phones). */
function SidebarShell({ groupName, children }: { groupName: string; children: React.ReactNode }) {
  const t = useT();
  return (
    <div className="min-h-dvh bg-paper md:flex">
      {/* The navy column stretches to the full page height; its content is a
          viewport-tall sticky panel whose nav scrolls on its own if needed. */}
      <aside className="z-40 hidden w-60 shrink-0 bg-navy text-white md:block">
        <div className="sticky top-0 flex h-dvh flex-col">
          <div className="flex h-16 items-center bg-navy-deep px-5">
            <BrandMark />
          </div>
          <nav className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-4">
            <Suspense fallback={null}>
              <NavLinks variant="side" />
            </Suspense>
          </nav>
          <div className="shrink-0 space-y-2 border-t border-white/10 px-5 py-4 text-xs text-navy-muted">
            <LangToggle tone="dark" />
            <div className="truncate">{groupName || " "}</div>
          </div>
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

      <main className="min-w-0 flex-1 pb-24 md:pb-12">
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
          <PageBody>{children}</PageBody>
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
