-- 035: two-way room sync and the portal's AI room assistant (CONTRACT §11).
--
-- floor_things     the cloud's copy of each store's floor: one row per room
--                  (entity 'room' = a store zone), dining table ('table') or
--                  floor object ('floor_object'). `fields` holds the synced
--                  fields (last-write-wins registers, the same rule as the
--                  menu), `clock` the stamp of the write that set each one.
--                  Deletes are tombstones (deleted = true), never row
--                  deletes. `locked`: a table with an open bill at the store
--                  (or one of its sub-tables): the portal may not move,
--                  reshape, renumber or remove it. Open bills themselves never
--                  leave the store.
-- menu_feed        now also carries room / table / floor_object entries: the
--                  store pulls them with the menu (one cursor, one epoch).
--                  ('photo' stays allowed: migration 034, portal AI photos.)
-- venues.rooms_sync_at   last feed pull by a store that applies room
--                        changes (`rooms=1`): set = the portal may edit its
--                        floor; NULL = an older store (portal edits refused).
-- room_ai_applies  each applied AI room change (a room from a photo, a floor
--                  edit), with what it takes to put the floor back (Undo).
--
-- NOTE for merges: renumber this file if another 035 lands on main first.

CREATE TABLE IF NOT EXISTS floor_things (
    tenant_id  TEXT NOT NULL,
    venue_id   TEXT NOT NULL,
    entity     TEXT NOT NULL CHECK (entity IN ('room', 'table', 'floor_object')),
    id         TEXT NOT NULL,
    zone_id    TEXT,
    fields     JSONB NOT NULL DEFAULT '{}'::jsonb,
    clock      JSONB NOT NULL DEFAULT '{}'::jsonb,
    deleted    BOOLEAN NOT NULL DEFAULT false,
    locked     BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, venue_id, entity, id)
);

CREATE INDEX IF NOT EXISTS floor_things_zone_idx ON floor_things (tenant_id, venue_id, zone_id);

ALTER TABLE menu_feed DROP CONSTRAINT IF EXISTS menu_feed_entity_check;

ALTER TABLE menu_feed ADD CONSTRAINT menu_feed_entity_check
    CHECK (entity IN ('item', 'category', 'photo', 'room', 'table', 'floor_object'));

ALTER TABLE venues ADD COLUMN IF NOT EXISTS rooms_sync_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS room_ai_applies (
    id           TEXT PRIMARY KEY,
    tenant_id    TEXT NOT NULL,
    venue_id     TEXT NOT NULL,
    user_id      BIGINT NOT NULL,
    room_id      TEXT NOT NULL,
    kind         TEXT NOT NULL,
    summary      TEXT NOT NULL DEFAULT '',
    changes      INTEGER NOT NULL DEFAULT 0,
    undo         JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    reverted_at  TIMESTAMPTZ,
    reverted_by  BIGINT
);

CREATE INDEX IF NOT EXISTS room_ai_applies_venue_idx ON room_ai_applies (tenant_id, venue_id, created_at);
