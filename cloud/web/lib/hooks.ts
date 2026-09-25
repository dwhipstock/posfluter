"use client";

import { useRef } from "react";
import useSWR, { type SWRConfiguration } from "swr";
import { useSearchParams } from "next/navigation";
import { get, getBackground } from "./api";
import { rangeFromParams, type DateRange } from "./range";
import { scopeApiPath, useStoreId } from "./store";
import type { Me } from "./types";

/**
 * Data hook for portal pages. Store-scoped routes (reports, menu, staff)
 * automatically follow the header's store picker: `?store=<id>` in the page
 * URL becomes `venue=<id>` on the API call; no store = all stores combined.
 * Callers must sit under <Suspense> (useSearchParams) — the app shell does that.
 *
 * With `refreshInterval` (auto-refresh), only the first load of a key counts as
 * user activity; every later revalidation is sent as a background request so an
 * open, untouched tab still hits the session's idle timeout.
 */
export function useApi<T>(key: string | null, config?: SWRConfiguration<T>) {
  const storeId = useStoreId();
  const scoped = key === null ? null : scopeApiPath(key, storeId);
  const polls = !!config?.refreshInterval;
  const loaded = useRef<string | null>(null);
  const fetcher = (k: string) => {
    if (!polls) return get<T>(k);
    const background = loaded.current === k;
    loaded.current = k;
    return background ? getBackground<T>(k) : get<T>(k);
  };
  return useSWR<T>(scoped, fetcher, { keepPreviousData: true, ...config });
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
