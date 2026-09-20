-- 020: sync_state — bookkeeping for the cloud sync loop (CONTRACT.md §5):
-- push_hwm (last acked outbox row id), catalog_cursor (last applied cloud
-- change version), catalog_snapshot_seq (one-time bootstrap marker).
CREATE TABLE IF NOT EXISTS sync_state (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);
