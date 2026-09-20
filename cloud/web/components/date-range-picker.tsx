"use client";

import { useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { PRESETS, presetKeyOf, rangeFromParams, type DateRange } from "@/lib/range";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { cn } from "@/lib/utils";

// Range lives in ?from&to so report links are shareable. Must sit under
// <Suspense> (useSearchParams).
export function DateRangePicker() {
  const router = useRouter();
  const pathname = usePathname();
  const t = useT();
  const searchParams = useSearchParams();
  const range = rangeFromParams(searchParams);
  const activeKey = presetKeyOf(range);
  const [customOpen, setCustomOpen] = useState(activeKey === null);

  const apply = (r: DateRange) => {
    const params = new URLSearchParams(searchParams.toString());
    params.set("from", r.from);
    params.set("to", r.to);
    params.delete("page");
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  };

  const chip = (label: string, active: boolean, onClick: () => void) => (
    <button
      key={label}
      onClick={onClick}
      className={cn(
        "shrink-0 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors",
        active
          ? "border-accent bg-accent text-black"
          : "border-neutral-200 bg-white text-neutral-600 hover:border-neutral-300 hover:text-ink"
      )}
    >
      {label}
    </button>
  );

  return (
    <div className="space-y-2">
      <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4 md:mx-0 md:flex-wrap md:px-0">
        {PRESETS.map((p) =>
          chip(t(`preset_${p.key}` as MsgKey), !customOpen && activeKey === p.key, () => {
            setCustomOpen(false);
            apply(p.range());
          })
        )}
        {chip(t("preset_custom"), customOpen || activeKey === null, () => setCustomOpen((v) => !v))}
      </div>
      {(customOpen || activeKey === null) && (
        <div className="flex items-center gap-2">
          <input
            type="date"
            value={range.from}
            max={range.to}
            onChange={(e) => e.target.value && apply({ from: e.target.value, to: range.to })}
            className="h-9 rounded-lg border border-neutral-200 bg-white px-2.5 text-xs text-ink focus:outline-none focus:ring-2 focus:ring-accent/25"
          />
          <span className="text-xs text-neutral-400">{t("to")}</span>
          <input
            type="date"
            value={range.to}
            min={range.from}
            onChange={(e) => e.target.value && apply({ from: range.from, to: e.target.value })}
            className="h-9 rounded-lg border border-neutral-200 bg-white px-2.5 text-xs text-ink focus:outline-none focus:ring-2 focus:ring-accent/25"
          />
        </div>
      )}
    </div>
  );
}
