-- 003: refunds projected from the store's report-complete refund.created events.
-- The store decomposed the reversed inclusive VAT (gross = money returned,
-- tax = VAT inside it, net = gross - tax); the cloud only stores and aggregates
-- these — it never recomputes tax. Refunds net out of sales + VAT in the summary
-- and VAT reports, and drive the Refunds report. Idempotent upsert by refund_id.

CREATE TABLE refunds (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    refund_id BIGINT NOT NULL,
    check_id INTEGER,
    shift_id BIGINT,
    gross_cents BIGINT,
    net_cents BIGINT,
    tax_included_cents BIGINT,
    tender_type TEXT,
    reason TEXT,
    refunded_by TEXT,
    table_label TEXT,
    zone_id TEXT,
    zone_name_fr TEXT,
    zone_name_en TEXT,
    created_at TIMESTAMP,
    PRIMARY KEY (tenant_id, venue_id, refund_id)
);
CREATE INDEX refunds_created_at ON refunds (tenant_id, venue_id, created_at);
