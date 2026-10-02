"use client";

// Edit item / Add item → "Stores": a tick box per store of the client, the
// ones that carry the item ticked. Ticking puts the item on that store on
// Save (a copy under the same id), unticking takes it off that store only.
// The plan and its calls are in lib/menu-stores.ts; the sheet runs them.
import { AlertTriangle, Clock } from "lucide-react";
import { useI18n, useT } from "@/lib/i18n/context";
import { canToggle, type Ticks } from "@/lib/menu-stores";
import { shortStoreName } from "@/lib/store";
import type { MenuItemStore } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";

export function StoresSection({
  stores,
  ticks,
  onToggle,
  ask,
  picked,
  onPick,
  missing,
  readOnly,
  isNew,
}: {
  /** Undefined while loading. */
  stores: MenuItemStore[] | undefined;
  ticks: Ticks | null;
  onToggle: (venueId: string) => void;
  /** Stores the item is going to that have no matching category: the manager picks one there. */
  ask: string[];
  picked: Record<string, string>;
  onPick: (venueId: string, categoryId: string) => void;
  /** Stores whose category is still to pick (after a Save attempt). */
  missing: string[];
  readOnly: boolean;
  isNew: boolean;
}) {
  const t = useT();
  const { name } = useI18n();
  return (
    <section className="space-y-2" aria-labelledby="item-stores-title">
      <div>
        <h3 id="item-stores-title" className="text-sm font-semibold">
          {t("menu_stores")}
        </h3>
        {!readOnly && <p className="text-xs text-neutral-600">{t("menu_stores_hint")}</p>}
      </div>
      {!stores || !ticks ? (
        <Skeleton className="h-24 w-full rounded-lg" />
      ) : (
        <div className="space-y-1.5">
          {stores.map((s) => {
            const store = shortStoreName(s.name);
            const on = !!ticks[s.venueId];
            const disabled = readOnly || !canToggle(s);
            const adding = on && !s.carries && !isNew;
            const removing = !on && s.carries;
            const lines: { warn: boolean; text: string }[] = [];
            if (!s.editable) lines.push({ warn: true, text: t("menu_store_outdated", { store }) });
            else {
              if (s.failed && s.failed > 0) lines.push({ warn: true, text: t("menu_apply_failed", { store, n: s.failed }) });
              if (s.pending > 0) lines.push({ warn: false, text: t("menu_pending", { store, n: s.pending }) });
            }
            const asking = on && ask.includes(s.venueId);
            const err = missing.includes(s.venueId);
            return (
              <div
                key={s.venueId}
                className={`rounded-lg border px-3 py-2.5 ${on ? "border-neutral-300 bg-surface" : "border-neutral-100 bg-neutral-50"}`}
              >
                <label className={`flex items-start gap-3 ${disabled ? "" : "cursor-pointer"}`}>
                  <input
                    type="checkbox"
                    className="mt-0.5 h-5 w-5 shrink-0 accent-[var(--c-accent)] sm:h-4 sm:w-4"
                    checked={on}
                    disabled={disabled}
                    onChange={() => onToggle(s.venueId)}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <span className="break-words text-sm font-medium text-ink">{store}</span>
                      {adding && <Badge variant="copper">{t("menu_stores_adding")}</Badge>}
                      {removing && <Badge variant="warning">{t("menu_stores_removing")}</Badge>}
                    </span>
                    {lines.map((l, i) => (
                      <span key={i} className="mt-0.5 flex items-start gap-1.5 text-xs text-neutral-700">
                        {l.warn ? (
                          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-700" />
                        ) : (
                          <Clock className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                        )}
                        {l.text}
                      </span>
                    ))}
                  </span>
                </label>
                {asking && (
                  <div className="mt-2 space-y-1.5 pl-8 sm:pl-7">
                    <p className={`text-xs ${err ? "text-red-700" : "text-neutral-700"}`}>{t("menu_stores_category_missing", { store })}</p>
                    <Label className="sr-only">{t("menu_stores_category_at", { store })}</Label>
                    <Select value={picked[s.venueId] ?? ""} onValueChange={(v) => onPick(s.venueId, v)}>
                      <SelectTrigger aria-label={t("menu_stores_category_at", { store })} aria-invalid={err || undefined}>
                        <SelectValue placeholder={t("menu_stores_category_pick")} />
                      </SelectTrigger>
                      <SelectContent>
                        {s.categories.map((c) => (
                          <SelectItem key={c.id} value={c.id}>
                            {name(c.nameFr, c.nameEn, c.names)}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
      {!readOnly && <p className="rounded-lg bg-neutral-50 px-3 py-2 text-xs text-neutral-700">{t("menu_stores_price_note")}</p>}
    </section>
  );
}
