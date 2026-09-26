// A client's portal brand: name, logo, palette, font, shape, locales, currency
// and layout. Every client runs the same portal image; which brand it wears is
// chosen at run time (lib/brand/server.ts), from a brand pack —
// brands/<id>/brand.json plus its image files — shipped in the image or
// mounted into the container.
//
// This module is isomorphic and pure (no fs): parsing, validation, defaults
// and the CSS custom properties the Tailwind theme reads (tailwind.config.ts).

export const SUPPORTED_LOCALES = ["en", "fr", "es"] as const;
export type BrandLocale = (typeof SUPPORTED_LOCALES)[number];

/** The shell: a dark sidebar (desktop) + dark bottom bar, or a light top bar + light bottom bar. */
export type BrandLayout = "sidebar" | "topbar";
/** The sign-in page: a dark header band over a card, or a split panel. */
export type BrandLogin = "band" | "split";
export type BrandFont = "inter" | "jakarta";

export interface BrandPalette {
  /** Chrome + strong brand colour (sidebar, headings). Tailwind: `navy`. */
  primary: string;
  /** Pressed / header bands. Tailwind: `navy-deep`. */
  primaryDeep: string;
  /** Secondary text on the primary colour. Tailwind: `navy-muted`. */
  onPrimaryMuted: string;
  /** The one colour that pops, used sparingly. Tailwind: `copper`. */
  accent: string;
  /** The accent for small text on the page background. Tailwind: `copper-text`. */
  accentText: string;
  /** Accent tint for selected rows, notices. Tailwind: `copper-soft`. */
  accentSoft: string;
  /** Primary buttons, focus rings, selected chips. Tailwind: `accent`. */
  action: string;
  actionHover: string;
  background: string;
  surface: string;
  surfaceAlt: string;
  border: string;
  text: string;
  textMuted: string;
  destructive: string;
  destructiveDeep: string;
  attention: string;
  success: string;
  successDeep: string;
  /** Text selection highlight. */
  selection: string;
}

export interface BrandShape {
  /** Tailwind's rounded-sm … rounded-2xl, for this brand. */
  radius: { sm: string; base: string; md: string; lg: string; xl: string; "2xl": string };
  /** Buttons and text inputs: e.g. 9999px for pills. */
  control: string;
  controlSm: string;
  controlLg: string;
  /** Raised cards: a CSS box-shadow, or "none" for a flat look. */
  shadow: string;
}

export interface Brand {
  id: string;
  /** Short name: page titles, the manifest, the wordmark. */
  name: string;
  /** Full name, for image alt text. */
  legalName: string;
  /** Wordmark second line; absent → the localized "Manager" label. */
  wordmarkSub?: string;
  layout: BrandLayout;
  login: BrandLogin;
  font: BrandFont;
  locales: { default: BrandLocale; available: BrandLocale[] };
  /** The client's currency: the fallback when a store doesn't say. */
  currency: string;
  /** File names inside the brand pack (served at /brand/<file>). */
  assets: { mark: string; markLarge: string; icon: string; logo?: string };
  palette: BrandPalette;
  /** The warm/cool grey ramp Tailwind's neutral-50 … 950 resolve to. */
  neutral: Record<"50" | "100" | "200" | "300" | "400" | "500" | "600" | "700" | "800" | "900" | "950", string>;
  /** Chart series: one colour per store (fixed order), then tender/category mixes. */
  series: string[];
  chart: { grid: string; axis: string };
  shape: BrandShape;
}

export class BrandError extends Error {}

const HEX = /^#[0-9a-fA-F]{6}$/;
const ID = /^[a-z0-9][a-z0-9-]{0,39}$/;
const FILE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,80}\.(png|webp|svg|jpg|ico)$/;
const NEUTRAL_STEPS = ["50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "950"] as const;
const PALETTE_KEYS: (keyof BrandPalette)[] = [
  "primary", "primaryDeep", "onPrimaryMuted", "accent", "accentText", "accentSoft", "action", "actionHover",
  "background", "surface", "surfaceAlt", "border", "text", "textMuted", "destructive", "destructiveDeep",
  "attention", "success", "successDeep", "selection",
];

/** Tailwind's own radii — a brand that sets no shape looks like the stock portal. */
export const DEFAULT_SHAPE: BrandShape = {
  radius: { sm: "0.125rem", base: "0.25rem", md: "0.375rem", lg: "0.5rem", xl: "0.75rem", "2xl": "1rem" },
  control: "0.5rem",
  controlSm: "0.375rem",
  controlLg: "0.75rem",
  shadow: "none",
};

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);

