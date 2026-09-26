-- 036: taxes added on top of pre-tax prices (GST / QST), stamped per check.
-- locked_tax_added_cents is the sum of the taxes added on top of the pre-tax
-- subtotal (locked_grand_total_cents already includes it). The *_taxes_json
-- columns hold the breakdown frozen at lock/refund time, one entry per tax:
-- [{code, labelFr, labelEn, ratePercent, registrationNumber, amountCents}].
-- A later rate or label change never re-prices history. All NULL on rows
-- written before this: they carried no added tax.

ALTER TABLE checks ADD COLUMN locked_tax_added_cents BIGINT NULL;
ALTER TABLE checks ADD COLUMN locked_taxes_json TEXT NULL;
ALTER TABLE bill_groups ADD COLUMN locked_taxes_json TEXT NULL;
ALTER TABLE refunds ADD COLUMN taxes_json TEXT NULL;
