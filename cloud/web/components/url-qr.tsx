// React in scope: the tsx test runner compiles JSX with the classic transform.
import * as React from "react";
import { QRCodeSVG } from "qrcode.react";
import { cn } from "../lib/utils";

/**
 * A link a person should open, shown two ways: a scannable QR code (black on
 * a white card, 200px or more) and the URL itself as selectable text. The QR
 * always encodes exactly `url`, the string printed under it — the same rule
 * as the POS's UrlQr widget. Not for internal API addresses.
 */
export function UrlQr({ url, size = 200, className }: { url: string; size?: number; className?: string }) {
  return (
    <figure className={cn("flex flex-col items-center gap-2", className)} data-url-qr={url}>
      <div className="rounded-xl bg-white p-3 ring-1 ring-neutral-200">
        <QRCodeSVG value={url} size={Math.max(200, size)} bgColor="#ffffff" fgColor="#000000" level="M" />
      </div>
      <figcaption className="max-w-full select-all break-all text-center font-mono text-xs text-ink">{url}</figcaption>
    </figure>
  );
}
