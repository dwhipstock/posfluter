-- Hot-path indexes. /zones resolves the open check for every table on every
-- poll (checks by table_id+status), then loads its lines and tenders; without
-- these each lookup is a full table scan that grows with sales history.
CREATE INDEX IF NOT EXISTS idx_checks_table_status ON checks(table_id, status);
CREATE INDEX IF NOT EXISTS idx_check_lines_check ON check_lines(check_id);
CREATE INDEX IF NOT EXISTS idx_tenders_transaction ON tenders(transaction_id);
