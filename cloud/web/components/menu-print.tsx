"use client";

// The Menu page's "Print menus": pick a menu (full, today's, drinks,
// highlights, a happy-hour flyer), a store, language, paper, style and
// options, then Generate. The cloud makes the PDF in three steps — the AI
// writes the menu, the artwork is made (or reused), the PDF is built — so
// phone and computer get the same file; this shows each step, a preview of
// the pages, and Download / Share. Every name and price on the page is the
// store's own; the AI only writes the words and picks the look.
import { useEffect, useRef, useState } from "react";
import { Check, Download, Image as ImageIcon, Loader2, Printer, RefreshCw, Share2, SlidersHorizontal, Sparkles } from "lucide-react";
import { ApiError, post } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useI18n, useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { useBrand } from "@/lib/brand/context";
import { brandAssetUrl, type Brand } from "@/lib/brand/brand";
import { shortStoreName, useStores } from "@/lib/store";
import {
  aiNoteKey,
  base64Bytes,
  brandPayload,
  defaultLang,
  LANG_NAMES,
  logoFile,
  NOTES_MAX,
  pdfFileName,
  PRINT_LANGS,
  PRINT_STYLES,
  PRINT_TYPES,
  printErrorKey,
  printRequest,
  type PrintArt,
  type PrintLang,
  type PrintPlan,
  type PrintResult,
  type PrintStatus,
  type PrintStyle,
  type PrintType,
} from "@/lib/menu-print";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet";

type T = ReturnType<typeof useT>;
type Step = "form" | "writing" | "art" | "pdf" | "done";

const KIND: Record<PrintType, { label: MsgKey; hint: MsgKey }> = {
  full: { label: "print_kind_full", hint: "print_kind_full_hint" },
  today: { label: "print_kind_today", hint: "print_kind_today_hint" },
  drinks: { label: "print_kind_drinks", hint: "print_kind_drinks_hint" },
  highlights: { label: "print_kind_highlights", hint: "print_kind_highlights_hint" },
  flyer: { label: "print_kind_flyer", hint: "print_kind_flyer_hint" },
};

const STYLE_KEY: Record<PrintStyle | "auto", MsgKey> = {
  auto: "print_style_auto",
  classic: "print_style_classic",
  modern: "print_style_modern",
  chalkboard: "print_style_chalkboard",
  autumn: "print_style_autumn",
  summer: "print_style_summer",
};

/** Can this user print menus here (owners and managers). */
export function usePrintStatus() {
  const { data } = useApi<PrintStatus>("/v1/menu-print/status", { revalidateOnFocus: false });
  return data;
}

/** The header button. */
export function PrintButton({ onOpen }: { onOpen: () => void }) {
  const t = useT();
  const status = usePrintStatus();
  if (!status?.canUse) return null;
  return (
    <Button variant="secondary" size="sm" onClick={onOpen}>
      <Printer /> {t("print_menus")}
    </Button>
  );
}

export function PrintSheet({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="md:max-w-2xl">{open && <MenuPrinter />}</SheetContent>
    </Sheet>
  );
}

function errorText(t: T, e: unknown): string {
  if (e instanceof ApiError) {
    const k = printErrorKey(e.code);
    return k ? t(k) : e.message || t("print_err_failed");
  }
  return t("print_err_failed");
}

/** The brand pack's logo as a PNG data URL, at most 600 px a side (null if it can't be read). */
async function logoDataUrl(b: Brand): Promise<string | undefined> {
  try {
    const img = new Image();
    img.decoding = "async";
    img.src = brandAssetUrl(logoFile(b));
    await img.decode();
    const s = Math.min(1, 600 / Math.max(img.naturalWidth, img.naturalHeight, 1));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(img.naturalWidth * s));
    canvas.height = Math.max(1, Math.round(img.naturalHeight * s));
    canvas.getContext("2d")?.drawImage(img, 0, 0, canvas.width, canvas.height);
    return canvas.toDataURL("image/png");
  } catch {
    return undefined;
  }
}

