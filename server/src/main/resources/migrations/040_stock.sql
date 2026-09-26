-- 040: stock counting and receiving IN THE STORE (retail), offline.
--
-- The store still never keeps an on-hand figure of its own: on hand is the
-- cloud's ledger (cloud migration 018/020). What happens here is the physical
-- work — a count session (any staff scan and count, per phone/tablet), and a
-- delivery received at the back door — recorded locally so it works with no
-- internet, then sent up through the outbox (`stock.counted`,
-- `stock.received`) once submitted.
--
-- Ids are client-minted UUIDs so a phone that lost the store Wi-Fi can start
-- a count, queue its lines and submit later, and a retried request is the
-- same row (idempotent), never a duplicate.
--
-- stock_count_lines: one row per (count, product, counter) where the counter
-- is the phone/tablet install that counted it; the session's figure for a
-- product is the SUM over counters, so two people counting the back room and
-- the shelf add up, and each device's resend is a plain overwrite.
--
-- stock_expected caches the cloud's on-hand per product (a slow, best-effort
-- pull like device revocations) for the "expected 12" hint. Read-only: it
-- never gates a sale or a count.

CREATE TABLE IF NOT EXISTS stock_counts (id VARCHAR(40) NOT NULL PRIMARY KEY, name VARCHAR(100) NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'OPEN', started_by VARCHAR(64) NOT NULL, started_at TEXT NOT NULL, submitted_by VARCHAR(64) NULL, approved_by VARCHAR(64) NULL, submitted_at TEXT NULL);
CREATE TABLE IF NOT EXISTS stock_count_lines (count_id VARCHAR(40) NOT NULL, item_id VARCHAR(64) NOT NULL, counter_id VARCHAR(40) NOT NULL, qty INT NOT NULL, counted_by VARCHAR(64) NOT NULL, counted_at TEXT NOT NULL, PRIMARY KEY (count_id, item_id, counter_id));
CREATE TABLE IF NOT EXISTS stock_receipts (id VARCHAR(40) NOT NULL PRIMARY KEY, supplier VARCHAR(100) NOT NULL DEFAULT '', reference VARCHAR(100) NOT NULL DEFAULT '', received_by VARCHAR(64) NOT NULL, received_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS stock_receipt_lines (receipt_id VARCHAR(40) NOT NULL, item_id VARCHAR(64) NOT NULL, qty INT NOT NULL, PRIMARY KEY (receipt_id, item_id));
CREATE TABLE IF NOT EXISTS stock_expected (item_id VARCHAR(64) NOT NULL PRIMARY KEY, on_hand INT NOT NULL, as_of TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS stock_counts_status ON stock_counts (status);
