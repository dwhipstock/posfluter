"use client";

import * as React from "react";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { AnimatePresence, motion } from "framer-motion";
import { QRCodeSVG } from "qrcode.react";
import { Check, Copy, Download, Loader2 } from "lucide-react";
import { ApiError, post } from "@/lib/api";
import { errorMessage, toast } from "@/lib/toast";
import type { ConfirmResponse, LoginResponse } from "@/lib/types";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { LangEraToggle } from "@/components/lang-toggle";
import { BrandMark } from "@/components/brand-mark";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type Stage =
  | { step: "creds" }
  | { step: "totp"; pendingToken: string }
  | { step: "setup"; pendingToken: string; secret: string; otpauthUri: string }
  | { step: "saved"; codes: string[] };

// Server error `code` → localized copy. Anything unmapped falls back to the
// server's English message, so new codes still surface something readable.
const AUTH_ERR: Record<string, MsgKey> = {
  bad_credentials: "err_bad_credentials",
  bad_totp: "err_bad_totp",
  bad_pending_token: "err_bad_pending_token",
  rate_limited: "err_rate_limited",
};

export default function LoginPage() {
  const router = useRouter();
  const t = useT();
  const [stage, setStage] = useState<Stage>({ step: "creds" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [backupMode, setBackupMode] = useState(false);
  const [busy, setBusy] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);

  const showAuthError = (err: unknown) => {
    const key = err instanceof ApiError ? AUTH_ERR[err.code] : undefined;
    toast("error", key ? t(key) : errorMessage(err));
    // a dead/expired pending token can't be retried — send them back to the start
    if (err instanceof ApiError && err.code === "bad_pending_token") {
      setStage({ step: "creds" });
      setBackupMode(false);
    }
  };

  useEffect(() => {
    fetch("/v1/auth/me", { credentials: "same-origin" })
      .then((r) => {
        if (r.ok) router.replace("/");
      })
      .catch(() => {});
  }, [router]);

  useEffect(() => {
    if (stage.step === "totp" || stage.step === "setup") {
      setCode("");
      setBackupMode(false);
      codeRef.current?.focus();
    }
  }, [stage.step]);

  const submitCreds = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    try {
      const res = await post<LoginResponse>("/v1/auth/login", { email, password });
      if (res.stage === "authenticated") {
        router.replace("/");
        router.refresh();
        return;
      }
      setStage(
        res.stage === "totp"
          ? { step: "totp", pendingToken: res.pendingToken }
          : { step: "setup", pendingToken: res.pendingToken, secret: res.secret, otpauthUri: res.otpauthUri }
      );
    } catch (err) {
      showAuthError(err);
    } finally {
      setBusy(false);
    }
  };

  // `c` is a 6-digit authenticator code or a backup code; the server sorts it out.
  const verify = async (c: string) => {
    if ((stage.step !== "totp" && stage.step !== "setup") || busy) return;
    setBusy(true);
    try {
      if (stage.step === "setup") {
        const res = await post<ConfirmResponse>("/v1/auth/totp/confirm", {
          pendingToken: stage.pendingToken,
          code: c,
        });
        setStage({ step: "saved", codes: res.backupCodes });
        return;
      }
      await post("/v1/auth/totp", { pendingToken: stage.pendingToken, code: c });
      router.replace("/");
    } catch (err) {
      showAuthError(err);
      setCode("");
      codeRef.current?.focus();
      setBusy(false);
    }
  };

  // 6 digits in → submit without waiting for a button (authenticator mode only)
  useEffect(() => {
    if (code.length === 6 && !backupMode && (stage.step === "totp" || stage.step === "setup") && !busy) {
      void verify(code);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);

  return (
    <div className="grid min-h-dvh place-items-center bg-paper px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-2 flex justify-end">
          <LangEraToggle />
        </div>
        <div className="mb-8 flex flex-col items-center">
          <BrandMark large />
          <p className="mt-3 text-sm text-neutral-500">{t("brand_tagline")}</p>
        </div>

        <div className="rounded-2xl border border-neutral-200 bg-white p-6 shadow-[0_1px_2px_rgba(0,0,0,0.04)]">
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={stage.step}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.18, ease: "easeOut" }}
            >
              {stage.step === "creds" && (
                <form onSubmit={submitCreds} className="space-y-4">
                  <div className="space-y-1.5">
                    <Label htmlFor="email">{t("login_email")}</Label>
                    <Input
                      id="email"
                      type="email"
                      autoComplete="email"
                      required
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      placeholder="owner@example.com"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="password">{t("login_password")}</Label>
                    <Input
                      id="password"
                      type="password"
                      autoComplete="current-password"
                      required
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="••••••••"
                    />
                  </div>
                  <Button type="submit" className="w-full" size="lg" disabled={busy}>
                    {busy && <Loader2 className="animate-spin" />}
                    {t("login_signin")}
                  </Button>
                </form>
              )}

              {stage.step === "totp" && !backupMode && (
                <div className="space-y-4">
                  <div>
                    <h2 className="text-base font-semibold">{t("login_2fa_title")}</h2>
                    <p className="mt-1 text-sm text-neutral-500">{t("login_2fa_body")}</p>
                  </div>
                  <CodeInput ref={codeRef} value={code} onChange={setCode} disabled={busy} />
                  <BusySpinner busy={busy} />
                  <button
                    onClick={() => setBackupMode(true)}
                    className="w-full text-center text-xs text-neutral-500 hover:text-ink"
                  >
                    {t("login_use_backup")}
                  </button>
                  <BackToLogin onClick={() => setStage({ step: "creds" })} />
                </div>
              )}

              {stage.step === "totp" && backupMode && (
                <BackupEntry
                  busy={busy}
                  onSubmit={(v) => void verify(v)}
                  onUseAuthenticator={() => {
                    setBackupMode(false);
                    setCode("");
                    codeRef.current?.focus();
                  }}
                />
              )}

              {stage.step === "setup" && (
                <div className="space-y-4">
                  <div>
                    <h2 className="text-base font-semibold">{t("login_setup_title")}</h2>
                    <p className="mt-1 text-sm text-neutral-500">{t("login_setup_body")}</p>
                  </div>
                  <div className="flex justify-center">
                    <div className="rounded-xl border border-neutral-200 bg-white p-3">
                      <QRCodeSVG value={stage.otpauthUri} size={168} />
                    </div>
                  </div>
                  <SecretRow secret={stage.secret} />
                  <CodeInput ref={codeRef} value={code} onChange={setCode} disabled={busy} />
                  <BusySpinner busy={busy} />
                  <BackToLogin onClick={() => setStage({ step: "creds" })} />
                </div>
              )}

              {stage.step === "saved" && (
                <BackupCodes codes={stage.codes} onContinue={() => router.replace("/")} />
              )}
            </motion.div>
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}

function BusySpinner({ busy }: { busy: boolean }) {
  if (!busy) return null;
  return (
    <div className="flex justify-center text-neutral-400">
      <Loader2 className="h-4 w-4 animate-spin" />
    </div>
  );
}

function BackToLogin({ onClick }: { onClick: () => void }) {
  const t = useT();
  return (
    <button onClick={onClick} className="w-full text-center text-xs text-neutral-500 hover:text-ink">
      {t("login_back")}
    </button>
  );
}

function BackupEntry({
  busy,
  onSubmit,
  onUseAuthenticator,
}: {
  busy: boolean;
  onSubmit: (code: string) => void;
  onUseAuthenticator: () => void;
}) {
  const t = useT();
  const [value, setValue] = useState("");
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => ref.current?.focus(), []);
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!busy && value.trim()) onSubmit(value.trim());
  };
  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <h2 className="text-base font-semibold">{t("login_backup_title")}</h2>
        <p className="mt-1 text-sm text-neutral-500">{t("login_backup_body")}</p>
      </div>
      <input
        ref={ref}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        autoComplete="one-time-code"
        autoCapitalize="none"
        spellCheck={false}
        disabled={busy}
        placeholder="xxxxx-xxxxx"
        className="h-12 w-full rounded-xl border border-neutral-200 bg-white text-center font-mono text-lg tracking-widest text-ink placeholder:text-neutral-300 focus:border-accent/60 focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:opacity-50"
      />
      <Button type="submit" className="w-full" size="lg" disabled={busy || !value.trim()}>
        {busy && <Loader2 className="animate-spin" />}
        {t("login_verify")}
      </Button>
      <button
        type="button"
        onClick={onUseAuthenticator}
        className="w-full text-center text-xs text-neutral-500 hover:text-ink"
      >
        {t("login_use_authenticator")}
      </button>
    </form>
  );
}

