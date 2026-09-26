// Server-only: which brand this portal instance wears, read at run time.
//
//   PORTAL_BRAND=<id>       a brand pack shipped in the image: brands/<id>/
//   PORTAL_BRAND_DIR=<dir>  a brand pack mounted into the container (wins)
//
// Unset → "copperlantern", the first client (a deployment that predates brand
// packs keeps its look). A broken or missing pack fails loudly: a portal must
// never silently wear another client's brand.
//
// Never import this from a client component: it reads the filesystem.

import fs from "node:fs";
import path from "node:path";
import { parseBrand, BrandError, type Brand } from "./brand";

export const DEFAULT_BRAND = "copperlantern";
const ID = /^[a-z0-9][a-z0-9-]{0,39}$/;

/** The brand pack directory for this environment. */
export function brandDir(env: Record<string, string | undefined> = process.env, root = process.cwd()): string {
  const mounted = env.PORTAL_BRAND_DIR?.trim();
  if (mounted) return path.resolve(mounted);
  const id = env.PORTAL_BRAND?.trim() || DEFAULT_BRAND;
  if (!ID.test(id)) throw new BrandError(`PORTAL_BRAND "${id}": lowercase letters, digits and dashes`);
  return path.join(root, "brands", id);
}

/** Read and validate the brand pack at [dir]. */
export function loadBrandFrom(dir: string): Brand {
  const file = path.join(dir, "brand.json");
  let raw: unknown;
  try {
    raw = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (e) {
    throw new BrandError(`brand pack ${file}: ${(e as Error).message}`);
  }
  const brand = parseBrand(raw);
  for (const f of [brand.assets.mark, brand.assets.markLarge, brand.assets.icon, brand.assets.logo]) {
    if (f && !fs.existsSync(path.join(dir, f))) throw new BrandError(`brand pack ${dir}: ${f} is missing`);
  }
  return brand;
}

let cached: { dir: string; brand: Brand } | null = null;

/** This instance's brand (read once per process). */
export function getBrand(): Brand {
  const dir = brandDir();
  if (cached?.dir !== dir) cached = { dir, brand: loadBrandFrom(dir) };
  return cached.brand;
}

const TYPES: Record<string, string> = {
  ".png": "image/png",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".jpg": "image/jpeg",
  ".ico": "image/x-icon",
};

/** One of THIS brand's declared asset files, or null — never any other path. */
export function readBrandAsset(file: string): { body: Buffer; type: string } | null {
  const brand = getBrand();
  const declared = [brand.assets.mark, brand.assets.markLarge, brand.assets.icon, brand.assets.logo];
  if (!declared.includes(file)) return null;
  const type = TYPES[path.extname(file).toLowerCase()];
  if (!type) return null;
  return { body: fs.readFileSync(path.join(brandDir(), file)), type };
}