function MenuPrinter() {
  const t = useT();
  const { locale } = useI18n();
  const brand = useBrand();
  const status = usePrintStatus();
  const { storeId, venues, nameOf } = useStores();
  const [picked, setPicked] = useState<string | null>(storeId ?? (venues.length === 1 ? venues[0].id : null));
  const venue = storeId ?? picked;

  const [type, setType] = useState<PrintType>("full");
  const [lang, setLang] = useState<PrintLang>(defaultLang(locale));
  const [paper, setPaper] = useState<"letter" | "a4">("letter");
  const [style, setStyle] = useState<PrintStyle | "auto">("auto");
  const [photos, setPhotos] = useState(false);
  const [fillPhotos, setFillPhotos] = useState(false);
  const [savePhotos, setSavePhotos] = useState(false);
  const [notes, setNotes] = useState("");

  const [step, setStep] = useState<Step>("form");
  const [error, setError] = useState<string | null>(null);
  const [plan, setPlan] = useState<PrintPlan | null>(null);
  const [art, setArt] = useState<PrintArt | null>(null);
  const [result, setResult] = useState<PrintResult | null>(null);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const logo = useRef<Promise<string | undefined> | null>(null);

  // the PDF's object URL lives as long as it is shown
  useEffect(() => () => { if (pdfUrl) URL.revokeObjectURL(pdfUrl); }, [pdfUrl]);

  const path = (p: string) => `${p}?venue=${encodeURIComponent(venue ?? "")}`;
  const busy = step === "writing" || step === "art" || step === "pdf";

  const show = (r: PrintResult) => {
    const blob = new Blob([base64Bytes(r.pdf) as BlobPart], { type: "application/pdf" });
    setPdfUrl(URL.createObjectURL(blob));
    setResult(r);
    setStep("done");
  };

  /** Steps 2 and 3 for [p]: the artwork (fresh = "New artwork"), then the PDF. */
  const finish = async (p: PrintPlan, fresh: boolean) => {
    setStep("art");
    setArt(await post<PrintArt>(path(`/v1/menu-print/${encodeURIComponent(p.jobId)}/art`), { fresh }));
    setStep("pdf");
    show(await post<PrintResult>(path(`/v1/menu-print/${encodeURIComponent(p.jobId)}/render`), {}));
  };

  const generate = async () => {
    if (!venue || busy) return;
    setError(null);
    setStep("writing");
    try {
      logo.current ??= logoDataUrl(brand);
      const req = printRequest({ type, lang, paper, photos, fillPhotos, savePhotos, notes, style, brand: brandPayload(brand, await logo.current) });
      const p = await post<PrintPlan>(path("/v1/menu-print/plan"), req);
      setPlan(p);
      await finish(p, false);
    } catch (e) {
      setError(errorText(t, e));
      setStep(result ? "done" : "form");
    }
  };

  const newArtwork = async () => {
    if (!plan || busy) return;
    setError(null);
    try {
      await finish(plan, true);
    } catch (e) {
      setError(errorText(t, e));
      setStep(result ? "done" : "form");
    }
  };

  const share = async () => {
    if (!result) return;
    const file = new File([base64Bytes(result.pdf) as BlobPart], pdfFileName(result.fileName), { type: "application/pdf" });
    try {
      await navigator.share({ files: [file], title: pdfFileName(result.fileName) });
    } catch {
      // cancelled, or the browser can't share files: nothing to say
    }
  };
  const canShare =
    typeof navigator !== "undefined" &&
    typeof navigator.canShare === "function" &&
    (() => {
      try {
        return navigator.canShare({ files: [new File([new Uint8Array([37])], "m.pdf", { type: "application/pdf" })] });
      } catch {
        return false;
      }
    })();

  const notesFor = (): MsgKey[] => {
    const out: MsgKey[] = [];
    const k = result ? aiNoteKey(result.ai, result.aiReason) : null;
    if (k) out.push(k);
    if (result?.notesIgnored) out.push("print_note_notes");
    if (art && art.builtIn > 0 && plan?.artAvailable) out.push("print_note_art");
    return out;
  };

  return (
    <>
      <SheetHeader>
        <div className="min-w-0">
          <SheetTitle className="flex items-center gap-2">
            <Printer className="h-4 w-4 shrink-0 text-copper-text" /> {t("print_title")}
          </SheetTitle>
          {venue && <p className="mt-0.5 text-xs text-neutral-600">{t("menu_scope_one", { store: shortStoreName(nameOf(venue)) })}</p>}
        </div>
      </SheetHeader>
      <SheetBody className="space-y-5">
        {step === "form" && (
          <>
            <p className="text-sm text-neutral-700">{t("print_intro")}</p>

            {!storeId && venues.length > 1 && (
              <div className="space-y-2">
                <p className="text-sm font-medium text-ink">{t("print_pick_store")}</p>
                <div className="flex flex-wrap gap-2">
                  {venues.map((v) => (
                    <Button key={v.id} size="sm" variant={picked === v.id ? "dark" : "secondary"} onClick={() => setPicked(v.id)}>
                      {picked === v.id && <Check />} {shortStoreName(v.name)}
                    </Button>
                  ))}
                </div>
              </div>
            )}

            <fieldset className="space-y-2">
              <legend className="mb-2 text-sm font-medium text-ink">{t("print_kind")}</legend>
              <div role="radiogroup" aria-label={t("print_kind")} className="grid gap-2 sm:grid-cols-2">
                {PRINT_TYPES.map((k) => (
                  <button
                    key={k}
                    type="button"
                    role="radio"
                    aria-checked={type === k}
                    onClick={() => setType(k)}
                    className={`rounded-xl border px-3 py-2.5 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 ${
                      type === k ? "border-accent bg-copper-soft" : "border-neutral-200 bg-surface hover:bg-neutral-50"
                    }`}
                  >
                    <span className="flex items-center gap-1.5 text-sm font-semibold text-ink">
                      {type === k && <Check className="h-3.5 w-3.5 shrink-0" />} {t(KIND[k].label)}
                    </span>
                    <span className="mt-0.5 block text-xs text-neutral-600">{t(KIND[k].hint)}</span>
                  </button>
                ))}
              </div>
            </fieldset>

            <div className="grid gap-3 sm:grid-cols-3">
              <Pick label={t("print_language")} value={lang} onChange={(v) => setLang(v as PrintLang)}
                options={PRINT_LANGS.map((l) => ({ value: l, label: LANG_NAMES[l] }))} />
              <Pick label={t("print_paper")} value={paper} onChange={(v) => setPaper(v as "letter" | "a4")}
                options={[{ value: "letter", label: t("print_paper_letter") }, { value: "a4", label: t("print_paper_a4") }]} />
              <Pick label={t("print_style")} value={style} onChange={(v) => setStyle(v as PrintStyle | "auto")}
                options={(["auto", ...PRINT_STYLES] as const).map((s) => ({ value: s, label: t(STYLE_KEY[s]) }))} />
            </div>

            <div className="space-y-3 rounded-xl border border-neutral-200 px-3 py-3">
              <Toggle label={t("print_photos")} checked={photos} onChange={setPhotos} />
              {photos && status?.art && (
                <Toggle label={t("print_fill_photos")} checked={fillPhotos} onChange={setFillPhotos} icon={<Sparkles className="h-3.5 w-3.5 text-copper-text" />} />
              )}
              {photos && status?.art && fillPhotos && (
                <Toggle label={t("print_save_photos")} hint={t("print_save_photos_hint")} checked={savePhotos} onChange={setSavePhotos} />
              )}
            </div>

            <label className="block space-y-1.5">
              <span className="text-sm font-medium text-ink">{t("print_notes")}</span>
              <textarea
                value={notes}
                rows={2}
                maxLength={NOTES_MAX}
                placeholder={t("print_notes_ph")}
                onChange={(e) => setNotes(e.target.value)}
                className="block w-full resize-none rounded-control border border-neutral-200 bg-surface px-3 py-2 text-sm text-ink placeholder:text-neutral-500 focus-visible:border-accent/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/25"
              />
            </label>
          </>
        )}

        {busy && <Progress t={t} step={step} />}

        {step === "done" && result && (
          <div className="space-y-3">
            <p className="text-sm text-neutral-700">
              {t("print_ready", { pages: result.pages, style: plan ? t(STYLE_KEY[plan.style] ?? "print_style_auto") : "" })}
            </p>
            {notesFor().map((k) => (
              <p key={k} className="rounded-lg bg-neutral-100 px-3 py-2 text-xs text-neutral-700">{t(k)}</p>
            ))}
            {art && art.photosSaved > 0 && (
              <p className="rounded-lg bg-emerald-50 px-3 py-2 text-xs text-emerald-900">{t("print_note_photos_saved", { n: art.photosSaved })}</p>
            )}
            <div className="space-y-3" aria-label={t("print_preview")}>
              {result.previews.map((src, i) => (
                <figure key={i} className="overflow-hidden rounded-lg border border-neutral-200 bg-white shadow-sm">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={src} alt={t("print_page_of", { n: i + 1, total: result.pages })} className="block h-auto w-full" />
                </figure>
              ))}
            </div>
          </div>
        )}

        {error && (
          <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">
            {error}
          </p>
        )}
      </SheetBody>

      <SheetFooter className="flex flex-wrap items-center justify-end gap-2">
        {step === "form" && (
          <Button className="w-full sm:w-auto" disabled={!venue} onClick={generate}>
            <Sparkles /> {t("print_generate")}
          </Button>
        )}
        {step === "done" && result && pdfUrl && (
          // phones: Download first and full width, the rest below; from sm up one row, Download last
          <div className="flex w-full flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-end">
            <Button asChild className="w-full sm:order-last sm:w-auto">
              <a href={pdfUrl} download={pdfFileName(result.fileName)}>
                <Download /> {t("print_download")}
              </a>
            </Button>
            <div className="flex flex-wrap gap-2 sm:justify-end">
              {canShare && (
                <Button variant="secondary" size="sm" onClick={share}>
                  <Share2 /> {t("print_share")}
                </Button>
              )}
              <Button variant="secondary" size="sm" onClick={generate}>
                <RefreshCw /> {t("print_again")}
              </Button>
              {plan?.artAvailable && plan.artToMake > 0 && (
                <Button variant="secondary" size="sm" onClick={newArtwork}>
                  <ImageIcon /> {t("print_new_art")}
                </Button>
              )}
              <Button variant="ghost" size="sm" onClick={() => { setStep("form"); setError(null); }}>
                <SlidersHorizontal /> {t("print_change")}
              </Button>
            </div>
          </div>
        )}
      </SheetFooter>
    </>
  );
}

