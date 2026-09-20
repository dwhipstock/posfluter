"use client";

import useSWR, { type SWRConfiguration } from "swr";
import { useSearchParams } from "next/navigation";
import { get } from "./api";
import { rangeFromParams, type DateRange } from "./range";
import type { Me } from "./types";

export function useApi<T>(key: string | null, config?: SWRConfiguration<T>) {
  return useSWR<T>(key, (k: string) => get<T>(k), { keepPreviousData: true, ...config });
}

export function useMe() {
  return useSWR<Me>("/v1/auth/me", (k: string) => get<Me>(k), {
    shouldRetryOnError: false,
    revalidateOnFocus: false,
  });
}

// Callers must sit under <Suspense> (useSearchParams).
export function useRange(): DateRange {
  const sp = useSearchParams();
  return rangeFromParams(sp);
}

export function reportKey(path: string, r: DateRange): string {
  return `${path}?from=${r.from}&to=${r.to}`;
}
