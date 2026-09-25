"use client";

import { Check, Minus } from "lucide-react";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const ROLES = ["MANAGER", "SERVER"] as const;

/**
 * The role-default grant matrix, read-only: each store's tablet owns its
 * grants and pushes them up (one-way sync).
 */
export function RoleMatrix({
  roleGrants,
  permissions,
  title,
}: {
  roleGrants: Record<string, Record<string, boolean>>;
  permissions: string[];
  /** Overrides the card title, e.g. with the store name in a combined view. */
  title?: string;
}) {
  const t = useT();
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title ?? t("grants_roles_title")}</CardTitle>
        <CardDescription>{t("grants_roles_sub")}</CardDescription>
      </CardHeader>
      <CardContent className="pt-0">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-neutral-100 text-xs text-neutral-500">
                <th className="py-2 text-left font-medium">{t("col_permission")}</th>
                {ROLES.map((r) => (
                  <th key={r} className="w-24 px-2 py-2 text-center font-medium">
                    {t(r === "MANAGER" ? "role_manager" : "role_server")}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100">
              {permissions.map((p) => (
                <tr key={p}>
                  <td className="py-2.5 pr-2">{t(`perm_${p}` as MsgKey)}</td>
                  {ROLES.map((r) => (
                    <td key={r} className="px-2 py-2.5">
                      <div className="flex justify-center">
                        {roleGrants[r]?.[p] ? (
                          <Check className="h-4 w-4 text-accent" aria-label={t("grant_allow")} />
                        ) : (
                          <Minus className="h-4 w-4 text-neutral-400" aria-label={t("grant_deny")} />
                        )}
                      </div>
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  );
}
