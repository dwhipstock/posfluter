-- Owner-editable catalog (M6): items and variants become soft-deletable.
-- Deleted rows stay for closed-check history/receipts; every read path that
-- lists the catalog filters deleted_at IS NULL.
ALTER TABLE items ADD COLUMN deleted_at TEXT;
ALTER TABLE item_variants ADD COLUMN deleted_at TEXT;
