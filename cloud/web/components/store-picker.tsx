"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Store } from "lucide-react";
import { STORE_PARAM, shortStoreName, useStores } from "@/lib/store";
import { useT } from "@/lib/i18n/context";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { cn } from "@/lib/utils";

const ALL = "__all__";

/**
 * The header store picker, on every page: "All stores" (default) or one store.
 * The choice lives in ?store= so every view is linkable; the rest of the query
 * (date range, search) is kept. Must sit under <Suspense> (useSearchParams).
 */
export function StorePicker({ tone = "light", className }: { tone?: "light" | "dark"; className?: string }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { venues, storeId } = useStores();

  if (venues.length < 2 && !storeId) return null;

  const pick = (value: string) => {
    const params = new URLSearchParams(searchParams.toString());
    if (value === ALL) params.delete(STORE_PARAM);
    else params.set(STORE_PARAM, value);
    params.delete("page"); // paged lists restart for a new scope
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  return (
    <Select value={storeId ?? ALL} onValueChange={pick}>
      <SelectTrigger
        aria-label={t("store_label")}
        className={cn(
          "h-9 gap-2 text-xs font-medium",
          tone === "dark" && "border-white/15 bg-white/5 text-white focus:ring-white/20",
          className
        )}
      >
        <Store className={cn("h-3.5 w-3.5 shrink-0", tone === "dark" ? "text-neutral-400" : "text-neutral-500")} />
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL}>{t("store_all")}</SelectItem>
        {venues.map((v) => (
          <SelectItem key={v.id} value={v.id}>
            {shortStoreName(v.name)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
