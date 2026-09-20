"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { onToast, type ToastMsg } from "@/lib/toast";
import { cn } from "@/lib/utils";

const DOT: Record<ToastMsg["kind"], string> = {
  success: "bg-accent",
  error: "bg-red-500",
  info: "bg-neutral-400",
};

export function Toaster() {
  const [toasts, setToasts] = useState<ToastMsg[]>([]);

  useEffect(
    () =>
      onToast((t) => {
        setToasts((prev) => [...prev.slice(-3), t]);
        setTimeout(() => setToasts((prev) => prev.filter((x) => x.id !== t.id)), 4000);
      }),
    []
  );

  return (
    <div className="pointer-events-none fixed inset-x-0 top-3 z-[100] flex flex-col items-center gap-2 px-4">
      <AnimatePresence>
        {toasts.map((t) => (
          <motion.div
            key={t.id}
            initial={{ opacity: 0, y: -12, scale: 0.96 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -8, scale: 0.98 }}
            transition={{ duration: 0.18, ease: "easeOut" }}
            className="pointer-events-auto flex max-w-md items-center gap-2.5 rounded-full bg-ink px-4 py-2.5 text-sm text-white shadow-lg"
            onClick={() => setToasts((prev) => prev.filter((x) => x.id !== t.id))}
          >
            <span className={cn("h-2 w-2 shrink-0 rounded-full", DOT[t.kind])} />
            <span className="truncate">{t.message}</span>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
