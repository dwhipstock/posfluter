-- 039: cash rounding to the nickel (Canada and the US no longer make pennies).
-- A cash payment that settles a balance rounds to the nearest 5¢; the tender
-- row already records that as tenders.rounding_adjustment_cents (001). A CASH
-- refund now rounds the same way: gross/net/tax stay exact, and this column
-- is what the cash handed back differs from the gross by (signed, cents):
-- cash back = gross_cents + rounding_adjustment_cents. 0 for card / transfer
-- refunds and every refund made before this migration.
ALTER TABLE refunds ADD COLUMN rounding_adjustment_cents BIGINT NOT NULL DEFAULT 0;
