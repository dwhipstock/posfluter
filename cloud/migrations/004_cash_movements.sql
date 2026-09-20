-- 004: non-sale cash movements projected from the store's cash.movement events.
-- IN = float top-up / change added; OUT = petty cash / supplier paid in cash /
-- owner draw. Feeds the Cash movements (pay-in / pay-out) report. Idempotent
-- upsert by movement_id.

CREATE TABLE cash_movements (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    movement_id BIGINT NOT NULL,
    shift_id BIGINT,
    direction TEXT,
    amount_cents BIGINT,
    reason TEXT,
    created_by TEXT,
    created_at TIMESTAMP,
    PRIMARY KEY (tenant_id, venue_id, movement_id)
);
CREATE INDEX cash_movements_created_at ON cash_movements (tenant_id, venue_id, created_at);