function str(o: Obj, key: string, where: string, re?: RegExp): string {
  const v = o[key];
  if (typeof v !== "string" || !v.trim()) throw new BrandError(`${where}.${key}: required text`);
  if (re && !re.test(v)) throw new BrandError(`${where}.${key}: "${v}" is not valid`);
  return v;
}

function oneOf<T extends string>(o: Obj, key: string, allowed: readonly T[], fallback: T, where: string): T {
  const v = o[key];
  if (v === undefined) return fallback;
  if (typeof v !== "string" || !(allowed as readonly string[]).includes(v)) {
    throw new BrandError(`${where}.${key}: one of ${allowed.join(", ")}`);
  }
  return v as T;
}

/** A CSS length (or "none"/a shadow) — no braces, semicolons or url(): it is inlined into a <style>. */
const CSS_SAFE = /^[A-Za-z0-9 .,#%()\-]+$/;
function cssValue(v: unknown, where: string, fallback: string): string {
  if (v === undefined) return fallback;
  if (typeof v !== "string" || !CSS_SAFE.test(v) || /url\s*\(/i.test(v)) throw new BrandError(`${where}: not a safe CSS value`);
  return v;
}

/** Validate a brand.json and fill in defaults. Throws BrandError with the offending path. */
export function parseBrand(raw: unknown): Brand {
  if (!isObj(raw)) throw new BrandError("brand.json: expected an object");
  const id = str(raw, "id", "brand", ID);
  const name = str(raw, "name", "brand");
  const legalName = typeof raw.legalName === "string" && raw.legalName.trim() ? raw.legalName : name;
  const wordmarkSub = typeof raw.wordmarkSub === "string" && raw.wordmarkSub.trim() ? raw.wordmarkSub : undefined;

  const loc = isObj(raw.locales) ? raw.locales : {};
  const available = Array.isArray(loc.available) ? loc.available : ["en"];
  if (!available.length || !available.every((l) => (SUPPORTED_LOCALES as readonly unknown[]).includes(l))) {
    throw new BrandError(`brand.locales.available: a list of ${SUPPORTED_LOCALES.join(", ")}`);
  }
  const def = loc.default ?? available[0];
  if (!available.includes(def)) throw new BrandError("brand.locales.default: must be one of locales.available");

  const currency = typeof raw.currency === "string" ? raw.currency.toUpperCase() : "CAD";
  if (!/^[A-Z]{3}$/.test(currency)) throw new BrandError("brand.currency: a 3-letter code");

  const a = isObj(raw.assets) ? raw.assets : {};
  const assets = {
    mark: str(a, "mark", "brand.assets", FILE),
    markLarge: str(a, "markLarge", "brand.assets", FILE),
    icon: str(a, "icon", "brand.assets", FILE),
    logo: a.logo === undefined ? undefined : str(a, "logo", "brand.assets", FILE),
  };

  const p = isObj(raw.palette) ? raw.palette : {};
  const palette = {} as BrandPalette;
  for (const k of PALETTE_KEYS) palette[k] = str(p, k, "brand.palette", HEX);

  const n = isObj(raw.neutral) ? raw.neutral : {};
  const neutral = {} as Brand["neutral"];
  for (const k of NEUTRAL_STEPS) neutral[k] = str(n, k, "brand.neutral", HEX);

  const series = Array.isArray(raw.series) ? raw.series : [];
  if (series.length < 2 || !series.every((c) => typeof c === "string" && HEX.test(c))) {
    throw new BrandError("brand.series: at least two #rrggbb colours");
  }
  const c = isObj(raw.chart) ? raw.chart : {};
  const chart = { grid: str(c, "grid", "brand.chart", HEX), axis: str(c, "axis", "brand.chart", HEX) };

  const s = isObj(raw.shape) ? raw.shape : {};
  const r = isObj(s.radius) ? s.radius : {};
  const shape: BrandShape = {
    radius: {
      sm: cssValue(r.sm, "brand.shape.radius.sm", DEFAULT_SHAPE.radius.sm),
      base: cssValue(r.base, "brand.shape.radius.base", DEFAULT_SHAPE.radius.base),
      md: cssValue(r.md, "brand.shape.radius.md", DEFAULT_SHAPE.radius.md),
      lg: cssValue(r.lg, "brand.shape.radius.lg", DEFAULT_SHAPE.radius.lg),
      xl: cssValue(r.xl, "brand.shape.radius.xl", DEFAULT_SHAPE.radius.xl),
      "2xl": cssValue(r["2xl"], "brand.shape.radius.2xl", DEFAULT_SHAPE.radius["2xl"]),
    },
    control: cssValue(s.control, "brand.shape.control", DEFAULT_SHAPE.control),
    controlSm: cssValue(s.controlSm, "brand.shape.controlSm", DEFAULT_SHAPE.controlSm),
    controlLg: cssValue(s.controlLg, "brand.shape.controlLg", DEFAULT_SHAPE.controlLg),
    shadow: cssValue(s.shadow, "brand.shape.shadow", DEFAULT_SHAPE.shadow),
  };

  return {
    id,
    name,
    legalName,
    wordmarkSub,
    layout: oneOf(raw, "layout", ["sidebar", "topbar"] as const, "sidebar", "brand"),
    login: oneOf(raw, "login", ["band", "split"] as const, "band", "brand"),
    font: oneOf(raw, "font", ["inter", "jakarta"] as const, "inter", "brand"),
    locales: { default: def as BrandLocale, available: available as BrandLocale[] },
    currency,
    assets,
    palette,
    neutral,
    series: series as string[],
    chart,
    shape,
  };
}

/** "#17456E" → "23 69 110": the channel triplet Tailwind's `rgb(var(--x) / <alpha>)` needs. */
export function hexChannels(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

/** The custom properties tailwind.config.ts maps its colour, radius, shadow and font names onto. */
export function brandCssVars(b: Brand): Record<string, string> {
  const p = b.palette;
  const vars: Record<string, string> = {
    "--c-ink": hexChannels(p.text),
    "--c-paper": hexChannels(p.background),
    "--c-surface": hexChannels(p.surface),
    "--c-surface-alt": hexChannels(p.surfaceAlt),
    "--c-navy": hexChannels(p.primary),
    "--c-navy-deep": hexChannels(p.primaryDeep),
    "--c-navy-muted": hexChannels(p.onPrimaryMuted),
    "--c-copper": hexChannels(p.accent),
    "--c-copper-soft": hexChannels(p.accentSoft),
    "--c-copper-text": hexChannels(p.accentText),
    "--c-attention": hexChannels(p.attention),
    "--c-accent": hexChannels(p.action),
    "--c-accent-hover": hexChannels(p.actionHover),
    "--c-red-600": hexChannels(p.destructive),
    "--c-red-700": hexChannels(p.destructiveDeep),
    "--c-emerald-600": hexChannels(p.success),
    "--c-emerald-700": hexChannels(p.successDeep),
    "--c-border": hexChannels(p.border),
    "--c-selection": p.selection,
    "--radius-sm": b.shape.radius.sm,
    "--radius": b.shape.radius.base,
    "--radius-md": b.shape.radius.md,
    "--radius-lg": b.shape.radius.lg,
    "--radius-xl": b.shape.radius.xl,
    "--radius-2xl": b.shape.radius["2xl"],
    "--radius-control": b.shape.control,
    "--radius-control-sm": b.shape.controlSm,
    "--radius-control-lg": b.shape.controlLg,
    "--shadow-raised": b.shape.shadow,
    "--font-brand": b.font === "jakarta" ? "var(--font-jakarta)" : "var(--font-inter)",
  };
  for (const [step, hex] of Object.entries(b.neutral)) vars[`--c-n-${step}`] = hexChannels(hex);
  return vars;
}

/** `:root { … }` for the root layout's inline <style>. Values were validated by parseBrand. */
export function brandCss(b: Brand): string {
  const body = Object.entries(brandCssVars(b))
    .map(([k, v]) => `${k}:${v}`)
    .join(";");
  return `:root{${body}}`;
}

/** The locale to render: the visitor's saved choice when this client offers it, else the client's default. */
export function pickLocale(b: Pick<Brand, "locales">, saved: string | undefined): BrandLocale {
  return saved && (b.locales.available as string[]).includes(saved) ? (saved as BrandLocale) : b.locales.default;
}

/** Public URL of a brand pack file. */
export const brandAssetUrl = (file: string) => `/brand/${file}`;
