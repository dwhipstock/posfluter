import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium leading-4",
  {
    variants: {
      variant: {
        default: "bg-neutral-100 text-neutral-700",
        outline: "border border-neutral-200 text-neutral-600",
        pink: "bg-navy/10 text-navy",
        copper: "bg-copper-soft text-copper-text",
        dark: "bg-navy text-white",
        destructive: "bg-red-100 text-red-700",
        success: "bg-emerald-100 text-emerald-700",
        warning: "bg-amber-100 text-amber-800",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };
