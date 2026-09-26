-- 019: cash rounding to the nickel (store migration 039). A CASH payment that
-- settles a balance rounds to the nearest 5¢; check_tenders already keeps its
-- rounding_adjustment_cents (001). Now a CASH refund rounds the same way, and a
-- Z-report echoes the shift's net rounding:
--   refunds.rounding_adjustment_cents — cash handed back − gross (signed)
--   shifts.cash_rounding_cents        — cash-sale rounding less cash refunds'
-- NULL = an older store that sent none; reports read it as 0. Revenue and tax
-- stay the exact figures; the portal reports the rounding on its own, per
-- store and per currency, never adding two currencies together.

ALTER TABLE refunds ADD COLUMN rounding_adjustment_cents BIGINT NULL;
ALTER TABLE shifts ADD COLUMN cash_rounding_cents BIGINT NULL;
