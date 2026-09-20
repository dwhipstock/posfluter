import type { Config } from "tailwindcss";
import colors from "tailwindcss/colors";

// Tremor addresses chart colors by tailwind palette name only, so the brand
// blue brand ramp hijacks palettes the app never uses otherwise:
//   pink-500    -> primary blue     #1565C0
//   fuchsia-500 -> navy ink         #17263A
//   rose-500    -> muted blue-gray  #5B6D82
//   violet-500  -> pale blue border #C8D5E6
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
        ink: "#17263A",
        paper: "#F3F7FC",
        accent: { DEFAULT: "#1565C0", hover: "#0D4F9B" },
        neutral: {
          50: "#F8FAFD",
          100: "#E7EFF9",
          200: "#C8D5E6",
          300: "#A9BACD",
          400: "#8292A5",
          500: "#5B6D82",
          600: "#43566D",
          700: "#334861",
          800: "#24374D",
          900: "#17263A",
          950: "#0D1724",
        },
        pink: { ...colors.pink, 500: "#1565C0" },
        fuchsia: { ...colors.fuchsia, 500: "#17263A" },
        rose: { ...colors.rose, 500: "#5B6D82" },
        violet: { ...colors.violet, 500: "#C8D5E6" },
        tremor: {
          brand: {
            faint: "#F3F7FC",
            muted: "#C8DDF5",
            subtle: "#5B8FCB",
            DEFAULT: "#1565C0",
            emphasis: "#0D4F9B",
            inverted: "#FFFFFF",
          },
          background: {
            muted: "#F3F7FC",
            subtle: "#E7EFF9",
            DEFAULT: "#FFFFFF",
            emphasis: "#5B6D82",
          },
          border: { DEFAULT: "#C8D5E6" },
          ring: { DEFAULT: "#C8D5E6" },
          content: {
            subtle: "#8292A5",
            DEFAULT: "#5B6D82",
            emphasis: "#334861",
            strong: "#17263A",
            inverted: "#FFFFFF",
          },
        },
        "dark-tremor": {
          brand: {
            faint: "#0B1229",
            muted: "#5B8FCB",
            subtle: "#5B8FCB",
            DEFAULT: "#1565C0",
            emphasis: "#8AB6E8",
            inverted: "#FFFFFF",
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
