-- 034: AI item photos from the portal (/v1/menu-ai/photos).
--
-- menu_ai_photos: one row per generated picture. A preview waits here
-- ('pending') until the manager accepts or discards it, so a preview survives
-- an API restart and is never held only in memory. Accepting it makes it the
-- item's photo (item_photos, catalog_items.photo_version / photo_source) and
-- appends a 'photo' entry to the store's menu_feed, which is how the store
-- gets it (CONTRACT §10 "Photos from the portal"). The row keeps the photo it
-- replaced (prev_*) so Undo can put it back; had_prev = false means the item
-- had no photo, and Undo removes it again. Bytes are dropped once a row is
-- discarded or expires; accepted rows keep theirs for Undo.
--
-- Every generation is also a menu_ai_log row (kind 'photo'): the per-store
-- photo cap and the assistant's daily cap count those. Never the prompt or a key.
--
-- NOTE for merges: renumber this file if another 034 lands on main first.

CREATE TABLE IF NOT EXISTS menu_ai_photos (
    id                 TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    venue_id           TEXT NOT NULL,
    item_id            TEXT NOT NULL,
    user_id            BIGINT NOT NULL,
    -- ai_generated | ai_enhanced (what accepting it records as photo_source)
    source             TEXT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'pending',   -- pending | accepted | discarded | undone
    content            BYTEA,
    content_type       TEXT NOT NULL,
    provider           TEXT NOT NULL,
    model              TEXT NOT NULL,
    -- set on accept: the version written to item_photos, and what it replaced
    version            BIGINT,
    had_prev           BOOLEAN,
    prev_content       BYTEA,
    prev_content_type  TEXT,
    prev_source        TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS menu_ai_photos_item_idx ON menu_ai_photos (tenant_id, venue_id, item_id, created_at);

-- the store's menu feed carries photo entries too (old stores ignore an entity they don't know)
ALTER TABLE menu_feed DROP CONSTRAINT IF EXISTS menu_feed_entity_check;
ALTER TABLE menu_feed ADD CONSTRAINT menu_feed_entity_check CHECK (entity IN ('item', 'category', 'photo'));
