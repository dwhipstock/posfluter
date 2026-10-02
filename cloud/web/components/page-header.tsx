"use client";

import { useEffect } from "react";
import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { shortStoreName, useStores, useStoreHref } from "@/lib/store";
import { useT } from "@/lib/i18n/context";
import { ScopeChip } from "@/components/scope-chip";
import { useBrand } from "@/lib/brand/context";

/**
 * Every page's title row. The scope is part of the title: the picked store's
 * name (or "All stores") follows it on screen, in the browser tab and so in
 * bookmarks and printouts.
 */
export function PageHeader({
  title,
  sub,
  back,
  action,
  scoped = true,
}: {
  title: string;
  sub?: string;
  back?: { href: string; label: string };
  action?: React.ReactNode;
  /** false for pages that are not store data (account). */
  scoped?: boolean;
}) {
  const t = useT();
  const storeHref = useStoreHref();
  const { store, venues, storeId } = useStores();
  const brandName = useBrand().name;
  const scopeName = storeId ? shortStoreName(store?.name ?? storeId) : venues.length > 1 ? t("store_all") : null;

  useEffect(() => {
    document.title = [title, scoped ? scopeName : null, brandName].filter(Boolean).join(" · ");
  }, [title, scopeName, scoped, brandName]);

  return (
    // phones: the actions drop below the title (side by side they squeezed the title to one
    // letter per line on the Menu page); from sm up they sit on the right
    <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between md:mb-6">
      <div className="min-w-0 sm:flex-1">
        {back && (
          <Link
            href={storeHref(back.href)}
            className="mb-1 inline-flex items-center gap-0.5 text-xs font-medium text-neutral-500 hover:text-navy"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            {back.label}
          </Link>
        )}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
          <h1 className="text-xl font-bold tracking-tight text-navy md:text-2xl">
            {title}
            {scoped && store && <span className="sr-only"> · {shortStoreName(store.name)}</span>}
          </h1>
          {scoped && <ScopeChip />}
        </div>
        {sub && <p className="mt-1 text-sm text-neutral-500">{sub}</p>}
      </div>
      {action && <div className="min-w-0 sm:shrink-0 sm:pt-1">{action}</div>}
    </div>
  );
}
