"use client";

import Link from "next/link";
import { Armchair, ChevronRight } from "lucide-react";
import { useT } from "@/lib/i18n/context";
import { useStoreHref, useStores } from "@/lib/store";
import { isRetail } from "@/components/money-scope";

/**
 * Phones: the bottom bar is full, so Rooms is reached from the Menu page
 * (desktop has it in the nav). Restaurants only: a shop has no floor plan.
 */
export function RoomsLink() {
  const t = useT();
  const storeHref = useStoreHref();
  const { venues, store } = useStores();
  const floors = store ? !isRetail(store) : venues.some((v) => !isRetail(v));
  if (!floors) return null;
  return (
    <Link
      href={storeHref("/rooms")}
      className="flex items-center gap-3 rounded-2xl border border-neutral-200 bg-surface px-4 py-3 transition-colors hover:bg-neutral-50 md:hidden"
    >
      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-neutral-100 text-neutral-700">
        <Armchair className="h-4 w-4" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold text-ink">{t("rooms_title")}</span>
        <span className="block text-xs text-neutral-700">{t("rooms_card_hint")}</span>
      </span>
      <ChevronRight className="h-4 w-4 shrink-0 text-neutral-500" aria-label={t("rooms_open")} />
    </Link>
  );
}
