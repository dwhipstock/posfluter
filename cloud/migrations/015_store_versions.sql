-- 015: the store may report its app version and contract version on each
-- heartbeat (CONTRACT §8, both optional). The portal's Devices page shows them
-- next to the store's liveness; a store that sends neither leaves them NULL.

ALTER TABLE venues ADD COLUMN store_app_version TEXT NULL;
ALTER TABLE venues ADD COLUMN store_contract_version INTEGER NULL;
