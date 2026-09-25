"use client";

import { useApi } from "@/lib/hooks";
import { useT } from "@/lib/i18n/context";
import type { StaffListResponse } from "@/lib/types";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { RoleMatrix } from "@/components/staff/role-matrix";
import { StoreTag } from "@/components/store-breakdown";
import { useStores } from "@/lib/store";

/** Read-only: each store's tablet owns its staff and pushes them up (one-way sync). */
export default function StaffPage() {
  const t = useT();
  const { combined, nameOf } = useStores();
  const { data, error, isLoading, mutate } = useApi<StaffListResponse>("/v1/staff");

  return (
    <div className="space-y-4">
      <PageHeader title={t("staff_title")} sub={t("staff_sub")} />

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
              <div key={`${s.venueId}/${s.id}`} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="truncate text-sm font-medium">{s.name}</span>
                    <StoreTag venueId={s.venueId} />
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
              </div>
            );
          })}
        </Card>
      ) : (
        <Card>
          <EmptyState title={t("staff_none")} />
        </Card>
      )}

      {data &&
        (combined ? data.venueGrants : data.venueGrants.slice(0, 1)).map((g) => (
          <RoleMatrix
            key={g.venueId}
            roleGrants={g.roleGrants}
            permissions={data.permissions}
            title={combined ? `${t("grants_roles_title")} · ${nameOf(g.venueId)}` : undefined}
          />
        ))}
    </div>
  );
}
