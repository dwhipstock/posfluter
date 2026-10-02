-- 064: menu specials, for any kind of store. An item can be cheaper on some
-- days (maybe only in a time window, per size), and can be sold only on some
-- days (sdk/MenuSpecials.kt). A side table, so the shared items table keeps
-- its shape: one row per item that has any; no row = every day, menu price.
CREATE TABLE item_specials (
    item_id VARCHAR(64) NOT NULL PRIMARY KEY REFERENCES items(id),
    available_days VARCHAR(40) NULL,
    specials_json TEXT NULL
);
-- A line rung at a special price keeps the menu price it replaced and which
-- special it was (days, window, name), for the check, the bill and receipts.
ALTER TABLE check_lines ADD COLUMN regular_unit_price_cents BIGINT NULL;
ALTER TABLE check_lines ADD COLUMN special_json TEXT NULL;
-- Two-way menu sync: the two new item fields start as "set before sync" (the
-- empty stamp, like 058), so a portal edit made before this store upgraded
-- still wins over the store's empty values.
INSERT OR IGNORE INTO menu_sync_clocks (entity, entity_id, field, value, hlc) SELECT 'item', id, 'availableDays', 'null', '' FROM items;
INSERT OR IGNORE INTO menu_sync_clocks (entity, entity_id, field, value, hlc) SELECT 'item', id, 'specials', 'null', '' FROM items;
