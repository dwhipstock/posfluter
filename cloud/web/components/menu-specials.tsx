"use client";

// The item sheet's "Specials" and "Available only on" sections (CONTRACT §10
// "Specials"). The rules and the canonical form are in lib/menu-specials.ts;
// this is only the form. Built to fit a 360 px phone: chips wrap, the price
// fields stack, nothing is wider than the sheet.
import { Plus, Trash2 } from "lucide-react";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { cad } from "@/lib/format";
import { DAYS, daysSummary, emptySpecial, notCheaper, validateSpecials, type Day, type SpecialDraft, type SpecialError } from "@/lib/menu-specials";
import type { ItemDraft } from "@/lib/menu-edit";
import { parseCents } from "@/lib/menu-edit";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type T = ReturnType<typeof useT>;

const SPECIAL_ERR: Record<SpecialError, MsgKey> = {
  special_day_required: "menu_err_special_day_required",
  special_price_required: "menu_err_special_price_required",
  special_price_invalid: "menu_err_special_price_invalid",
  special_time_both: "menu_err_special_time_both",
  special_time_same: "menu_err_special_time_same",
  special_time_invalid: "menu_err_special_time_invalid",
  special_too_many: "menu_err_special_too_many",
};

export const dayShort = (t: T, d: Day) => t(`day_${d}` as MsgKey);
export const dayFull = (t: T, d: Day) => t(`day_full_${d}` as MsgKey);

/** "Fri & Sat" in the reader's language. */
export function daysText(t: T, days: readonly string[]): string {
  return daysSummary(days, (d) => dayShort(t, d), t("days_and"), t("days_every"));
}

function DayChips({ value, onChange, label }: { value: string[]; onChange: (v: string[]) => void; label: string }) {
  const t = useT();
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-1.5">
      {DAYS.map((d) => {
        const on = value.includes(d);
        return (
          <button
            key={d}
            type="button"
            aria-pressed={on}
            aria-label={dayFull(t, d)}
            title={dayFull(t, d)}
            onClick={() => onChange(on ? value.filter((x) => x !== d) : DAYS.filter((x) => x === d || value.includes(x)))}
            className={`h-9 min-w-[2.75rem] rounded-full border px-2.5 text-xs font-medium capitalize transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-neutral-400 ${
              on ? "border-ink bg-ink text-white" : "border-neutral-300 bg-white text-neutral-700 hover:bg-neutral-50"
            }`}
          >
            {dayShort(t, d)}
          </button>
        );
      })}
    </div>
  );
}

