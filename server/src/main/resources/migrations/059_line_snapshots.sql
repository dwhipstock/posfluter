-- 059: a check line keeps the menu as it was when it was rung. With two-way
-- menu sync a manager can rename, reprice, re-categorise or delete an item
-- (or a size) from the portal while it sits on an open check; the line keeps
-- its price and tax facts already (unit_price_cents, taxable, ...), and now
-- also its names, size labels, category, extra-language names and whether its
-- size is shown. Bills, receipts (and reprints), kitchen tickets, the
-- check.closed sync event and the store's reports read these; NULL (rows from
-- before this, until backfilled below) falls back to the live menu as before.
-- Existing lines are backfilled with the menu as it is now — what they would
-- have shown anyway.
ALTER TABLE check_lines ADD COLUMN name_fr VARCHAR(200) NULL;
ALTER TABLE check_lines ADD COLUMN name_en VARCHAR(200) NULL;
ALTER TABLE check_lines ADD COLUMN variant_label_fr VARCHAR(100) NULL;
ALTER TABLE check_lines ADD COLUMN variant_label_en VARCHAR(100) NULL;
ALTER TABLE check_lines ADD COLUMN category_id VARCHAR(64) NULL;
ALTER TABLE check_lines ADD COLUMN names_json TEXT NULL;
ALTER TABLE check_lines ADD COLUMN variant_names_json TEXT NULL;
ALTER TABLE check_lines ADD COLUMN show_variant BOOLEAN NULL;
UPDATE check_lines SET name_fr = (SELECT name_fr FROM items WHERE items.id = check_lines.item_id), name_en = (SELECT name_en FROM items WHERE items.id = check_lines.item_id), category_id = (SELECT category_id FROM items WHERE items.id = check_lines.item_id) WHERE item_id IS NOT NULL;
UPDATE check_lines SET variant_label_fr = (SELECT label_fr FROM item_variants WHERE item_variants.id = check_lines.variant_id), variant_label_en = (SELECT label_en FROM item_variants WHERE item_variants.id = check_lines.variant_id) WHERE variant_id IS NOT NULL;
