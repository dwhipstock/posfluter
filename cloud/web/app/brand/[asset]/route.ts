import { NextResponse } from "next/server";
import { readBrandAsset } from "@/lib/brand/server";

// This instance's brand images (mark, favicon, logo) from its brand pack. Only
// the files the active pack declares are served — never another pack's, never
// any other path.
export const dynamic = "force-dynamic";

export async function GET(_req: Request, { params }: { params: Promise<{ asset: string }> }) {
  const { asset } = await params;
  const found = readBrandAsset(asset);
  if (!found) return new NextResponse("Not found", { status: 404 });
  return new NextResponse(new Uint8Array(found.body), {
    headers: { "content-type": found.type, "cache-control": "public, max-age=3600" },
  });
}
