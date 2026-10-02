-- 036: menu specials (CONTRACT §10 "Specials"; store migration 064). Two
-- more item registers of two-way menu sync, each one whole value in its
-- canonical JSON text (TEXT, not JSONB: JSONB would reorder the keys the
-- store compares by text):
--
-- available_days   the business days the item is sold, e.g. ["fri","sat"];
--                  NULL = every day
-- specials         the item's day prices (days, optional window, optional
--                  name, size id -> cents); NULL = none
--
-- Additive: existing rows get NULL and no stamp for either field ("set
-- before sync"), so a store's first value lands.
ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS available_days TEXT NULL;
ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS specials TEXT NULL;
