"use client";

import { createContext, useContext } from "react";
import type { Brand } from "./brand";

// The brand this portal instance wears, handed down from the root layout
// (read on the server from the brand pack). Client code never imports any
// brand's name, colours or logo directly: it reads them from here, so the
// browser bundle is the same for every client and names none of them.
const Ctx = createContext<Brand | null>(null);

export function BrandProvider({ brand, children }: { brand: Brand; children: React.ReactNode }) {
  return <Ctx.Provider value={brand}>{children}</Ctx.Provider>;
}

export function useBrand(): Brand {
  const b = useContext(Ctx);
  if (!b) throw new Error("useBrand must be used within <BrandProvider>");
  return b;
}

/** Chart colours: store series (fixed order), grid and axis, the surface behind a slice. */
export function useChartTheme() {
  const b = useBrand();
  return {
    series: b.series,
    grid: b.chart.grid,
    axis: b.chart.axis,
    surface: b.palette.surface,
    text: b.palette.text,
  };
}
