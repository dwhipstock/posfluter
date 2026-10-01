-- 027: two-way menu sync (CONTRACT §10). The manager portal can now edit a
-- store's menu; the edit is merged field by field (last write wins) with what
-- the store pushes, and handed down to the store through a per-store feed.
-- Additive only: every existing row keeps its values, with no clocks yet
-- (an empty clock = "set before two-way sync").
--
-- clock          per-field write stamps of the row: {"nameEn": "<hlc>", ...},
--                including "names.<lang>" for the names in catalog_names
-- menu_feed      per-store change feed the store pulls: one entity's full
--                state (with clocks) per row, in seq order
-- menu_hlc       the cloud's hybrid logical clock, per tenant (last stamp issued)
-- menu_edits     idempotency keys of portal menu edits (a retried request is answered from here)
-- venues.menu_sync_at   last time the store pulled the feed: set = the store
--                       takes portal edits; NULL = an older store (portal edits refused)
-- venues.menu_cursor    the feed position the store had applied at that pull
--                       (the portal's "waiting for the store" count)
-- portal_users.role     owner | manager edit the menu; viewer only looks

ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS clock JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE catalog_variants ADD COLUMN IF NOT EXISTS clock JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE catalog_categories ADD COLUMN IF NOT EXISTS clock JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE IF NOT EXISTS menu_feed (
    seq        BIGSERIAL PRIMARY KEY,
    tenant_id  TEXT NOT NULL,
    venue_id   TEXT NOT NULL,
    entity     TEXT NOT NULL CHECK (entity IN ('item', 'category')),
    entity_id  TEXT NOT NULL,
    data       JSONB NOT NULL,
    origin     TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS menu_feed_venue_idx ON menu_feed (tenant_id, venue_id, seq);

CREATE TABLE IF NOT EXISTS menu_hlc (
    tenant_id TEXT PRIMARY KEY,
    last      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS menu_edits (
    tenant_id  TEXT NOT NULL,
    edit_id    TEXT NOT NULL,
    response   JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, edit_id)
);

ALTER TABLE venues ADD COLUMN IF NOT EXISTS menu_sync_at TIMESTAMPTZ;

ALTER TABLE venues ADD COLUMN IF NOT EXISTS menu_cursor BIGINT;

ALTER TABLE portal_users ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'owner';

ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS portal_users_role_check;

ALTER TABLE portal_users ADD CONSTRAINT portal_users_role_check CHECK (role IN ('owner', 'manager', 'viewer'));
