"use client";

import { motion } from "framer-motion";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

export function Kpi({
  label,
  value,
  sub,
  accent = false,
  loading = false,
  className,
}: {
  label: string;
  value?: string;
  sub?: string;
  accent?: boolean;
  loading?: boolean;
  className?: string;
}) {
  return (
    <Card className={cn("p-4", className)}>
      <div className="text-[11px] font-medium uppercase tracking-wider text-neutral-500">{label}</div>
      {loading ? (
        <Skeleton className="mt-1.5 h-8 w-24" />
      ) : (
        <motion.div
          key={value ?? "—"}
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.25, ease: "easeOut" }}
          className={cn("mt-1 text-2xl font-semibold tabular-nums tracking-tight", accent && "text-accent")}
        >
          {value ?? "—"}
        </motion.div>
      )}
      {sub && !loading && <div className="mt-0.5 text-xs text-neutral-500">{sub}</div>}
    </Card>
  );
}
