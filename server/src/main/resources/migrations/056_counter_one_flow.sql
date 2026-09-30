-- 056: the one-flow counter (Copper Lantern Express). An order gets its
-- customer number only when it is paid, so order_number becomes nullable; a
-- kiosk order waits to be paid under its own daily kiosk number (K1, K2...).
-- paid_at is when the order was committed (paid, sent to the kitchen).
-- Statuses: DRAFT (rung at the POS, unpaid), WAITING (kiosk, unpaid),
-- PREPARING -> READY -> PICKED_UP (paid). counter_config holds the counter's
-- settings (default dine in / take out). SQLite cannot drop NOT NULL, so the
-- table is rebuilt. The old unpaid / empty test orders are cleaned up at
-- startup (QuickServeService.cleanupLegacy), where the sync events are written.
CREATE TABLE IF NOT EXISTS counter_orders_056 (check_id INTEGER NOT NULL PRIMARY KEY, business_date VARCHAR(10) NOT NULL, order_number INTEGER NULL, kiosk_number INTEGER NULL, service_mode VARCHAR(10) NOT NULL, source VARCHAR(10) NOT NULL, status VARCHAR(12) NOT NULL, created_at TEXT NOT NULL, paid_at TEXT NULL, ready_at TEXT NULL, picked_up_at TEXT NULL);
INSERT INTO counter_orders_056 (check_id, business_date, order_number, kiosk_number, service_mode, source, status, created_at, paid_at, ready_at, picked_up_at) SELECT check_id, business_date, order_number, NULL, service_mode, source, status, created_at, NULL, ready_at, picked_up_at FROM counter_orders;
DROP TABLE counter_orders;
ALTER TABLE counter_orders_056 RENAME TO counter_orders;
CREATE UNIQUE INDEX IF NOT EXISTS counter_orders_number ON counter_orders (business_date, order_number);
CREATE INDEX IF NOT EXISTS counter_orders_kiosk ON counter_orders (business_date, kiosk_number);
CREATE INDEX IF NOT EXISTS counter_orders_status ON counter_orders (status);
CREATE TABLE IF NOT EXISTS counter_config (config_key VARCHAR(64) NOT NULL PRIMARY KEY, config_value VARCHAR(200) NOT NULL);
