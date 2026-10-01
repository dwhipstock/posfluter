import type { NextConfig } from "next";

// Dev: Next proxies /v1 + /health to the local API. Prod: Caddy routes /v1
// before Next sees it; the rewrite is a fallback, so compose can point it at
// the api container via API_ORIGIN.
const API_ORIGIN = process.env.API_ORIGIN ?? "http://localhost:8081";

const nextConfig: NextConfig = {
  output: "standalone",
  // the dev-only "N" badge sat over the sidebar footer; prod never shows it
  devIndicators: false,
  // stray lockfiles above the repo otherwise hijack the tracing root
  outputFileTracingRoot: __dirname,
  // security headers on every portal page (red-team): no framing
  // (clickjacking), no MIME sniffing. Caddy sets the same in production.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        ],
      },
    ];
  },
  async rewrites() {
    return [
      { source: "/v1/:path*", destination: `${API_ORIGIN}/v1/:path*` },
      { source: "/health", destination: `${API_ORIGIN}/health` },
    ];
  },
};

export default nextConfig;
