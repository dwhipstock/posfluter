"use client";

import { useCallback, useMemo } from "react";
import { useSearchParams } from "next/navigation";
import useSWR from "swr";
import { get } from "./api";
import type { Venue, VenuesResponse } from "./types";
import { STORE_SERIES } from "./theme";

// The store picker lives in the URL (?store=<venueId>) so every view is
// linkable. No param = "All stores": the same page, combined across the
// tenant's stores. Callers must sit under <Suspense> (useSearchParams).
export const STORE_PARAM = "store";

/** API routes whose data is store-scoped; they get `venue=<id>` when a store is picked. */
const SCOPED_API = ["/v1/reports", "/v1/menu", "/v1/staff", "/v1/devices", "/v1/stock"];

export function useStoreId(): string | null {
  const sp = useSearchParams();
  const id = sp.get(STORE_PARAM);
  return id && id.trim() ? id : null;
}

/** Add the picked store to a store-scoped API path (others pass through unchanged). */
export function scopeApiPath(path: string, storeId: string | null): string {
  if (!storeId || !SCOPED_API.some((p) => path.startsWith(p)) || /[?&]venue=/.test(path)) return path;
  return `${path}${path.includes("?") ? "&" : "?"}venue=${encodeURIComponent(storeId)}`;
}

/** Keep the picked store on in-app links (nav, report cards, "see all"). */
export function useStoreHref(): (href: string) => string {
  const storeId = useStoreId();
  return useCallback(
    (href: string) => {
      if (!storeId) return href;
      const [path, query = ""] = href.split("?");
      const params = new URLSearchParams(query);
      params.set(STORE_PARAM, storeId);
      return `${path}?${params.toString()}`;
    },
    [storeId]
  );
}

export function useVenues() {
  return useSWR<VenuesResponse>("/v1/venues", (k: string) => get<VenuesResponse>(k), {
    revalidateOnFocus: false,
  });
}

/** Store display names by id, plus the picked store (if any). */
export function useStores(): {
  venues: Venue[];
  storeId: string | null;
  store: Venue | null;
  /** True in "All stores" mode with more than one store — show per-store labels/breakdowns. */
  combined: boolean;
  nameOf: (venueId: string) => string;
  /** The store's fixed chart colour (its position in the tenant's list, never its rank). */
  colorOf: (venueId: string) => string;
} {
  const storeId = useStoreId();
  const { data } = useVenues();
  return useMemo(() => {
    const venues = data?.venues ?? [];
    const byId = new Map(venues.map((v) => [v.id, v]));
    const index = new Map(venues.map((v, i) => [v.id, i]));
    return {
      venues,
      storeId,
      store: storeId ? byId.get(storeId) ?? null : null,
      combined: !storeId && venues.length > 1,
      nameOf: (id: string) => shortStoreName(byId.get(id)?.name ?? id),
      colorOf: (id: string) => STORE_SERIES[(index.get(id) ?? 0) % STORE_SERIES.length],
    };
  }, [data, storeId]);
}

/** "Copper Lantern — Plateau" → "plateau": a filename-safe slug. */
export function slugify(name: string): string {
  return (
    name
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "") || "store"
  );
}

/** "Copper Lantern — Plateau" → "Plateau": the part that tells stores apart. */
export function shortStoreName(name: string): string {
  const parts = name.split(/\s+[—–-]\s+/);
  return parts.length > 1 ? parts[parts.length - 1] : name;
}
