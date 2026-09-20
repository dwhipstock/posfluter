-- 015: pending-order alerts become owner-tunable venue settings. When a customer
-- QR order lands it chimes; if it sits un-actioned past the escalate window the
-- staff terminal escalates (recurring chime + a global "orders waiting" banner).
-- All three are venue-level (single row inherits the defaults via the column
-- default). Enabled by default; 90s before escalation; 80% chime volume.

ALTER TABLE venue_settings ADD COLUMN pending_alerts_enabled INT DEFAULT 1 NOT NULL;
ALTER TABLE venue_settings ADD COLUMN pending_alert_escalate_seconds INT DEFAULT 90 NOT NULL;
ALTER TABLE venue_settings ADD COLUMN pending_alert_volume INT DEFAULT 80 NOT NULL;
