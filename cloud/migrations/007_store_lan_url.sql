-- 007: the store reports its current LAN base URL (e.g. http://192.168.1.50:8080)
-- to the cloud on each sync heartbeat, so the portal can 302-redirect staff phones
-- to the in-store staff ordering app (/staff-app) without anyone memorizing the IP.
-- store_seen_at gates freshness: a stale IP (store offline) is treated as unknown.

ALTER TABLE venues ADD COLUMN store_lan_url TEXT NULL;
ALTER TABLE venues ADD COLUMN store_seen_at TIMESTAMP NULL;
