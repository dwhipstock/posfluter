"use client";

import useSWR, { type SWRConfiguration } from "swr";
import { useSearchParams } from "next/navigation";
import { get } from "./api";
import { rangeFromParams, type DateRange } from "./range";
import { scopeApiPath, useStoreId } from "./store";
import type { Me } from "./types";

/**
 * Data hook for portal pages. Store-scoped routes (reports, menu, staff)
 * automatically follow the header's store picker: `?store=<id>` in the page
 * URL becomes `venue=<id>` on the API call; no store = all stores combined.
 * Callers must sit under <Suspense> (useSearchParams) — the app shell does that.
 */
export function useApi<T>(key: string | null, config?: SWRConfiguration<T>) {
  const storeId = useStoreId();
  const scoped = key === null ? null : scopeApiPath(key, storeId);
  return useSWR<T>(scoped, (k: string) => get<T>(k), { keepPreviousData: true, ...config });
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
