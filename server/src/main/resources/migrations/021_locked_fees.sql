-- 021: stamp the fee lines actually assessed at total-lock onto the check.
-- The fees baked into locked_grand_total_cents were previously re-assessed at
-- finalize/Z-report time against LIVE settings — a corkage/service-charge rate
-- change between lock and read re-priced history. JSON array of
-- {code, labelFr, labelEn, amountCents}; NULL on rows locked before this.

ALTER TABLE checks ADD COLUMN locked_fees_json TEXT NULL;
