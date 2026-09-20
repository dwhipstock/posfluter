"use client";

import { useEffect, useState } from "react";
import { put, ApiError } from "@/lib/api";
import { toast, toastError } from "@/lib/toast";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";

const ROLES = ["MANAGER", "SERVER"] as const;

/** The role-default grant matrix: permissions × roles, one Switch per cell. */
export function RoleMatrix({
  roleGrants,
  permissions,
  onChanged,
}: {
  roleGrants: Record<string, Record<string, boolean>>;
  permissions: string[];
  onChanged: () => void;
}) {
  const t = useT();
  const [matrix, setMatrix] = useState(roleGrants);
  const [saving, setSaving] = useState(false);

  // resync when a refetch brings a new authoritative matrix
  useEffect(() => setMatrix(roleGrants), [roleGrants]);

  const toggle = async (role: string, perm: string, granted: boolean) => {
    const next = { ...matrix, [role]: { ...matrix[role], [perm]: granted } };
    setMatrix(next); // optimistic
    setSaving(true);
    try {
      await put("/v1/roles/grants", { roles: next });
      onChanged();
    } catch (err) {
      setMatrix(roleGrants); // revert
      if (err instanceof ApiError && err.code === "last_manager") toast("error", t("staff_last_manager"));
      else toastError(err);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("grants_roles_title")}</CardTitle>
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
                        <Switch
                          checked={matrix[r]?.[p] ?? false}
                          disabled={saving}
                          onCheckedChange={(v) => toggle(r, p, v)}
                        />
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
