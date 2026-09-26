-- 020: stock counted and received IN THE STORE (store migration 040), and
-- refunds that put stock back.
--
-- The stock ledger stays stock_movements (018), now with a third kind:
--   RECEIVED    a delivery (portal, or scanned at the store: stock.received)
--   ADJUSTMENT  a portal correction (breakage, a return counted back in)
--   COUNT       a physical count (stock.counted): it SETS on hand to qty as
--               of created_at (the count time). Everything dated after it —
--               sales closed, deliveries, adjustments, refunds — applies on
--               top, so sales synced after the count still subtract correctly.
-- source is 'portal' (entered here) or 'store' (from the store's outbox);
-- source_ref ('count:<id>' / 'receipt:<id>') makes a replayed store event a
-- no-op per product.
--
-- stock_counts / stock_count_lines keep what the portal shows of a count:
-- who, when, and per product the counted qty against the qty the cloud
-- expected at that moment (its variance). stock_receipts heads a delivery
-- received at the store; its lines are the RECEIVED movements.
--
-- refund_lines: the products a by-line refund returned (refund.created
-- lines; itemId from the payload, or from the check's own lines for stores
-- that predate it). They go back on hand at the refund's time. An
-- amount-only refund names no products and changes no stock.

ALTER TABLE stock_movements ADD COLUMN source TEXT NOT NULL DEFAULT 'portal';
ALTER TABLE stock_movements ADD COLUMN source_ref TEXT NULL;
CREATE UNIQUE INDEX stock_movements_source_ref ON stock_movements (tenant_id, venue_id, source_ref, item_id) WHERE source_ref IS NOT NULL;
CREATE INDEX stock_movements_time_idx ON stock_movements (tenant_id, venue_id, item_id, kind, created_at);

CREATE TABLE stock_counts (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    count_id TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    started_by TEXT NULL,
    submitted_by TEXT NULL,
    submitted_by_name TEXT NULL,
    approved_by TEXT NULL,
    started_at TIMESTAMPTZ NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, count_id)
);
CREATE TABLE stock_count_lines (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    count_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    counted INTEGER NOT NULL,
    expected INTEGER NULL,
    store_expected INTEGER NULL,
    counted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, count_id, item_id)
);
CREATE TABLE stock_receipts (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    receipt_id TEXT NOT NULL,
    supplier TEXT NOT NULL DEFAULT '',
    reference TEXT NOT NULL DEFAULT '',
    received_by TEXT NULL,
    received_by_name TEXT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, receipt_id)
);
CREATE TABLE refund_lines (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    refund_id BIGINT NOT NULL,
    line_id BIGINT NOT NULL,
    item_id TEXT NULL,
    qty INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, refund_id, line_id)
);
CREATE INDEX refund_lines_item_idx ON refund_lines (tenant_id, venue_id, item_id);
-- backfill the by-line refunds already synced (the raw events keep their lines)
INSERT INTO refund_lines (tenant_id, venue_id, refund_id, line_id, item_id, qty, created_at) SELECT r.tenant_id, r.venue_id, r.refund_id, (l->>'lineId')::bigint, COALESCE(l->>'itemId', (SELECT cl.item_id FROM check_lines cl WHERE cl.tenant_id = r.tenant_id AND cl.venue_id = r.venue_id AND cl.check_id = r.check_id AND cl.line_id = (l->>'lineId')::bigint LIMIT 1)), (l->>'qty')::int, COALESCE(r.created_at, e.store_created_at) FROM refunds r JOIN events e ON e.tenant_id = r.tenant_id AND e.venue_id = r.venue_id AND e.event_type = 'refund.created' AND (e.payload->>'refundId')::bigint = r.refund_id, jsonb_array_elements(e.payload->'lines') l WHERE jsonb_typeof(e.payload->'lines') = 'array' AND (l->>'lineId') IS NOT NULL AND (l->>'qty') IS NOT NULL ON CONFLICT DO NOTHING;
