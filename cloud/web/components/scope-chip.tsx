"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Layers, X } from "lucide-react";
import { STORE_PARAM, shortStoreName, useStores } from "@/lib/store";
import { useT } from "@/lib/i18n/context";
import { cn } from "@/lib/utils";

/**
 * Which stores the page is showing, impossible to miss: one store (its colour
 * dot + name, with a one-tap way back to all stores) or "All stores" (every
 * store's dot). Sits in the page header next to the title. Under <Suspense>.
 */
export function ScopeChip({ className }: { className?: string }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { venues, store, storeId, colorOf } = useStores();

  if (venues.length < 2 && !storeId) return null;

  if (storeId) {
    const clear = () => {
      const params = new URLSearchParams(searchParams.toString());
      params.delete(STORE_PARAM);
      params.delete("page");
      const qs = params.toString();
      router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    };
    return (
      <span
        className={cn(
          "inline-flex items-center gap-2 rounded-full border border-copper/40 bg-copper-soft py-1 pl-2.5 pr-1 text-xs font-semibold text-copper-text",
          className
        )}
        data-testid="scope-chip"
      >
        <span className="h-2.5 w-2.5 rounded-full ring-2 ring-surface" style={{ backgroundColor: colorOf(storeId) }} />
        {t("scope_single", { store: store ? shortStoreName(store.name) : storeId })}
        <button
          type="button"
          onClick={clear}
          className="grid h-5 w-5 place-items-center rounded-full text-copper-text transition-colors hover:bg-copper/15"
          aria-label={t("scope_show_all")}
          title={t("scope_show_all")}
        >
          <X className="h-3 w-3" />
        </button>
      </span>
    );
  }

  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded-full border border-navy/25 bg-navy/[0.07] px-2.5 py-1 text-xs font-semibold text-navy",
        className
      )}
      data-testid="scope-chip"
    >
      <Layers className="h-3.5 w-3.5" />
      {t("scope_all_n", { n: venues.length })}
      <span className="flex -space-x-1" aria-hidden>
        {venues.map((v) => (
          <span key={v.id} className="h-2.5 w-2.5 rounded-full ring-2 ring-surface" style={{ backgroundColor: colorOf(v.id) }} />
        ))}
      </span>
    </span>
  );
}
