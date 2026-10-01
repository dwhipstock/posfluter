// Server-built data exports (/v1/exports): the cloud writes the CSV / XLSX /
// ZIP from the full data; the portal only asks for it and saves the file.
// Unlike the report exports (ExportMenu, built in the browser from what the
// page shows), these are whole datasets, one row per check, line, refund ….

import { ApiError, loginUrlFor } from "../api";
import type { DateRange } from "../range";
import { scopeApiPath } from "../store";
import { saveBlob } from "./download";

/** The datasets the cloud exports, in the order the page lists them. `dated: false` = current state. */
export const EXPORT_DATASETS = [
  { id: "sales", dated: true },
  { id: "sale-lines", dated: true },
  { id: "refunds", dated: true },
  { id: "tenders", dated: true },
  { id: "shifts", dated: true },
  { id: "cash-movements", dated: true },
  { id: "tax-summary", dated: true },
  { id: "menu-items", dated: false },
  { id: "staff", dated: false },
] as const;

export type ExportDatasetId = (typeof EXPORT_DATASETS)[number]["id"];
export type ExportFormat = "csv" | "xlsx";

/** How many whole-data zips an owner may take per hour (the cloud's ZIP_PER_HOUR). */
export const ZIP_PER_HOUR = 3;

/** Owners and managers export; viewers don't (an older server without a role = owner). */
export function canExport(role: string | undefined): boolean {
  return role === undefined || role === "owner" || role === "manager";
}

/** `/v1/exports/sales.csv?from=…&to=…&venue=…`: the dates only where they apply; the picked store, if any. */
export function exportPath(id: ExportDatasetId, format: ExportFormat, range: DateRange, storeId: string | null): string {
  const dated = EXPORT_DATASETS.find((d) => d.id === id)?.dated ?? true;
  const q = dated ? `?from=${range.from}&to=${range.to}` : "";
  return scopeApiPath(`/v1/exports/${id}.${format}${q}`, storeId);
}

/** The owner's whole-data zip: every store, every date — the pickers don't narrow it. */
export const ALL_DATA_PATH = "/v1/exports/all.zip";

/** The file name from `Content-Disposition: attachment; filename=...` (quoted or not), else null. */
export function filenameFrom(header: string | null): string | null {
  if (!header) return null;
  const m = /filename\*?=(?:UTF-8'')?("?)([^";]+)\1/i.exec(header);
  if (!m) return null;
  const name = decodeURIComponent(m[2]).trim();
  // never a path: the browser picks the folder
  return name.replace(/[\\/]/g, "_") || null;
}

/** A refused export: the API's status + code, and (429) how many seconds until it may be tried again. */
export class ExportError extends ApiError {
  constructor(status: number, code: string, message: string, readonly retryAfterSeconds?: number) {
    super(status, code, message);
  }
}

/** Fetch an export and save it; resolves to the saved file name. Errors are [ExportError]s. */
export async function downloadExport(path: string, fallbackName: string): Promise<string> {
  let res: Response;
  try {
    res = await fetch(path, { credentials: "same-origin" });
  } catch {
    throw new ExportError(0, "network", "Can't reach the server");
  }
  if (!res.ok) {
    let code = `http_${res.status}`;
    let msg = res.statusText || "Request failed";
    try {
      const body = await res.json();
      code = body.code ?? code;
      msg = body.error ?? msg;
    } catch {}
    if (res.status === 401 && typeof window !== "undefined") {
      window.location.assign(loginUrlFor(code, res.headers.get("X-Session-Idle-Minutes")));
    }
    throw new ExportError(res.status, code, msg, Number(res.headers.get("Retry-After")) || undefined);
  }
  const name = filenameFrom(res.headers.get("Content-Disposition")) ?? fallbackName;
  saveBlob(await res.blob(), name);
  return name;
}