export function SpecialsSection({
  draft,
  onChange,
  showErrors,
  sizeLabel,
}: {
  draft: ItemDraft;
  onChange: (p: Partial<ItemDraft>) => void;
  /** Show each special's problems (after a save was tried). */
  showErrors: boolean;
  /** A size's label in the reader's language. */
  sizeLabel: (v: ItemDraft["variants"][number]) => string;
}) {
  const t = useT();
  const sizes = draft.variants.filter((v) => !!v.id);
  const sizeIds = sizes.map((v) => v.id as string);
  const regular = Object.fromEntries(sizes.map((v) => [v.id as string, parseCents(v.price) ?? 0]));
  const errors = validateSpecials(draft.specials, sizeIds);
  const setSpecial = (i: number, p: Partial<SpecialDraft>) =>
    onChange({ specials: draft.specials.map((s, j) => (j === i ? { ...s, ...p } : s)) });

  return (
    <>
      <div className="space-y-2 rounded-lg border border-neutral-100 px-3 py-3">
        <div>
          <h3 className="text-sm font-semibold">{t("menu_days_only")}</h3>
          <p className="text-xs text-neutral-600">{t("menu_days_only_hint")}</p>
        </div>
        <DayChips label={t("menu_days_only")} value={draft.availableDays} onChange={(v) => onChange({ availableDays: v })} />
      </div>

      <div className="space-y-3">
        <div>
          <h3 className="text-sm font-semibold">{t("menu_specials")}</h3>
          <p className="text-xs text-neutral-600">{t("menu_specials_hint")}</p>
        </div>
        {draft.specials.map((sp, i) => {
          const warn = notCheaper(sp, regular);
          return (
            <div key={i} className="space-y-3 rounded-lg border border-neutral-200 px-3 py-3">
              <div className="flex items-center justify-between gap-2">
                <span className="min-w-0 truncate text-sm font-medium">
                  {sp.label.trim() || t("menu_special_n", { n: i + 1 })}
                  {sp.days.length > 0 && <span className="ml-2 text-xs font-normal text-neutral-600">{daysText(t, sp.days)}</span>}
                </span>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={t("menu_special_remove")}
                  title={t("menu_special_remove")}
                  onClick={() => onChange({ specials: draft.specials.filter((_, j) => j !== i) })}
                >
                  <Trash2 />
                </Button>
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs text-neutral-600">{t("menu_special_days")}</Label>
                <DayChips label={t("menu_special_days")} value={sp.days} onChange={(v) => setSpecial(i, { days: v })} />
              </div>
              <div className="space-y-1.5">
                <div className="grid grid-cols-2 gap-2">
                  <div className="min-w-0 space-y-1">
                    <Label className="text-xs text-neutral-600">{t("menu_special_from")}</Label>
                    <Input type="time" step={300} value={sp.from} onChange={(e) => setSpecial(i, { from: e.target.value })} />
                  </div>
                  <div className="min-w-0 space-y-1">
                    <Label className="text-xs text-neutral-600">{t("menu_special_to")}</Label>
                    <Input type="time" step={300} value={sp.to} onChange={(e) => setSpecial(i, { to: e.target.value })} />
                  </div>
                </div>
                <p className="text-xs text-neutral-600">{t("menu_special_time_hint")}</p>
              </div>
              <div className="space-y-1">
                <Label className="text-xs text-neutral-600">{t("menu_special_name")}</Label>
                <Input value={sp.label} maxLength={40} placeholder={t("menu_special_name_ph")} onChange={(e) => setSpecial(i, { label: e.target.value })} />
              </div>
              <div className="grid gap-2 sm:grid-cols-2">
                {sizes.map((v) => (
                  <div key={v.id} className="min-w-0 space-y-1">
                    <Label className="flex flex-wrap items-baseline justify-between gap-x-2 text-xs text-neutral-600">
                      <span className="truncate font-medium text-neutral-800">{sizeLabel(v)}</span>
                      <span className="tabular-nums">{t("menu_special_regular", { price: cad(regular[v.id as string] ?? 0) })}</span>
                    </Label>
                    <div className="relative">
                      <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-neutral-500">$</span>
                      <Input
                        className="pl-6 tabular-nums"
                        inputMode="decimal"
                        placeholder="—"
                        value={sp.prices[v.id as string] ?? ""}
                        onChange={(e) => setSpecial(i, { prices: { ...sp.prices, [v.id as string]: e.target.value } })}
                      />
                    </div>
                  </div>
                ))}
              </div>
              {warn.length > 0 && (
                <p className="text-xs text-amber-800">
                  {t("menu_special_not_cheaper", { sizes: sizes.filter((v) => warn.includes(v.id as string)).map(sizeLabel).join(", ") })}
                </p>
              )}
              {showErrors && errors[i]?.length > 0 && (
                <ul className="space-y-0.5 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-700" role="alert">
                  {errors[i].map((e) => (
                    <li key={e}>{t(SPECIAL_ERR[e])}</li>
                  ))}
                </ul>
              )}
            </div>
          );
        })}
        {draft.specials.length < 10 && (
          <Button variant="secondary" size="sm" onClick={() => onChange({ specials: [...draft.specials, emptySpecial()] })}>
            <Plus /> {t("menu_special_add")}
          </Button>
        )}
      </div>
    </>
  );
}
