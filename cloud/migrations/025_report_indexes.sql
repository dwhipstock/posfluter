-- Reports read closed sales by store and closing time (every dashboard view):
-- without this index even "today" read every sale the store ever made. Load
-- test, 200 stores x a year: docs/load-test-report.md.
CREATE INDEX IF NOT EXISTS checks_closed_at_idx ON checks (tenant_id, venue_id, closed_at);
