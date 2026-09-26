-- 045: optional kitchen / station tickets and the kitchen screen
-- (kitchen.printing=on; off by default, and then these tables stay empty).
-- Everything here is local to the store and never synced.

-- A station is where an order goes: Kitchen, Bar, Sushi Bar. Its output is
-- a printer, the kitchen screen, or both. A blank printer_host means "the
-- receipt printer" (venue settings), so every station can share one printer.
CREATE TABLE IF NOT EXISTS kitchen_stations (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name_fr VARCHAR(100) NOT NULL,
    name_en VARCHAR(100) NOT NULL,
    output VARCHAR(10) NOT NULL DEFAULT 'printer',
    printer_host VARCHAR(64) NOT NULL DEFAULT '',
    printer_port INT NOT NULL DEFAULT 9100,
    paper_mm INT NOT NULL DEFAULT 80,
    sort_order INT NOT NULL DEFAULT 0
);

-- Menu category → station, and optional per-item overrides. station_id ''
-- means "no ticket" (an item override can keep a line off every ticket).
CREATE TABLE IF NOT EXISTS kitchen_routes (
    kind VARCHAR(10) NOT NULL,
    ref_id VARCHAR(96) NOT NULL,
    station_id VARCHAR(64) NOT NULL DEFAULT '',
    PRIMARY KEY (kind, ref_id)
);

-- Store-level knobs: ticket language, default station, screen timer colours.
CREATE TABLE IF NOT EXISTS kitchen_config (
    key VARCHAR(64) NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);

-- What each station has been told about each check line (the net quantity
-- and the text it saw), so a later send prints only what changed. No FK: a
-- removed line's row stays until its VOID ticket is out.
CREATE TABLE IF NOT EXISTS kitchen_sent_lines (
    line_id INTEGER NOT NULL PRIMARY KEY,
    check_id INTEGER NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    qty INTEGER NOT NULL,
    name_fr VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    variant_fr VARCHAR(100) NULL,
    variant_en VARCHAR(100) NULL,
    note VARCHAR(500) NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_kitchen_sent_check ON kitchen_sent_lines (check_id);

-- One ticket event per station per send: ORDER | ADD | VOID | REPRINT. The
-- printer and the screen both read these, so they always agree.
CREATE TABLE IF NOT EXISTS kitchen_tickets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id VARCHAR(40) NOT NULL UNIQUE,
    check_id INTEGER NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    table_label VARCHAR(100) NOT NULL,
    server_name VARCHAR(100) NOT NULL,
    guests INT NULL,
    on_screen INT NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    bumped_at TEXT NULL,
    bump_id VARCHAR(40) NULL
);
CREATE INDEX IF NOT EXISTS idx_kitchen_tickets_check ON kitchen_tickets (check_id, station_id);
CREATE INDEX IF NOT EXISTS idx_kitchen_tickets_screen ON kitchen_tickets (on_screen, bumped_at);

CREATE TABLE IF NOT EXISTS kitchen_ticket_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id INTEGER NOT NULL,
    line_id INTEGER NOT NULL,
    qty INTEGER NOT NULL,
    name_fr VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    variant_fr VARCHAR(100) NULL,
    variant_en VARCHAR(100) NULL,
    note VARCHAR(500) NULL
);
CREATE INDEX IF NOT EXISTS idx_kitchen_ticket_items_ticket ON kitchen_ticket_items (ticket_id);

-- The persistent print queue. A job waits (with backoff) while its printer is
-- unreachable or out of paper and prints in order when it is back. job_id is
-- the idempotency key: a DONE job is never sent again.
CREATE TABLE IF NOT EXISTS kitchen_print_jobs (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id VARCHAR(40) NOT NULL UNIQUE,
    ticket_id INTEGER NULL,
    station_id VARCHAR(64) NOT NULL,
    lines_json TEXT NOT NULL,
    paper_mm INT NOT NULL DEFAULT 80,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_ms INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(300) NULL,
    created_at TEXT NOT NULL,
    printed_at TEXT NULL
);
CREATE INDEX IF NOT EXISTS idx_kitchen_jobs_pending ON kitchen_print_jobs (status, station_id, seq);

-- Guests at the table, for the ticket header (set from the check screen).
CREATE TABLE IF NOT EXISTS kitchen_check_info (
    check_id INTEGER NOT NULL PRIMARY KEY,
    guests INT NULL
);
