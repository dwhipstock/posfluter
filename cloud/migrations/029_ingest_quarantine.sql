-- 029: an ingested event the cloud cannot store or project is set aside here
-- instead of failing its whole batch. Before this, one guest note with U+0000
-- (Postgres JSONB cannot hold it) made every /v1/ingest batch it rode in fail,
-- and the store resent that same batch forever: no later sale ever reached
-- the portal. Now that event lands here (with the error), the rest of the
-- batch is stored and projected, and the store moves on.
--
-- payload is TEXT, not JSONB, on purpose: it keeps the event exactly as it
-- came (JSON text escapes U+0000 as the six characters \u0000), so it can be
-- inspected and replayed once the cause is fixed.
--
-- NOTE for merges: renumber this file if another 029 lands on main first.
CREATE TABLE IF NOT EXISTS ingest_quarantine (
    tenant_id        TEXT NOT NULL,
    venue_id         TEXT NOT NULL,
    event_id         TEXT NOT NULL,
    event_type       TEXT NOT NULL,
    aggregate_type   TEXT NOT NULL,
    aggregate_id     TEXT NOT NULL,
    store_seq        BIGINT NOT NULL,
    store_created_at TEXT NOT NULL,
    payload          TEXT NOT NULL,
    error            TEXT NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX IF NOT EXISTS ingest_quarantine_venue ON ingest_quarantine (tenant_id, venue_id, received_at);
