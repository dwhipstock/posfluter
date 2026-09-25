"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Layers } from "lucide-react";
import { STORE_PARAM, shortStoreName, useStores } from "@/lib/store";
import { useT } from "@/lib/i18n/context";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { cn } from "@/lib/utils";

const ALL = "__all__";

/**
 * The header store picker, on every page: "All stores" (default) or one store
 * (= that store's one POS tablet). The choice lives in ?store= so every view is
 * linkable; the rest of the query (date range, search) is kept. Each store
 * shows its chart colour. Must sit under <Suspense> (useSearchParams).
 */
export function StorePicker({ tone = "light", className }: { tone?: "light" | "dark"; className?: string }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { venues, storeId, colorOf } = useStores();

  if (venues.length < 2 && !storeId) return null;

  const pick = (value: string) => {
    const params = new URLSearchParams(searchParams.toString());
    if (value === ALL) params.delete(STORE_PARAM);
    else params.set(STORE_PARAM, value);
    params.delete("page"); // paged lists restart for a new scope
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  const dot = (id: string) => (
    <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: colorOf(id) }} aria-hidden />
  );

  return (
    <Select value={storeId ?? ALL} onValueChange={pick}>
      <SelectTrigger
        aria-label={t("store_label")}
        className={cn(
          "h-9 gap-2 text-xs font-semibold",
          tone === "dark"
            ? "border-white/20 bg-white/10 text-white focus:ring-white/25"
            : storeId
              ? "border-copper/50 bg-copper-soft text-copper-text"
              : "border-navy/30 text-navy",
          className
        )}
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL}>
          <span className="flex items-center gap-2">
            <Layers className="h-3.5 w-3.5 text-navy" />
            {t("scope_all_n", { n: venues.length })}
          </span>
        </SelectItem>
        {venues.map((v) => (
          <SelectItem key={v.id} value={v.id}>
            <span className="flex items-center gap-2">
              {dot(v.id)}
              {shortStoreName(v.name)}
            </span>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
