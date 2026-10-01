-- 029: menu feed hardening (CONTRACT §10).
--
-- menu_feed_epoch     one row: a random id for this database's menu feed. The
--                     feed epoch a store sees is "<database oid>-<this id>", so
--                     it changes when the database is restored from a backup
--                     into a new database, or reset (the row is re-created
--                     with a new id when it is missing). A store whose epoch
--                     differs, or whose cursor is past the newest entry, is
--                     served the feed from the start (replays are harmless).
-- menu_feed_entity_idx  (see below)
-- venues.menu_failed  menu changes the store reported it could not apply at
--                     its last feed pull (the portal's sync status shows them).
--
-- NOTE for merges: renumber this file if another 029 lands on main first.

CREATE TABLE IF NOT EXISTS menu_feed_epoch (
    id    SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    epoch TEXT NOT NULL
);

INSERT INTO menu_feed_epoch (id, epoch) VALUES (1, md5(random()::text || clock_timestamp()::text))
    ON CONFLICT (id) DO NOTHING;

ALTER TABLE venues ADD COLUMN IF NOT EXISTS menu_failed INTEGER;

-- the feed serves only the newest entry of each thing (each entry is its full
-- state): this finds "a newer entry of the same thing" quickly
CREATE INDEX IF NOT EXISTS menu_feed_entity_idx ON menu_feed (tenant_id, venue_id, entity, entity_id, seq);