function BackupCodes({ codes, onContinue }: { codes: string[]; onContinue: () => void }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  const asText = codes.join("\n");

  const copyAll = async () => {
    try {
      await navigator.clipboard.writeText(asText);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast("error", t("login_copy_failed"));
    }
  };

  const download = () => {
    const blob = new Blob([asText + "\n"], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "copperlantern-backup-codes.txt";
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-base font-semibold">{t("login_backup_save_title")}</h2>
        <p className="mt-1 text-sm text-neutral-500">{t("login_backup_save_body")}</p>
      </div>
      <div className="grid grid-cols-2 gap-2 rounded-xl bg-neutral-50 p-3">
        {codes.map((c) => (
          <code key={c} className="text-center font-mono text-sm tracking-wider text-neutral-700">
            {c}
          </code>
        ))}
      </div>
      <p className="text-center text-xs font-medium text-accent">{t("login_backup_warn")}</p>
      <div className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={copyAll} type="button">
          {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
          {copied ? t("login_backup_copied") : t("login_backup_copy")}
        </Button>
        <Button variant="secondary" className="flex-1" onClick={download} type="button">
          <Download className="h-4 w-4" />
          {t("login_backup_download")}
        </Button>
      </div>
      <Button className="w-full" size="lg" onClick={onContinue} type="button">
        {t("login_backup_continue")}
      </Button>
    </div>
  );
}

function SecretRow({ secret }: { secret: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  const grouped = secret.match(/.{1,4}/g)?.join(" ") ?? secret;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast("error", t("login_copy_failed"));
    }
  };
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg bg-neutral-50 px-3 py-2">
      <code className="break-all font-mono text-xs text-neutral-600">{grouped}</code>
      <button
        onClick={copy}
        className="shrink-0 rounded-md p-1.5 text-neutral-400 hover:bg-neutral-100 hover:text-ink"
        aria-label={t("login_copy_secret")}
      >
        {copied ? <Check className="h-4 w-4 text-accent" /> : <Copy className="h-4 w-4" />}
      </button>
    </div>
  );
}

const CodeInput = React.forwardRef<
  HTMLInputElement,
  { value: string; onChange: (v: string) => void; disabled?: boolean }
>(({ value, onChange, disabled }, ref) => (
  <input
    ref={ref}
    value={value}
    onChange={(e) => onChange(e.target.value.replace(/\D/g, "").slice(0, 6))}
    inputMode="numeric"
    pattern="[0-9]*"
    autoComplete="one-time-code"
    maxLength={6}
    disabled={disabled}
    placeholder="••••••"
    className="h-14 w-full rounded-xl border border-neutral-200 bg-white text-center font-mono text-2xl tracking-[0.5em] text-ink placeholder:text-neutral-300 focus:border-accent/60 focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:opacity-50"
  />
));
CodeInput.displayName = "CodeInput";
