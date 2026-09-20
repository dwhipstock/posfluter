"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { BarChart3, BookOpen, CircleUser, LayoutGrid, TabletSmartphone, Users } from "lucide-react";
import { ApiError } from "@/lib/api";
import { useMe } from "@/lib/hooks";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { LangEraToggle } from "@/components/lang-toggle";
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

function Wordmark() {
  return (
    <span className="flex items-baseline text-sm font-bold uppercase tracking-[0.18em]">
      CopperLantern<span className="ml-0.5 text-lg leading-none text-accent">.</span>
    </span>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const me = useMe();
  const t = useT();

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
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-56 flex-col bg-ink text-white md:flex">
        <div className="flex h-16 items-center border-b border-white/10 px-5">
          <Wordmark />
        </div>
        <nav className="flex-1 space-y-1 px-3 py-4">
          {NAV.map(({ href, labelKey, icon: Icon }) => {
            const active = isActive(pathname, href);
            return (
              <Link
                key={href}
                href={href}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors",
                  active ? "bg-white/5 text-accent" : "text-neutral-400 hover:bg-white/5 hover:text-white"
                )}
              >
                <Icon className="h-[18px] w-[18px]" />
                {t(labelKey)}
              </Link>
            );
          })}
        </nav>
        <div className="space-y-3 border-t border-white/10 px-5 py-4 text-xs text-neutral-500">
          <LangEraToggle tone="dark" />
          {me.data?.venueName ?? " "}
        </div>
      </aside>

      <header className="sticky top-0 z-40 flex h-14 items-center justify-between gap-2 bg-ink px-4 text-white md:hidden">
        <Wordmark />
        <div className="flex min-w-0 items-center gap-2">
          <span className="truncate text-xs text-neutral-400">{me.data?.venueName ?? ""}</span>
          <LangEraToggle tone="dark" />
        </div>
      </header>

      <main className="pb-24 md:pb-12 md:pl-56">
        <div className="mx-auto w-full max-w-5xl px-4 pt-5 md:px-8 md:pt-8">
          <motion.div
            key={pathname}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2, ease: "easeOut" }}
          >
            {children}
          </motion.div>
        </div>
      </main>

      <nav className="fixed inset-x-0 bottom-0 z-40 bg-ink pb-[env(safe-area-inset-bottom)] md:hidden">
        <div className="grid grid-cols-5">
          {MOBILE_NAV.map(({ href, labelKey, icon: Icon }) => {
            const active = isActive(pathname, href);
            return (
              <Link
                key={href}
                href={href}
                className={cn(
                  "flex flex-col items-center gap-1 py-2.5 text-[10px] font-medium transition-colors",
                  active ? "text-accent" : "text-neutral-400"
                )}
              >
                <Icon className="h-5 w-5" />
                {t(labelKey)}
              </Link>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
