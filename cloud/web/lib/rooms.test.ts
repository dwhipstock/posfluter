// The Rooms page's pure helpers: the room after a proposal, photo proposals, names, highlights, errors.
import { test } from "node:test";
import assert from "node:assert/strict";
import { fitSize, ghostRoom, highlights, objectCaption, objectTypeKey, photoRoom, roomErrorKey, roomName, roomStats } from "./rooms";
import { messages, type MsgKey } from "./i18n/messages";
import type { FloorEditProposal, FloorObject, FloorTable, RoomDto, RoomPhotoProposal } from "./types";

const table = (id: string, over: Partial<FloorTable> = {}): FloorTable => ({
  id, label: `U-${id}`, number: Number(id) || null, x: 100, y: 100, width: 100, height: 100, rotation: 0,
  shape: "SQUARE", seats: 4, parentTableId: null, locked: false, ...over,
});
const object = (id: string, over: Partial<FloorObject> = {}): FloorObject => ({
  id, type: "POOL", x: 500, y: 500, width: 200, height: 120, rotation: 0, labelEn: null, labelFr: null,
  names: {}, icon: null, shape: null, ...over,
});
const room: RoomDto = {
  id: "upper", nameEn: "Upper", nameFr: "Étage", names: { de: "Oben" }, sortOrder: 0, labelPrefix: "U",
  tables: [table("1", { locked: true, parentTableId: null }), table("2"), table("3", { parentTableId: "2" })],
  objects: [object("upper-pool"), object("upper-custom", { type: "CUSTOM", labelEn: "Jukebox", labelFr: "Juke-box", names: { de: "Musikbox" }, icon: "music" })],
};
const proposal: Pick<FloorEditProposal, "tables" | "objects" | "removedTables" | "removedObjects"> = {
  tables: [
    { id: "3", label: "U-3", x: 300, y: 300, width: 130, height: 130, rotation: 0, shape: "ROUND", seats: 6, number: 3 },
    { id: "new-t1", label: "U-4", x: 700, y: 100, width: 70, height: 70, rotation: 0, shape: "SQUARE", seats: 2, number: 4 },
  ],
  objects: [{ id: "new-o1", type: "STAGE", x: 0, y: 800, width: 300, height: 150, rotation: 0, labelEn: null, labelFr: null, icon: null, shape: null }],
  removedTables: ["2"],
  removedObjects: ["upper-pool"],
};

test("the room after a proposal: changes in place, additions at the end, removals gone", () => {
  const after = ghostRoom(room, proposal);
  assert.deepEqual(after.tables.map((t) => t.id), ["1", "3", "new-t1"]);
  const t3 = after.tables[1];
  assert.equal(t3.shape, "ROUND");
  assert.equal(t3.seats, 6);
  assert.equal(t3.parentTableId, "2", "a changed table keeps its link");
  assert.equal(after.tables[0].locked, true, "an untouched table keeps its lock");
  assert.equal(after.tables[2].locked, false);
  assert.deepEqual(after.objects.map((o) => o.id), ["upper-custom", "new-o1"]);
  assert.equal(room.tables.length, 3, "the room itself is not changed");
});

test("highlights: added, changed and removed ids", () => {
  const h = highlights(room, proposal);
  assert.deepEqual([...h.added].sort(), ["new-o1", "new-t1"]);
  assert.deepEqual([...h.changed], ["3"]);
  assert.deepEqual([...h.removed].sort(), ["2", "upper-pool"]);
});

test("a photo proposal draws as a room under the name typed", () => {
  const p: RoomPhotoProposal = {
    proposalId: "p1", venueId: "v", model: "m", roomName: "Patio", labelPrefix: "P",
    tables: [{ id: "ai-t1", label: "P-1", x: 0, y: 0, width: 100, height: 100, rotation: 0, shape: "ROUND", seats: 4, number: 1 }],
    objects: [], notes: "", rejected: [], elapsedMs: 1, refusal: null, message: null,
  };
  const r = photoRoom(p, "  Terrace ");
  assert.equal(r.nameEn, "Terrace");
  assert.equal(photoRoom(p).nameEn, "Patio");
  assert.equal(r.tables[0].locked, false);
  assert.deepEqual(roomStats(r), { tables: 1, seats: 4, objects: 0 });
});

test("names follow the locale; objects fall back to their type, pillars stay blank", () => {
  assert.equal(roomName("fr", room), "Étage");
  assert.equal(roomName("en", room), "Upper");
  assert.equal(roomName("de", room), "Oben");
  assert.equal(roomName("es", room), "Upper");
  const t = (k: MsgKey) => messages[k].en;
  assert.equal(objectCaption("de", room.objects[1], t), "Musikbox");
  assert.equal(objectCaption("fr", room.objects[1], t), "Juke-box");
  assert.equal(objectCaption("en", room.objects[0], t), messages.rooms_obj_pool.en);
  assert.equal(objectCaption("en", object("p", { type: "PILLAR" }), t), null);
  assert.equal(objectTypeKey("SOMETHING_NEW"), "rooms_obj_custom");
});

test("room stats count sub-tables and seats", () => {
  assert.deepEqual(roomStats(room), { tables: 3, seats: 12, objects: 2 });
});

test("room error codes have their own messages; the rest fall back to the AI's", () => {
  assert.equal(roomErrorKey("table_locked"), "rooms_err_locked");
  assert.equal(roomErrorKey("store_not_upgraded"), "rooms_err_not_upgraded");
  assert.equal(roomErrorKey("room_ai_image_too_large"), "rooms_err_image_large");
  assert.equal(roomErrorKey("menu_ai_expired"), "ai_err_expired");
  assert.equal(roomErrorKey("menu_ai_too_many"), "ai_err_too_many");
  assert.equal(roomErrorKey("something_else"), null);
  for (const c of ["store_not_upgraded", "table_locked", "room_not_found", "room_ai_no_image", "room_ai_image_type", "room_ai_image_too_large", "venue_required"]) {
    const k = roomErrorKey(c);
    assert.ok(k && messages[k], c);
  }
});

test("photos are downscaled to 1600 px on the long side, never upscaled", () => {
  assert.deepEqual(fitSize(4032, 3024), { width: 1600, height: 1200 });
  assert.deepEqual(fitSize(3024, 4032), { width: 1200, height: 1600 });
  assert.deepEqual(fitSize(800, 600), { width: 800, height: 600 });
});
