"use client";

import { useState } from "react";
import { Pencil, Plus } from "lucide-react";
import { patch, ApiError } from "@/lib/api";
import { toast, toastError } from "@/lib/toast";
import { useApi } from "@/lib/hooks";
import { useT } from "@/lib/i18n/context";
import type { StaffListResponse, StaffMember } from "@/lib/types";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { RoleMatrix } from "@/components/staff/role-matrix";
import { StaffEditor, type StaffEditorState } from "@/components/staff/staff-editor";

export default function StaffPage() {
  const t = useT();
  const { data, error, isLoading, mutate } = useApi<StaffListResponse>("/v1/staff");
  const [editor, setEditor] = useState<StaffEditorState | null>(null);

  const toggleActive = async (s: StaffMember, active: boolean) => {
    mutate(
      (prev) => prev && { ...prev, staff: prev.staff.map((x) => (x.id === s.id ? { ...x, active } : x)) },
      { revalidate: false }
    );
    try {
      await patch(`/v1/staff/${s.id}`, { active });
      mutate();
    } catch (err) {
      if (err instanceof ApiError && err.code === "last_manager") toast("error", t("staff_last_manager"));
      else toastError(err);
      mutate(); // roll back to the server's truth
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={t("staff_title")}
        sub={t("staff_sub")}
        action={
          <Button size="sm" onClick={() => setEditor({ mode: "new" })}>
            <Plus /> {t("staff_add")}
          </Button>
        }
      />

      {isLoading ? (
        <Card>
          <TableSkeleton rows={4} />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState message={error.message} onRetry={() => mutate()} />
        </Card>
      ) : data && data.staff.length > 0 ? (
        <Card className="divide-y divide-neutral-100">
          {data.staff.map((s) => {
            const overrideCount = Object.keys(s.overrides).length;
            return (
              <div key={s.id} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="truncate text-sm font-medium">{s.name}</span>
                    <Badge variant={s.role === "MANAGER" ? "pink" : "default"}>
                      {t(s.role === "MANAGER" ? "role_manager" : "role_server")}
                    </Badge>
                    {overrideCount > 0 && (
                      <Badge variant="outline">{t("staff_overrides_n", { n: overrideCount })}</Badge>
                    )}
                  </div>
                  <span className="text-xs text-neutral-500">
                    {s.active ? t("staff_active") : t("staff_inactive")}
                  </span>
                </div>
                <Switch checked={s.active} onCheckedChange={(v) => toggleActive(s, v)} />
                <Button variant="ghost" size="icon-sm" onClick={() => setEditor({ mode: "edit", staff: s })}>
                  <Pencil className="text-neutral-400" />
                </Button>
              </div>
            );
          })}
        </Card>
      ) : (
        <Card>
          <EmptyState title={t("staff_none")} />
        </Card>
      )}

      {data && (
        <RoleMatrix roleGrants={data.roleGrants} permissions={data.permissions} onChanged={() => mutate()} />
      )}
      {data && (
        <StaffEditor state={editor} data={data} onClose={() => setEditor(null)} onChanged={() => mutate()} />
      )}
    </div>
  );
}
