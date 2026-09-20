-- Multi-tenant cloud schema. Every row carries tenant_id; composite keys lead
-- with it so no query can cross tenants by accident. Timestamps are naive
-- (venue-local LocalDateTime per CONTRACT.md) — never timestamptz.

CREATE TABLE tenants (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE venues (
    tenant_id TEXT NOT NULL,
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    timezone TEXT NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE store_api_keys (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    key_sha256 TEXT NOT NULL UNIQUE,
    label TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP
);

CREATE TABLE portal_users (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    totp_secret TEXT,
    totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    display_name TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, email)
);

CREATE TABLE portal_sessions (
    token_sha256 TEXT NOT NULL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL
);

-- purpose: 'totp' (login second factor) | 'totp_setup' (first-login enrollment)
CREATE TABLE login_pending (
    token_sha256 TEXT NOT NULL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    purpose TEXT NOT NULL,
    secret TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- raw event log: replayable source of truth, dedup on (tenant_id, event_id)
CREATE TABLE events (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    store_seq BIGINT NOT NULL,
    store_created_at TIMESTAMP NOT NULL,
    received_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);

CREATE INDEX events_venue_seq_idx ON events (tenant_id, venue_id, store_seq);

-- projections below: money/tax columns are store-computed cents, nullable
-- because legacy thin payloads may not carry them (reports render 0)
CREATE TABLE checks (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    check_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    table_id TEXT,
    table_label TEXT,
    zone_id TEXT,
    zone_name_fr TEXT,
    zone_name_en TEXT,
    shift_id BIGINT,
    opened_at TIMESTAMP,
    closed_at TIMESTAMP,
    opened_by TEXT,
    grand_total_cents BIGINT,
    tax_included_cents BIGINT,
    corkage_bottles INTEGER,
    corkage_cents BIGINT,
    service_charge_cents BIGINT,
    void_reason TEXT,
    voided_by TEXT,
    PRIMARY KEY (tenant_id, venue_id, check_id)
);

CREATE TABLE check_lines (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    check_id INTEGER NOT NULL,
    line_id BIGINT,
    item_id TEXT,
    variant_id TEXT,
    category_id TEXT,
    name_fr TEXT,
    name_en TEXT,
    variant_label_fr TEXT,
    variant_label_en TEXT,
    display_name TEXT,
    qty INTEGER NOT NULL,
    unit_price_cents BIGINT NOT NULL,
    line_total_cents BIGINT NOT NULL
);

CREATE INDEX check_lines_check_idx ON check_lines (tenant_id, venue_id, check_id);

CREATE TABLE check_tenders (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    tender_id BIGINT NOT NULL,
    check_id INTEGER NOT NULL,
    type TEXT NOT NULL,
    amount_tendered_cents BIGINT,
    amount_applied_cents BIGINT,
    rounding_adjustment_cents BIGINT,
    change_cents BIGINT,
    tendered_at TIMESTAMP,
    PRIMARY KEY (tenant_id, venue_id, tender_id)
);

CREATE INDEX check_tenders_check_idx ON check_tenders (tenant_id, venue_id, check_id);

CREATE TABLE shifts (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    shift_id BIGINT NOT NULL,
    status TEXT NOT NULL,
    opened_at TIMESTAMP,
    opened_by TEXT,
    opening_float_cents BIGINT,
    closed_at TIMESTAMP,
    closed_by TEXT,
    revenue_cents BIGINT,
    transaction_count INTEGER,
    avg_check_cents BIGINT,
    corkage_cents BIGINT,
    tender_breakdown JSONB,
    expected_cash_cents BIGINT,
    closing_count_cents BIGINT,
    over_short_cents BIGINT,
    PRIMARY KEY (tenant_id, venue_id, shift_id)
);

-- catalog mirror: cloud-authoritative copy of the snapshot shapes (CONTRACT §2)
CREATE TABLE catalog_categories (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    id TEXT NOT NULL,
    name_fr TEXT NOT NULL,
    name_en TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, venue_id, id)
);

CREATE TABLE catalog_items (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    id TEXT NOT NULL,
    name_fr TEXT NOT NULL,
    name_en TEXT NOT NULL,
    description_fr TEXT NOT NULL DEFAULT '',
    description_en TEXT NOT NULL DEFAULT '',
    category_id TEXT NOT NULL,
    abbrev TEXT,
    is_alcohol BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    photo_version BIGINT,
    PRIMARY KEY (tenant_id, venue_id, id)
);

CREATE TABLE catalog_variants (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    label_fr TEXT NOT NULL,
    label_en TEXT NOT NULL,
    price_cents BIGINT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, venue_id, id)
);

-- distribution stream: bigserial version is globally monotonic, therefore also
-- monotonic per tenant — the feed filters by tenant_id (CONTRACT §4)
CREATE TABLE catalog_changes (
    version BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    op TEXT NOT NULL,
    data JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX catalog_changes_tenant_idx ON catalog_changes (tenant_id, version);

CREATE TABLE item_photos (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    content BYTEA NOT NULL,
    content_type TEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, item_id)
);