function Progress({ t, step }: { t: T; step: Step }) {
  const steps: { key: Step; label: MsgKey }[] = [
    { key: "writing", label: "print_step_writing" },
    { key: "art", label: "print_step_art" },
    { key: "pdf", label: "print_step_pdf" },
  ];
  const at = steps.findIndex((s) => s.key === step);
  return (
    <ol className="space-y-2 py-4" role="status" aria-live="polite">
      {steps.map((s, i) => (
        <li key={s.key} className={`flex items-center gap-2 text-sm ${i <= at ? "text-ink" : "text-neutral-500"}`}>
          {i < at ? <Check className="h-4 w-4 text-emerald-700" /> : i === at ? <Loader2 className="h-4 w-4 animate-spin" /> : <span className="h-4 w-4" />}
          {t(s.label)}
        </li>
      ))}
    </ol>
  );
}

function Pick({ label, value, onChange, options }: {
  label: string; value: string; onChange: (v: string) => void; options: { value: string; label: string }[];
}) {
  return (
    <div className="space-y-1.5">
      <span className="block text-sm font-medium text-ink">{label}</span>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger aria-label={label}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {options.map((o) => (
            <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

function Toggle({ label, hint, checked, onChange, icon }: {
  label: string; hint?: string; checked: boolean; onChange: (v: boolean) => void; icon?: React.ReactNode;
}) {
  return (
    <label className="flex items-center justify-between gap-3">
      <span className="min-w-0">
        <span className="flex items-center gap-1.5 text-sm font-medium text-ink">{icon}{label}</span>
        {hint && <span className="block text-xs text-neutral-600">{hint}</span>}
      </span>
      <Switch checked={checked} onCheckedChange={onChange} />
    </label>
  );
}
