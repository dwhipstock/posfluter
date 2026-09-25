// The portal's palette — the SAME values as the POS tablet's design tokens
// (client/lib/design/tokens.dart, class `T`). Change a colour there, change it
// here: the till and the owner portal are meant to look like one product.
// Consumed by tailwind.config.ts (utility classes), the charts and the exports.

export const BRAND = {
  // brand
  navy: "#17456E", // logo badge; white on it ≈ 9.9:1
  navyDeep: "#0F3150", // header bands, pressed states
  copper: "#A65A23", // white on it ≈ 5.1:1 — used sparingly
  /** Copper for small text on cream (≈ 6:1); the tablet's copper tile hue. */
  copperText: "#8C4A1C",
  accentSoft: "#F3E3D3", // copper tint for selected rows
  // surfaces: warm cream, never cool grey
  background: "#F6F0E5",
  surface: "#FFFCF7",
  surfaceAlt: "#EFE6D6",
  border: "#DCCFB9",
  textPrimary: "#1C2733", // ≈ 13:1 on background
  textMuted: "#62574B", // ≈ 6.2:1 on background
  onNavyMuted: "#C9D6E3", // secondary text on navy
  destructive: "#B3261E",
  attention: "#8C5200",
} as const;

/**
 * One colour per store, in a fixed order: a store keeps its colour on every
 * chart and page (assigned by its position in the tenant's store list, never
 * by rank). Navy and copper lead so a two-store group reads in brand colours;
 * all six pass the categorical checks (lightness band, chroma, adjacent CVD
 * ΔE ≥ 10, ≥ 3:1 on the cream surface).
 */
export const STORE_SERIES = ["#1F5F99", "#C4702A", "#2B9C7C", "#7652B0", "#B8870F", "#B03A5B"] as const;

/** Tender / category mixes inside one store: the same fixed order. */
export const SERIES = STORE_SERIES;

/** Chart chrome: recessive grid and axis text that still meets AA. */
export const CHART = {
  grid: "#E8DDCB",
  axis: "#6F6456",
  surface: BRAND.surface,
} as const;
