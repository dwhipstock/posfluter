-- 033: the portal's AI menu assistant (/v1/menu-ai).
--
-- menu_ai_log: one row per AI call or apply — who (portal user), which store,
-- what kind (chat | voice | apply | revert), the outcome (proposed, off_topic,
-- no_change, rate_limited, daily_limit, menu_ai_<error>, applied, reverted),
-- how many changes / rejected ones, and how long the model took. Never the
-- request text, the audio, the prompt or any key. It is also what the daily
-- cap counts, so the cap survives a restart.
--
-- menu_ai_applies: each applied proposal, with what it would take to put the
-- menu back (the before state of every field it changed), so the manager can
-- Undo it. The undo runs through the same portal edit path as the apply.
--
-- NOTE for merges: renumber this file if another 033 lands on main first.

CREATE TABLE IF NOT EXISTS menu_ai_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   TEXT NOT NULL,
    venue_id    TEXT NOT NULL,
    user_id     BIGINT NOT NULL,
    kind        TEXT NOT NULL,
    outcome     TEXT NOT NULL,
    changes     INTEGER NOT NULL DEFAULT 0,
    rejected    INTEGER NOT NULL DEFAULT 0,
    elapsed_ms  BIGINT NOT NULL DEFAULT 0,
    ref         TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS menu_ai_log_venue_idx ON menu_ai_log (tenant_id, venue_id, created_at);
CREATE INDEX IF NOT EXISTS menu_ai_log_user_idx ON menu_ai_log (tenant_id, user_id, created_at);

CREATE TABLE IF NOT EXISTS menu_ai_applies (
    id           TEXT PRIMARY KEY,
    tenant_id    TEXT NOT NULL,
    venue_id     TEXT NOT NULL,
    user_id      BIGINT NOT NULL,
    summary      TEXT NOT NULL DEFAULT '',
    changes      INTEGER NOT NULL DEFAULT 0,
    undo         JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    reverted_at  TIMESTAMPTZ,
    reverted_by  BIGINT
);

CREATE INDEX IF NOT EXISTS menu_ai_applies_venue_idx ON menu_ai_applies (tenant_id, venue_id, created_at);
