import type { Config } from "tailwindcss";
import colors from "tailwindcss/colors";

// The portal's colour, radius, shadow and font names resolve to CSS custom
// properties that the root layout sets from THIS client's brand pack at run
// time (lib/brand/brand.ts → brandCssVars). The class names stay the same for
// every client — `bg-navy` is "the brand's chrome colour", `copper` its one
// accent, `accent` its action colour — so one build serves every brand.
// Copper Lantern's pack (brands/copperlantern) keeps the values this file used
// to hard-code: the logo navy, a copper accent, warm cream surfaces.
const v = (name: string) => `rgb(var(${name}) / <alpha-value>)`;

const config: Config = {
  darkMode: "class", // never enabled — the portal is light-only, like the till
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["var(--font-brand)", "ui-sans-serif", "system-ui", "-apple-system", "Segoe UI", "sans-serif"],
      },
      colors: {
        ink: v("--c-ink"),
        paper: v("--c-paper"),
        surface: { DEFAULT: v("--c-surface"), alt: v("--c-surface-alt") },
        navy: { DEFAULT: v("--c-navy"), deep: v("--c-navy-deep"), muted: v("--c-navy-muted") },
        copper: { DEFAULT: v("--c-copper"), soft: v("--c-copper-soft"), text: v("--c-copper-text") },
        attention: v("--c-attention"),
        accent: { DEFAULT: v("--c-accent"), hover: v("--c-accent-hover") },
        neutral: {
          50: v("--c-n-50"),
          100: v("--c-n-100"),
          200: v("--c-n-200"),
          300: v("--c-n-300"),
          400: v("--c-n-400"),
          500: v("--c-n-500"),
          600: v("--c-n-600"),
          700: v("--c-n-700"),
          800: v("--c-n-800"),
          900: v("--c-n-900"),
          950: v("--c-n-950"),
        },
        // status colours re-stepped per brand for AA on its surfaces
        red: { ...colors.red, 600: v("--c-red-600"), 700: v("--c-red-700") },
        emerald: { ...colors.emerald, 600: v("--c-emerald-600"), 700: v("--c-emerald-700") },
      },
      borderRadius: {
        sm: "var(--radius-sm)",
        DEFAULT: "var(--radius)",
        md: "var(--radius-md)",
        lg: "var(--radius-lg)",
        xl: "var(--radius-xl)",
        "2xl": "var(--radius-2xl)",
        // buttons and inputs: pills for one brand, rounded rectangles for another
        control: "var(--radius-control)",
        "control-sm": "var(--radius-control-sm)",
        "control-lg": "var(--radius-control-lg)",
      },
      boxShadow: {
        raised: "var(--shadow-raised)",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
};

export default config;
