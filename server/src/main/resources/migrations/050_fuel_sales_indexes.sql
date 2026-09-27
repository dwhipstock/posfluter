-- 050: the forecourt reads its open fuellings every poll (300 ms) and on every
-- pump screen refresh: settled but not yet cleared at the controller, and
-- prepay change still to hand back. Without these indexes both read every
-- fuelling the store ever sold (load test, a year of sales: docs/load-test-report.md).
CREATE INDEX IF NOT EXISTS fuel_sales_status_cleared ON fuel_sales (status, fdc_cleared);
CREATE INDEX IF NOT EXISTS fuel_sales_change_due ON fuel_sales (change_given, refund_cents);
