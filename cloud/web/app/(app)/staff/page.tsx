"use client";

import { useApi } from "@/lib/hooks";
import { useT } from "@/lib/i18n/context";
import type { StaffListResponse, StaffMember } from "@/lib/types";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { RoleMatrix } from "@/components/staff/role-matrix";
import { useStores } from "@/lib/store";

/**
 * Read-only: each store's tablet owns its staff and pushes them up (one-way
 * sync). One store → its team; "All stores" → every store's team, grouped by
 * store, each with its own role matrix.
 */
export default function StaffPage() {
  const t = useT();
  const { combined, venues, nameOf, colorOf } = useStores();
  const { data, error, isLoading, mutate } = useApi<StaffListResponse>("/v1/staff");

  const groups: { venueId: string | null; staff: StaffMember[] }[] = data
    ? combined
      ? venues.map((v) => ({ venueId: v.id, staff: data.staff.filter((s) => s.venueId === v.id) }))
      : [{ venueId: null, staff: data.staff }]
    : [];

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
        <div className="grid gap-4 lg:grid-cols-2">
          {groups.map((g) => (
            <Card key={g.venueId ?? "one"} className={combined ? "" : "lg:col-span-2"}>
              {g.venueId && (
                <div className="flex items-center gap-2 border-b border-neutral-200/70 px-4 py-3">
                  <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: colorOf(g.venueId) }} />
                  <h2 className="text-sm font-semibold text-navy">{nameOf(g.venueId)}</h2>
                  <span className="ml-auto text-xs text-neutral-500">{t("staff_count_n", { n: g.staff.length })}</span>
                </div>
              )}
              {g.staff.length === 0 ? (
                <p className="px-4 py-5 text-center text-xs text-neutral-500">{t("staff_none")}</p>
              ) : (
                <div className="divide-y divide-neutral-200/70">
                  {g.staff.map((s) => {
                    const overrideCount = Object.keys(s.overrides).length;
                    return (
                      <div key={`${s.venueId}/${s.id}`} className="flex items-center gap-3 px-4 py-3">
                        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-surface-alt text-xs font-semibold text-navy">
                          {(s.name[0] ?? "?").toUpperCase()}
                        </span>
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
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>
          ))}
        </div>
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
