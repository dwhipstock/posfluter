import type { Config } from "tailwindcss";
import colors from "tailwindcss/colors";
import { BRAND } from "./lib/theme";

// One palette with the POS tablet (client/lib/design/tokens.dart), via
// lib/theme.ts: the logo's navy as the primary, a copper accent kept for the
// few things that must pop, warm cream surfaces. `neutral` is a warm ramp, so
// the existing neutral-* classes read as cream/umber instead of cool grey.
// Every text step used on cream (400 and darker) meets WCAG AA (≥ 4.5:1).
const config: Config = {
  darkMode: "class", // never enabled — the portal is light-only, like the till
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["var(--font-inter)", "ui-sans-serif", "system-ui", "-apple-system", "Segoe UI", "sans-serif"],
      },
      colors: {
        ink: BRAND.textPrimary,
        paper: BRAND.background,
        surface: { DEFAULT: BRAND.surface, alt: BRAND.surfaceAlt },
        navy: { DEFAULT: BRAND.navy, deep: BRAND.navyDeep, muted: BRAND.onNavyMuted },
        copper: { DEFAULT: BRAND.copper, soft: BRAND.accentSoft, text: BRAND.copperText },
        attention: BRAND.attention,
        // primary actions, focus rings, selected chips: the logo navy
        accent: { DEFAULT: BRAND.navy, hover: BRAND.navyDeep },
        neutral: {
          50: "#FBF7F0",
          100: "#F2EADC",
          200: BRAND.border,
          300: "#BFAF95",
          400: "#6F6456",
          500: BRAND.textMuted,
          600: "#51473C",
          700: "#3E362D",
          800: "#2B2520",
          900: BRAND.textPrimary,
          950: "#121A23",
        },
        // status colours re-stepped for AA on cream
        red: { ...colors.red, 600: BRAND.destructive, 700: "#8F1E18" },
        emerald: { ...colors.emerald, 600: "#2F6B45", 700: "#245236" },
      },
      boxShadow: {
        raised: "0 3px 10px rgba(23, 69, 110, 0.10)",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
};

export default config;
