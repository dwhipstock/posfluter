"use client";

import { Inbox, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Card } from "@/components/ui/card";
import { useT } from "@/lib/i18n/context";

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <div className="mb-3 grid h-11 w-11 place-items-center rounded-full bg-neutral-100 text-neutral-400">
        <Inbox className="h-5 w-5" />
      </div>
      <p className="text-sm font-medium text-ink">{title}</p>
      {hint && <p className="mt-1 max-w-xs text-xs text-neutral-500">{hint}</p>}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  const t = useT();
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <p className="text-sm font-medium text-ink">{t("couldnt_load")}</p>
      <p className="mt-1 max-w-xs text-xs text-neutral-500">{message}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" className="mt-4" onClick={onRetry}>
          <RefreshCw /> {t("retry")}
        </Button>
      )}
    </div>
  );
}

export function TableSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="space-y-2.5 p-5">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
    </div>
  );
}

export function PageFallback() {
  return (
    <div className="space-y-4">
      <Skeleton className="h-8 w-40" />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-24 w-full rounded-2xl" />
        ))}
      </div>
      <Card>
        <TableSkeleton rows={6} />
      </Card>
    </div>
  );
}
