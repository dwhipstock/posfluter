-- 026: network receipt printer becomes an owner-tunable venue setting. The store
-- prints real ESC/POS receipts to a thermal printer over TCP (raw, port 9100);
-- the printer's IP moves with DHCP, so the owner sets it in settings rather than
-- a redeploy. Empty ip = "not configured" (sends are skipped, no error). Default
-- port 9100 is the JetDirect/RAW convention every network thermal printer uses.

ALTER TABLE venue_settings ADD COLUMN printer_ip VARCHAR(64) DEFAULT '' NOT NULL;
ALTER TABLE venue_settings ADD COLUMN printer_port INT DEFAULT 9100 NOT NULL;
