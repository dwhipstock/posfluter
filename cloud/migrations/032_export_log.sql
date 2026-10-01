-- 032: who exported what from the manager portal (/v1/exports).
--
-- One row per download: the portal user, the dataset (sales, staff, … or
-- "all" for the owner's whole-data ZIP), the file format, the stores and the
-- business-day range it covered. The cloud had no audit trail for portal
-- reads; an export takes a copy of the business's data off the platform, so
-- each one is recorded. No file contents, no secrets: just the request.
--
-- NOTE for merges: renumber this file if another 032 lands on main first.

CREATE TABLE IF NOT EXISTS export_log (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  TEXT NOT NULL,
    user_id    BIGINT NOT NULL,
    dataset    TEXT NOT NULL,
    format     TEXT NOT NULL,
    venue_ids  TEXT NOT NULL,
    from_date  DATE,
    to_date    DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS export_log_user_idx ON export_log (tenant_id, user_id, created_at);
