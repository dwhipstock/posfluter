"use client";

import Link from "next/link";
import { useStoreHref } from "@/lib/store";
import { ChevronLeft } from "lucide-react";

export function PageHeader({
  title,
  sub,
  back,
  action,
}: {
  title: string;
  sub?: string;
  back?: { href: string; label: string };
  action?: React.ReactNode;
}) {
  const storeHref = useStoreHref();
  return (
    <div className="mb-4 flex items-start justify-between gap-3 md:mb-6">
      <div className="min-w-0">
        {back && (
          <Link
            href={storeHref(back.href)}
            className="mb-1 inline-flex items-center gap-0.5 text-xs font-medium text-neutral-500 hover:text-ink"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            {back.label}
          </Link>
        )}
        <h1 className="truncate text-xl font-bold tracking-tight md:text-2xl">{title}</h1>
        {sub && <p className="mt-0.5 text-sm text-neutral-500">{sub}</p>}
      </div>
      {action && <div className="shrink-0 pt-1">{action}</div>}
    </div>
  );
}
