-- 041: a big retail shelf (~5,000 products at Sage & Poppy).
--
-- items gain the facets a counter filters and searches by: the producer
-- (brand), the style / varietal / type (subcategory) and a size or pack label
-- ('6-pack', '750 ml'). All NULL on the pubs' items. sales_weight is the demo
-- popularity a seeded store's sales follow (a 1/rank long tail) and the quick
-- keys' cold start before a store has sales of its own; 0 = none.
--
-- quick_key_pins: the counter tiles a manager pinned. The rest of the quick
-- keys are computed from the store's own last 28 days of sales, on the store,
-- with no cloud involved.
--
-- The indexes serve the retail lookups at 5,000 items: products by category,
-- prices by product, and closed sales by close time (the quick keys' and top
-- sellers' window).

ALTER TABLE items ADD COLUMN brand VARCHAR(100) NULL;
ALTER TABLE items ADD COLUMN subcategory VARCHAR(64) NULL;
ALTER TABLE items ADD COLUMN size_label VARCHAR(32) NULL;
ALTER TABLE items ADD COLUMN sales_weight INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_items_category ON items (category_id);
CREATE INDEX IF NOT EXISTS idx_item_variants_item ON item_variants (item_id);
CREATE INDEX IF NOT EXISTS idx_checks_status_closed ON checks (status, closed_at);
CREATE TABLE IF NOT EXISTS quick_key_pins (item_id VARCHAR(64) NOT NULL PRIMARY KEY, sort_order INT NOT NULL DEFAULT 0, pinned_by VARCHAR(64) NOT NULL, pinned_at TEXT NOT NULL);
