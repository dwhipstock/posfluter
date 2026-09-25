"use client";

// The "Export ▾" control on every report. A tiny hand-rolled dropdown (no menu
// lib in the tree) offering PDF, Excel and CSV. The generators are code-split and
// only fetched when the owner actually exports — the ~1MB of pdfmake/exceljs and
// the embedded Unicode font never load with the page.

import { useEffect, useRef, useState } from "react";
import { ChevronDown, Download, FileSpreadsheet, FileText, Loader2, Sheet } from "lucide-react";
import { useT } from "@/lib/i18n/context";
import { toastError } from "@/lib/toast";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import type { ExportDoc } from "@/lib/export/doc";

type Kind = "pdf" | "xlsx" | "csv";

export function ExportMenu({
  build,
  disabled = false,
}: {
  /**
   * Built lazily on click from the currently-loaded data; null skips. May be
   * async (e.g. the Journal fetches every page for the range on export).
   */
  build: () => ExportDoc | null | Promise<ExportDoc | null>;
  disabled?: boolean;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<Kind | null>(null);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const run = async (kind: Kind) => {
    if (busy) return;
    setBusy(kind);
    try {
      const doc = await build();
      if (!doc) return;
      if (kind === "pdf") {
        const { downloadPdf } = await import("@/lib/export/pdf");
        await downloadPdf(doc);
      } else if (kind === "csv") {
        const { downloadCsv } = await import("@/lib/export/csv");
        await downloadCsv(doc);
      } else {
        const { downloadXlsx } = await import("@/lib/export/xlsx");
        await downloadXlsx(doc, t("export_kpi_sheet"));
      }
      setOpen(false);
    } catch (e) {
      toastError(e);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="relative" ref={ref}>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => setOpen((o) => !o)}
        disabled={disabled}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <Download />
        {t("export")}
        <ChevronDown className={cn("transition-transform", open && "rotate-180")} />
      </Button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-50 mt-1 w-44 overflow-hidden rounded-lg border border-neutral-200 bg-surface py-1 shadow-raised"
        >
          <MenuItem
            icon={busy === "pdf" ? <Spinner /> : <FileText />}
            label={t("export_pdf")}
            onClick={() => run("pdf")}
            disabled={busy !== null}
          />
          <MenuItem
            icon={busy === "xlsx" ? <Spinner /> : <Sheet />}
            label={t("export_excel")}
            onClick={() => run("xlsx")}
            disabled={busy !== null}
          />
          <MenuItem
            icon={busy === "csv" ? <Spinner /> : <FileSpreadsheet />}
            label={t("export_csv")}
            onClick={() => run("csv")}
            disabled={busy !== null}
          />
        </div>
      )}
    </div>
  );
}

function MenuItem({
  icon,
  label,
  onClick,
  disabled,
}: {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
  disabled: boolean;
}) {
  return (
    <button
      role="menuitem"
      onClick={onClick}
      disabled={disabled}
      className="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-ink transition-colors hover:bg-neutral-50 disabled:cursor-not-allowed disabled:opacity-50 [&_svg]:h-4 [&_svg]:w-4 [&_svg]:shrink-0 [&_svg]:text-neutral-500"
    >
      {icon}
      {label}
    </button>
  );
}

function Spinner() {
  return <Loader2 className="animate-spin" />;
}
