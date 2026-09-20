"use client";

import { useState } from "react";
import { Loader2, Trash2 } from "lucide-react";
import { post, patch, del, ApiError } from "@/lib/api";
import { toast, toastError } from "@/lib/toast";
import { useT } from "@/lib/i18n/context";
import type { MsgKey } from "@/lib/i18n/messages";
import type { StaffListResponse, StaffMember, StaffRole } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";

export type StaffEditorState = { mode: "new" } | { mode: "edit"; staff: StaffMember };

type Tri = "default" | "allow" | "deny";

const isPin = (s: string) => /^\d{4}$/.test(s);

export function StaffEditor({
  state,
  data,
  onClose,
  onChanged,
}: {
  state: StaffEditorState | null;
  data: StaffListResponse;
  onClose: () => void;
  onChanged: () => void;
}) {
  return (
    <Sheet open={state !== null} onOpenChange={(o) => !o && onClose()}>
      <SheetContent>
        {state && (
          <StaffForm
            key={state.mode === "edit" ? state.staff.id : "new"}
            state={state}
            data={data}
            onClose={onClose}
            onChanged={onChanged}
          />
        )}
      </SheetContent>
    </Sheet>
  );
}

function StaffForm({
  state,
  data,
  onClose,
  onChanged,
}: {
  state: StaffEditorState;
  data: StaffListResponse;
  onClose: () => void;
  onChanged: () => void;
}) {
  const t = useT();
  const existing = state.mode === "edit" ? state.staff : null;
  const [name, setName] = useState(existing?.name ?? "");
  const [role, setRole] = useState<StaffRole>(existing?.role ?? "SERVER");
  const [active, setActive] = useState(existing?.active ?? true);
  const [pin, setPin] = useState("");
  const [overrides, setOverrides] = useState<Record<string, Tri>>(() => {
    const o: Record<string, Tri> = {};
    if (existing) for (const [k, v] of Object.entries(existing.overrides)) o[k] = v ? "allow" : "deny";
    return o;
  });
  const [saving, setSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const setOverride = (perm: string, tri: Tri) =>
    setOverrides((prev) => {
      const next = { ...prev };
      if (tri === "default") delete next[perm];
      else next[perm] = tri;
      return next;
    });

  const overridesPayload = (): Record<string, boolean> => {
    const out: Record<string, boolean> = {};
    for (const [k, v] of Object.entries(overrides)) if (v !== "default") out[k] = v === "allow";
    return out;
  };

  const onError = (err: unknown) => {
    if (err instanceof ApiError && err.code === "last_manager") toast("error", t("staff_last_manager"));
    else toastError(err);
  };

  const save = async () => {
    if (!name.trim()) return toast("error", t("staff_name_required"));
    if (state.mode === "new" && !isPin(pin)) return toast("error", t("staff_pin_required"));
    if (pin && !isPin(pin)) return toast("error", t("staff_pin_bad"));
    setSaving(true);
    try {
      if (state.mode === "new") {
        const created = await post<StaffMember>("/v1/staff", { name: name.trim(), role, pin });
        const ov = overridesPayload();
        if (Object.keys(ov).length) await patch(`/v1/staff/${created.id}/grants`, { overrides: ov });
        if (!active) await patch(`/v1/staff/${created.id}`, { active: false });
        toast("success", t("staff_created"));
      } else {
        const id = state.staff.id;
        await patch(`/v1/staff/${id}`, { name: name.trim(), role, active });
        if (pin) await post(`/v1/staff/${id}/pin`, { pin });
        await patch(`/v1/staff/${id}/grants`, { overrides: overridesPayload() });
        toast("success", t("staff_saved"));
      }
      onChanged();
      onClose();
    } catch (err) {
      onError(err);
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (state.mode !== "edit") return;
    setSaving(true);
    try {
      await del(`/v1/staff/${state.staff.id}`);
      toast("success", t("staff_deleted"));
      setConfirmDelete(false);
      onChanged();
      onClose();
    } catch (err) {
      setConfirmDelete(false);
      onError(err);
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <SheetHeader>
        <SheetTitle>{t(state.mode === "new" ? "staff_new" : "staff_edit")}</SheetTitle>
      </SheetHeader>
      <SheetBody className="space-y-5">
        <div className="space-y-1.5">
          <Label>{t("staff_name")}</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("staff_name_ph")} />
        </div>

        <div className="space-y-1.5">
          <Label>{t("staff_role")}</Label>
          <Select value={role} onValueChange={(v) => setRole(v as StaffRole)}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="MANAGER">{t("role_manager")}</SelectItem>
              <SelectItem value="SERVER">{t("role_server")}</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center justify-between">
          <Label>{t("staff_active")}</Label>
          <Switch checked={active} onCheckedChange={setActive} />
        </div>

        <div className="space-y-1.5">
          <Label>{state.mode === "new" ? t("staff_pin_set") : t("staff_pin_reset")}</Label>
          <Input
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 4))}
            inputMode="numeric"
            autoComplete="off"
            placeholder="••••"
          />
          {state.mode === "edit" && <p className="text-xs text-neutral-500">{t("staff_pin_keep")}</p>}
        </div>

        <div className="space-y-2">
          <div>
            <h4 className="text-sm font-semibold text-ink">{t("grants_overrides_title")}</h4>
            <p className="text-xs text-neutral-500">{t("grants_overrides_sub")}</p>
          </div>
          <div className="divide-y divide-neutral-100 rounded-xl border border-neutral-100">
            {data.permissions.map((p) => {
              const roleDefault = data.roleGrants[role]?.[p] ?? false;
              const tri = overrides[p] ?? "default";
              return (
                <div key={p} className="flex items-center justify-between gap-2 px-3 py-2.5">
                  <div className="min-w-0 flex-1">
                    <span className="text-sm">{t(`perm_${p}` as MsgKey)}</span>
                    {tri !== "default" && (
                      <span className="ml-1.5 text-[11px] font-medium text-accent">{t("grant_overridden")}</span>
                    )}
                  </div>
                  <Select value={tri} onValueChange={(v) => setOverride(p, v as Tri)}>
                    <SelectTrigger className="h-8 w-40 text-xs">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="default">
                        {t(roleDefault ? "grant_default_on" : "grant_default_off")}
                      </SelectItem>
                      <SelectItem value="allow">{t("grant_allow")}</SelectItem>
                      <SelectItem value="deny">{t("grant_deny")}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              );
            })}
          </div>
        </div>

        {state.mode === "edit" && (
          <Button variant="destructive-outline" size="sm" onClick={() => setConfirmDelete(true)} disabled={saving}>
            <Trash2 /> {t("staff_delete")}
          </Button>
        )}
      </SheetBody>

      <SheetFooter className="flex justify-end gap-2">
        <Button variant="secondary" size="sm" onClick={onClose} disabled={saving}>
          {t("cancel")}
        </Button>
        <Button size="sm" onClick={save} disabled={saving}>
          {saving && <Loader2 className="animate-spin" />}
          {t("save")}
        </Button>
      </SheetFooter>

      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent>
          <DialogTitle>{t("staff_delete_q", { name: existing?.name ?? "" })}</DialogTitle>
          <DialogDescription>{t("staff_delete_body")}</DialogDescription>
          <DialogFooter>
            <Button variant="secondary" size="sm" onClick={() => setConfirmDelete(false)}>
              {t("keep_it")}
            </Button>
            <Button variant="destructive" size="sm" onClick={remove} disabled={saving}>
              {t("delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
