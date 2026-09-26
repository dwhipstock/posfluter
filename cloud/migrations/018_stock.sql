-- 018: stock for retail stores, kept in the cloud.
--
-- A retail store sells regardless of stock (it may go negative) and offline;
-- the tablet never tracks it. On hand is computed here, per product:
--   received − sold ± adjustments
-- where "sold" is the qty of every closed sale's lines synced from the store
-- (check_lines of CLOSED checks). Deliveries and adjustments are cloud-owned
-- rows the owner records in the portal; they never flow down to the store.
-- reorder_level marks a product as low at or below it.
-- catalog_items.barcode mirrors the store's UPC for display (store item
-- snapshots carry it, CONTRACT §2).

CREATE TABLE stock_movements (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    qty INTEGER NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX stock_movements_item_idx ON stock_movements (tenant_id, venue_id, item_id);
CREATE TABLE stock_levels (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    reorder_level INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, item_id)
);
ALTER TABLE catalog_items ADD COLUMN barcode TEXT NULL;
-- backfill the barcodes of items mirrored before this column existed, from
-- the raw events the stores already sent (snapshots first, later item edits win)
UPDATE catalog_items ci SET barcode = x.barcode FROM (SELECT e.tenant_id, e.venue_id, it->>'id' AS item_id, it->>'barcode' AS barcode FROM events e, jsonb_array_elements(e.payload->'items') it WHERE e.event_type = 'catalog.snapshot' AND (it->>'barcode') IS NOT NULL) x WHERE ci.tenant_id = x.tenant_id AND ci.venue_id = x.venue_id AND ci.id = x.item_id;
UPDATE catalog_items ci SET barcode = x.barcode FROM (SELECT DISTINCT ON (e.tenant_id, e.venue_id, e.payload->'item'->>'id') e.tenant_id, e.venue_id, e.payload->'item'->>'id' AS item_id, e.payload->'item'->>'barcode' AS barcode FROM events e WHERE e.event_type LIKE 'item.%' AND (e.payload->'item'->>'barcode') IS NOT NULL ORDER BY e.tenant_id, e.venue_id, e.payload->'item'->>'id', e.store_seq DESC) x WHERE ci.tenant_id = x.tenant_id AND ci.venue_id = x.venue_id AND ci.id = x.item_id;
