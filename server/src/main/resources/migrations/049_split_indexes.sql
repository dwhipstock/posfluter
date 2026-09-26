-- 049: a split check looks up each bill group's payments and each line's
-- allocations on every view, tender and close. Without these indexes both read
-- the whole table, so a split check got slower with every sale the store had
-- ever made (load test, a year of restaurant sales: docs/load-test-report.md).
CREATE INDEX IF NOT EXISTS idx_tenders_bill_group ON tenders (bill_group_id);
CREATE INDEX IF NOT EXISTS idx_bill_group_allocations_line ON bill_group_allocations (line_id);
