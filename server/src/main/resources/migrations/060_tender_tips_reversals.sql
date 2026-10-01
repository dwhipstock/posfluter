-- 060: money red team (2026-10-01).
-- tenders.tip_cents: the tip a card took on top of the bill (card reader /
-- Stripe), so X / Z / range reports and the portal can count tips per tender
-- type and per server. amount_applied_cents stays the bill's share only.
-- Backfilled from card_json by the 061 code step.
-- tenders.reversed_at: a payment handed back when a manager cancels a partly
-- paid bill (void with "refund the payments"): the check is VOID, its
-- tenders stay as the record, stamped with when they were given back.
-- refunds.override_by: a refund to a different payment type than the guest
-- paid with (a card sale given back in cash), approved by this manager.
ALTER TABLE tenders ADD COLUMN tip_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE tenders ADD COLUMN reversed_at TEXT NULL;
ALTER TABLE refunds ADD COLUMN override_by VARCHAR(64) NULL;
