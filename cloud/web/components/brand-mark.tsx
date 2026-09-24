import Image from "next/image";

export function BrandMark({ compact = false, large = false }: { compact?: boolean; large?: boolean }) {
  if (large) {
    return (
      <Image
        src="/copper-lantern-logo.png"
        alt="The Copper Lantern Pub"
        width={192}
        height={192}
        unoptimized
        className="rounded-full"
        priority
      />
    );
  }
  return (
    <span className="inline-flex items-center gap-2" aria-label="Copper Lantern Manager">
      <Image
        src="/copper-lantern-logo.png"
        alt=""
        width={36}
        height={36}
        unoptimized
        className="shrink-0 rounded-full"
      />
      <span className="flex flex-col leading-none">
        <span className="text-sm font-bold uppercase tracking-[0.16em]">Copper Lantern</span>
        {!compact && (
          <span className="mt-1 text-[8px] font-semibold uppercase tracking-[0.32em] text-blue-200">
            Manager
          </span>
        )}
      </span>
    </span>
  );
}
