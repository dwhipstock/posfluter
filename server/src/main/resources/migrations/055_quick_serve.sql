-- 055: quick-serve counter orders (Copper Lantern Express). One row per
-- counter order: its check, the short number the customer is called by
-- (101, 102... restarting every business day), dine in / take out, where it
-- came from (the POS or a self-order kiosk) and its pickup status
-- (NEW -> PREPARING -> READY -> PICKED_UP). kiosk_devices marks which paired
-- devices are self-order kiosks. Local to the store (never synced).
CREATE TABLE IF NOT EXISTS counter_orders (check_id INTEGER NOT NULL PRIMARY KEY, business_date VARCHAR(10) NOT NULL, order_number INTEGER NOT NULL, service_mode VARCHAR(10) NOT NULL, source VARCHAR(10) NOT NULL, status VARCHAR(12) NOT NULL, created_at TEXT NOT NULL, ready_at TEXT NULL, picked_up_at TEXT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS counter_orders_number ON counter_orders (business_date, order_number);
CREATE TABLE IF NOT EXISTS kiosk_devices (device_id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(100) NOT NULL, paired_at TEXT NOT NULL);
