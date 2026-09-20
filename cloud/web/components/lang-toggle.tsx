"use client";

import { useI18n } from "@/lib/i18n/context";
import { cn } from "@/lib/utils";

// Compact language + calendar-era toggles, mirroring the POS app bar. Each
// button shows the CURRENT value; tapping flips it. `tone` adapts to the dark
// shell chrome vs. the light login card.
export function LangEraToggle({ tone = "light" }: { tone?: "light" | "dark" }) {
  const { locale, toggleLocale } = useI18n();

  const btn = cn(
    "rounded-md px-2 py-1 text-xs font-bold tracking-wide transition-colors",
    tone === "dark"
      ? "text-neutral-300 hover:bg-white/10 hover:text-white"
      : "text-neutral-500 hover:bg-neutral-100 hover:text-ink"
  );

  return (
    <div className="flex items-center gap-0.5">
      <button type="button" onClick={toggleLocale} className={btn} aria-label="Toggle language">
        {locale === "en" ? "EN" : "FR"}
      </button>
    </div>
  );
}

// Segmented pickers for the Account "Display" card — both options visible so
// the choice is obvious, not a mystery toggle.
export function SegmentedToggle<T extends string>({
  value,
  options,
  onChange,
  ariaLabel,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  ariaLabel: string;
}) {
  return (
    <div
      role="group"
      aria-label={ariaLabel}
      className="inline-flex rounded-lg border border-neutral-200 bg-neutral-50 p-0.5"
    >
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={cn(
            "min-w-[3rem] rounded-[7px] px-3 py-1 text-sm font-medium transition-colors",
            value === o.value ? "bg-white text-ink shadow-sm" : "text-neutral-500 hover:text-ink"
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
