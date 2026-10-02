// Pure helpers for the Rooms page (app/(app)/rooms, components/room-ai.tsx,
// components/room-drawing.tsx): the room "after" an AI proposal, a photo
// proposal as a room, names by locale, highlight sets, error message keys.
// No React, no fetch: lib/rooms.test.ts covers them.
import type { FloorEditProposal, FloorObject, FloorTable, RoomDto, RoomObject, RoomPhotoProposal, RoomTable } from "./types";
import type { Locale, MsgKey } from "./i18n/messages";
import { pickName } from "./i18n/translate";
import { aiErrorKey } from "./menu-ai";

/** The plan is 1000 × 1000 logical units, like the POS. */
export const CANVAS = 1000;

/** The room's name in [locale]: fr → nameFr, en → nameEn, es/de/af → its own name, else English. */
export function roomName(locale: Locale, room: Pick<RoomDto, "nameFr" | "nameEn" | "names">): string {
  return pickName(locale, room.nameFr, room.nameEn, room.names);
}

/** A floor object's own caption in [locale] (null: none — the drawing uses the type's name). */
export function objectLabel(locale: Locale, o: Pick<FloorObject, "labelFr" | "labelEn" | "names">): string | null {
  const s = pickName(locale, o.labelFr, o.labelEn, o.names ?? null).trim();
  return s || null;
}

const TYPE_KEYS: Record<string, MsgKey> = {
  POOL: "rooms_obj_pool",
  BAR_FRONT: "rooms_obj_bar",
  ENTRANCE: "rooms_obj_entrance",
  HOST_STAND: "rooms_obj_host",
  KITCHEN: "rooms_obj_kitchen",
  RESTROOMS: "rooms_obj_restrooms",
  STAGE: "rooms_obj_stage",
  CARRY_OUT: "rooms_obj_carry_out",
  CUSTOM: "rooms_obj_custom",
};

/** The message key of a built-in object type's name; null for a pillar (a small block needs no word). */
export function objectTypeKey(type: string): MsgKey | null {
  if (type === "PILLAR") return null;
  return TYPE_KEYS[type] ?? "rooms_obj_custom";
}

/** The caption drawn on an object: its own label, else its type's name (pillars: none). */
export function objectCaption(
  locale: Locale,
  o: Pick<FloorObject, "type" | "labelFr" | "labelEn" | "names">,
  t: (k: MsgKey) => string
): string | null {
  if (o.type === "PILLAR") return null;
  const own = objectLabel(locale, o);
  if (own) return own;
  const k = objectTypeKey(o.type);
  return k ? t(k) : null;
}

/** Tables, seats and objects in a room (sub-tables count as tables). */
export function roomStats(room: Pick<RoomDto, "tables" | "objects">): { tables: number; seats: number; objects: number } {
  return {
    tables: room.tables.length,
    seats: room.tables.reduce((s, t) => s + (t.seats || 0), 0),
    objects: room.objects.length,
  };
}

function asTable(t: RoomTable, was?: FloorTable): FloorTable {
  return {
    id: t.id,
    label: t.label,
    number: t.number,
    x: t.x,
    y: t.y,
    width: t.width,
    height: t.height,
    rotation: t.rotation,
    shape: t.shape,
    seats: t.seats,
    parentTableId: was?.parentTableId ?? null,
    locked: was?.locked ?? false,
  };
}

function asObject(o: RoomObject, was?: FloorObject): FloorObject {
  return {
    id: o.id,
    type: o.type,
    x: o.x,
    y: o.y,
    width: o.width,
    height: o.height,
    rotation: o.rotation,
    labelEn: o.labelEn,
    labelFr: o.labelFr,
    // the proposal names fr/en only; an unchanged object keeps its other names
    names: was && was.labelEn === o.labelEn && was.labelFr === o.labelFr ? was.names : undefined,
    icon: o.icon,
    shape: o.shape,
  };
}

/**
 * The room as it would be after [p]: changed tables and objects replaced (by
 * id, in place), the added ones appended, the removed ones dropped.
 */
export function ghostRoom(room: RoomDto, p: Pick<FloorEditProposal, "tables" | "objects" | "removedTables" | "removedObjects">): RoomDto {
  const removedT = new Set(p.removedTables);
  const removedO = new Set(p.removedObjects);
  const tById = new Map(p.tables.map((t) => [t.id, t]));
  const oById = new Map(p.objects.map((o) => [o.id, o]));
  const curT = new Set(room.tables.map((t) => t.id));
  const curO = new Set(room.objects.map((o) => o.id));
  const tables = room.tables
    .filter((t) => !removedT.has(t.id))
    .map((t) => (tById.has(t.id) ? asTable(tById.get(t.id)!, t) : t))
    .concat(p.tables.filter((t) => !curT.has(t.id)).map((t) => asTable(t)));
  const objects = room.objects
    .filter((o) => !removedO.has(o.id))
    .map((o) => (oById.has(o.id) ? asObject(oById.get(o.id)!, o) : o))
    .concat(p.objects.filter((o) => !curO.has(o.id)).map((o) => asObject(o)));
  return { ...room, tables, objects };
}

/** A photo proposal as a room to draw (named [name], or the proposal's own name). */
export function photoRoom(p: RoomPhotoProposal, name?: string): RoomDto {
  const n = (name ?? "").trim() || p.roomName;
  return {
    id: `proposal-${p.proposalId}`,
    nameEn: n,
    nameFr: n,
    names: {},
    sortOrder: 0,
    labelPrefix: p.labelPrefix,
    tables: p.tables.map((t) => asTable(t)),
    objects: p.objects.map((o) => asObject(o)),
  };
}

export interface Highlights {
  added: Set<string>;
  changed: Set<string>;
  removed: Set<string>;
}

/** Which ids a proposal adds, changes and removes (tables and objects alike), for the drawings. */
export function highlights(room: RoomDto, p: Pick<FloorEditProposal, "tables" | "objects" | "removedTables" | "removedObjects">): Highlights {
  const cur = new Set([...room.tables.map((t) => t.id), ...room.objects.map((o) => o.id)]);
  const added = new Set<string>();
  const changed = new Set<string>();
  for (const x of [...p.tables, ...p.objects]) (cur.has(x.id) ? changed : added).add(x.id);
  return { added, changed, removed: new Set([...p.removedTables, ...p.removedObjects]) };
}

/** An API error code → the message to show (null: the caller's generic one). */
export function roomErrorKey(code: string): MsgKey | null {
  switch (code) {
    case "store_not_upgraded":
      return "rooms_err_not_upgraded";
    case "table_locked":
      return "rooms_err_locked";
    case "room_not_found":
      return "rooms_err_not_found";
    case "room_ai_no_image":
      return "rooms_err_no_image";
    case "room_ai_image_type":
      return "rooms_err_image_type";
    case "room_ai_image_too_large":
      return "rooms_err_image_large";
    case "venue_required":
      return "rooms_pick_store";
    case "room_no_change":
      return "rooms_err_no_change";
    case "room_bad_name":
      return "rooms_err_bad_name";
    default:
      return aiErrorKey(code);
  }
}

/** The size to downscale a photo to: the longest side at most [max], never upscaled. */
export function fitSize(width: number, height: number, max = 1600): { width: number; height: number } {
  const long = Math.max(width, height);
  if (long <= max || long <= 0) return { width: Math.round(width), height: Math.round(height) };
  const k = max / long;
  return { width: Math.max(1, Math.round(width * k)), height: Math.max(1, Math.round(height * k)) };
}
