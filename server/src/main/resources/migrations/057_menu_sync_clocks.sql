-- 057: two-way menu sync (CONTRACT §10). One last-write-wins register per
-- synced menu field: the field's current value (canonical JSON) and the
-- hybrid-logical-clock stamp of the write that set it. Rows outlive a
-- category's hard delete (they are its tombstone). Migration 058 (code)
-- baselines every existing item, size and category with the empty stamp.
--
-- entity  item | variant | category
-- field   nameFr, nameEn, priceCents, deleted, names.es, ...
CREATE TABLE IF NOT EXISTS menu_sync_clocks (entity VARCHAR(16) NOT NULL, entity_id VARCHAR(160) NOT NULL, field VARCHAR(48) NOT NULL, value TEXT NOT NULL, hlc VARCHAR(64) NOT NULL, PRIMARY KEY (entity, entity_id, field));
