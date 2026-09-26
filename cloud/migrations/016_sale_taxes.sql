-- 016: per-sale GST / QST, as the store charged them (store migration 036).
-- The store adds the taxes on top of pre-tax prices and sends the breakdown on
-- check.closed / check.voided / refund.created (`taxes`: one entry per tax with
-- code, labels, rate, registration number and amount). The cloud keeps the two
-- amounts the tax report shows as columns, plus the full breakdown on checks
-- (labels and rates for display). tax_included_cents keeps meaning "every tax
-- inside the gross", so net = gross − tax is unchanged.
-- NULL = the store sent no breakdown (history before 036): reports show 0 and
-- never estimate it.

ALTER TABLE checks ADD COLUMN gst_cents BIGINT NULL;
ALTER TABLE checks ADD COLUMN qst_cents BIGINT NULL;
ALTER TABLE checks ADD COLUMN taxes JSONB NULL;
ALTER TABLE refunds ADD COLUMN gst_cents BIGINT NULL;
ALTER TABLE refunds ADD COLUMN qst_cents BIGINT NULL;
