import type { Config } from "tailwindcss";
import colors from "tailwindcss/colors";

// Tremor addresses chart colors by tailwind palette name only, so the brand
// mono ramp hijacks palettes the app never uses otherwise:
//   pink-500    -> brand hot pink   #FF1F8E
//   fuchsia-500 -> near-black       #18181B
//   rose-500    -> mid gray         #A1A1AA
//   violet-500  -> light gray       #D4D4D8
// Keep DONUT_HEX in app code in sync with these.
const config: Config = {
  darkMode: "class", // never enabled — forces light theme even on dark-mode phones
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
    "./node_modules/@tremor/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        ink: "#0A0A0A",
        paper: "#FAFAFA",
        accent: { DEFAULT: "#FF1F8E", hover: "#E5127D" },
        pink: { ...colors.pink, 500: "#FF1F8E" },
        fuchsia: { ...colors.fuchsia, 500: "#18181B" },
        rose: { ...colors.rose, 500: "#A1A1AA" },
        violet: { ...colors.violet, 500: "#D4D4D8" },
        tremor: {
          brand: {
            faint: "#FFF0F7",
            muted: "#FFC2E0",
            subtle: "#FF66B2",
            DEFAULT: "#FF1F8E",
            emphasis: "#E5127D",
            inverted: "#0A0A0A",
          },
          background: {
            muted: "#FAFAFA",
            subtle: "#F4F4F5",
            DEFAULT: "#FFFFFF",
            emphasis: "#3F3F46",
          },
          border: { DEFAULT: "#E5E5E5" },
          ring: { DEFAULT: "#E5E5E5" },
          content: {
            subtle: "#A1A1AA",
            DEFAULT: "#71717A",
            emphasis: "#3F3F46",
            strong: "#0A0A0A",
            inverted: "#FFFFFF",
          },
        },
        "dark-tremor": {
          brand: {
            faint: "#0B1229",
            muted: "#FF66B2",
            subtle: "#FF66B2",
            DEFAULT: "#FF1F8E",
            emphasis: "#FF66B2",
            inverted: "#0A0A0A",
          },
          background: {
            muted: "#131A2B",
            subtle: "#1F2937",
            DEFAULT: "#111827",
            emphasis: "#D1D5DB",
          },
          border: { DEFAULT: "#1F2937" },
          ring: { DEFAULT: "#1F2937" },
          content: {
            subtle: "#4B5563",
            DEFAULT: "#6B7280",
            emphasis: "#E5E7EB",
            strong: "#F9FAFB",
            inverted: "#000000",
          },
        },
      },
      boxShadow: {
        "tremor-input": "0 1px 2px 0 rgb(0 0 0 / 0.05)",
        "tremor-card": "0 1px 3px 0 rgb(0 0 0 / 0.1), 0 1px 2px -1px rgb(0 0 0 / 0.1)",
        "tremor-dropdown": "0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)",
        "dark-tremor-input": "0 1px 2px 0 rgb(0 0 0 / 0.05)",
        "dark-tremor-card": "0 1px 3px 0 rgb(0 0 0 / 0.1), 0 1px 2px -1px rgb(0 0 0 / 0.1)",
        "dark-tremor-dropdown": "0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)",
      },
      borderRadius: {
        "tremor-small": "0.375rem",
        "tremor-default": "0.5rem",
        "tremor-full": "9999px",
      },
      fontSize: {
        "tremor-label": ["0.75rem", { lineHeight: "1rem" }],
        "tremor-default": ["0.875rem", { lineHeight: "1.25rem" }],
        "tremor-title": ["1.125rem", { lineHeight: "1.75rem" }],
        "tremor-metric": ["1.875rem", { lineHeight: "2.25rem" }],
      },
    },
  },
  safelist: [
    {
      pattern:
        /^(bg|text|stroke|fill|border|ring)-(pink|fuchsia|rose|violet|zinc|neutral|stone|gray)-(50|100|200|300|400|500|600|700|800|900|950)$/,
      variants: ["hover", "ui-selected"],
    },
  ],
  plugins: [require("@headlessui/tailwindcss"), require("tailwindcss-animate")],
};

export default config;
