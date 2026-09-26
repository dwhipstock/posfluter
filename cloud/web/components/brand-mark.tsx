"use client";

import Image from "next/image";
import { Msg } from "@/lib/i18n/context";
import { useBrand } from "@/lib/brand/context";
import { brandAssetUrl } from "@/lib/brand/brand";

// This client's mark + wordmark, from its brand pack (served at /brand/<file>).
// `large` is the sign-in hero; `tone` is the chrome it sits on: "dark" (the
// sidebar brand's navy chrome) or "light" (the top-bar brand's white bar).
// `unoptimized` because the pack's images are already sized.
export function BrandMark({
  compact = false,
  large = false,
  tone = "dark",
}: {
  compact?: boolean;
  large?: boolean;
  tone?: "dark" | "light";
}) {
  const brand = useBrand();
  if (large) {
    return (
      <Image
        src={brandAssetUrl(brand.assets.markLarge)}
        alt={brand.legalName}
        width={168}
        height={168}
        unoptimized
        className="rounded-full drop-shadow-[0_8px_24px_rgba(0,0,0,0.25)]"
        priority
      />
    );
  }
  if (tone === "light") {
    // the top-bar wordmark: the mark, the name in the brand's heavy face, a small second line
    return (
      <span className="inline-flex items-center gap-2.5" aria-label={brand.legalName}>
        <Image src={brandAssetUrl(brand.assets.mark)} alt="" width={36} height={36} unoptimized className="shrink-0 rounded-full" />
        <span className="flex flex-col leading-none">
          <span className="text-[17px] font-extrabold tracking-tight text-navy-deep">{brand.name}</span>
          {!compact && (
            <span className="mt-1 text-[10px] font-semibold uppercase tracking-[0.22em] text-copper-text">
              {brand.wordmarkSub ?? <Msg k="brand_sub" />}
            </span>
          )}
        </span>
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-2.5" aria-label={brand.name}>
      <Image
        src={brandAssetUrl(brand.assets.mark)}
        alt=""
        width={38}
        height={38}
        unoptimized
        className="shrink-0 rounded-full ring-2 ring-white/15"
      />
      <span className="flex flex-col leading-none">
        <span className="text-[13px] font-bold uppercase tracking-[0.16em]">{brand.name}</span>
        {!compact && (
          <span className="mt-1 text-[9px] font-semibold uppercase tracking-[0.32em] text-copper-soft">
            {brand.wordmarkSub ?? <Msg k="brand_sub" />}
          </span>
        )}
      </span>
    </span>
  );
}
