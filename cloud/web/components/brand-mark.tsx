"use client";

import Image from "next/image";
import { Msg } from "@/lib/i18n/context";

// The round lantern badge (client/assets/copper_lantern_logo.png, the tablet's
// circle asset) served as pre-sized WebP: 192px for the shell, 384px for the
// login hero (2x for retina). `unoptimized` because they are already optimised.
export function BrandMark({ compact = false, large = false }: { compact?: boolean; large?: boolean }) {
  if (large) {
    return (
      <Image
        src="/lantern-badge-384.webp"
        alt="The Copper Lantern Pub"
        width={168}
        height={168}
        unoptimized
        className="rounded-full drop-shadow-[0_8px_24px_rgba(0,0,0,0.25)]"
        priority
      />
    );
  }
  return (
    <span className="inline-flex items-center gap-2.5" aria-label="Copper Lantern">
      <Image
        src="/lantern-badge-192.webp"
        alt=""
        width={38}
        height={38}
        unoptimized
        className="shrink-0 rounded-full ring-2 ring-white/15"
      />
      <span className="flex flex-col leading-none">
        <span className="text-[13px] font-bold uppercase tracking-[0.16em]">Copper Lantern</span>
        {!compact && (
          <span className="mt-1 text-[9px] font-semibold uppercase tracking-[0.32em] text-copper-soft">
            <Msg k="brand_sub" />
          </span>
        )}
      </span>
    </span>
  );
}
