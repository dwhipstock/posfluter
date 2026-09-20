-- 001: initial schema (converted from the SchemaUtils.create bootstrap).
-- DDL captured verbatim from Exposed 0.56 createStatements() so existing dev
-- databases created by the old bootstrap match byte-for-byte.

CREATE TABLE IF NOT EXISTS sync_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, event_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, aggregate_type VARCHAR(32) NOT NULL, aggregate_id VARCHAR(64) NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL, CONSTRAINT chk_sync_outbox_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647));

CREATE UNIQUE INDEX IF NOT EXISTS sync_outbox_event_id ON sync_outbox (event_id);

CREATE TABLE IF NOT EXISTS items (id VARCHAR(64) NOT NULL PRIMARY KEY, name_fr VARCHAR(200) NOT NULL, name_en VARCHAR(200) NOT NULL, category VARCHAR(64) NOT NULL, abbrev VARCHAR(4) NOT NULL, is_alcohol BOOLEAN DEFAULT 0 NOT NULL, active BOOLEAN DEFAULT 1 NOT NULL);

CREATE TABLE IF NOT EXISTS item_variants (id VARCHAR(96) NOT NULL PRIMARY KEY, item_id VARCHAR(64) NOT NULL, label_fr VARCHAR(100) NOT NULL, label_en VARCHAR(100) NOT NULL, price_cents BIGINT NOT NULL, sort_order INT DEFAULT 0 NOT NULL, CONSTRAINT fk_item_variants_item_id__id FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT chk_item_variants_signed_integer_sort_order CHECK (sort_order BETWEEN -2147483648 AND 2147483647));

CREATE TABLE IF NOT EXISTS tenders (id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id INT NOT NULL, "type" VARCHAR(20) NOT NULL, amount_tendered_cents BIGINT NOT NULL, amount_applied_cents BIGINT NOT NULL, rounding_adjustment_cents BIGINT DEFAULT 0 NOT NULL, change_cents BIGINT DEFAULT 0 NOT NULL, created_at TEXT NOT NULL, CONSTRAINT chk_tenders_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_tenders_signed_integer_transaction_id CHECK (transaction_id BETWEEN -2147483648 AND 2147483647));

CREATE TABLE IF NOT EXISTS users (id VARCHAR(64) NOT NULL PRIMARY KEY, "name" VARCHAR(100) NOT NULL, "role" VARCHAR(20) NOT NULL, pin VARCHAR(8) NOT NULL);

CREATE TABLE IF NOT EXISTS sessions (token VARCHAR(36) NOT NULL PRIMARY KEY, user_id VARCHAR(64) NOT NULL, created_at TEXT NOT NULL, revoked_at TEXT NULL, CONSTRAINT fk_sessions_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT);

CREATE TABLE IF NOT EXISTS zones (id VARCHAR(64) NOT NULL PRIMARY KEY, name_fr VARCHAR(100) NOT NULL, name_en VARCHAR(100) NOT NULL, sort_order INT DEFAULT 0 NOT NULL, CONSTRAINT chk_zones_signed_integer_sort_order CHECK (sort_order BETWEEN -2147483648 AND 2147483647));

CREATE TABLE IF NOT EXISTS dining_tables (id VARCHAR(64) NOT NULL PRIMARY KEY, zone_id VARCHAR(64) NOT NULL, label VARCHAR(32) NOT NULL, parent_table_id VARCHAR(64) NULL, name_override VARCHAR(100) NULL, sort_order INT DEFAULT 0 NOT NULL, CONSTRAINT fk_dining_tables_zone_id__id FOREIGN KEY (zone_id) REFERENCES zones(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT chk_dining_tables_signed_integer_sort_order CHECK (sort_order BETWEEN -2147483648 AND 2147483647));

CREATE TABLE IF NOT EXISTS checks (id INTEGER PRIMARY KEY AUTOINCREMENT, table_id VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, opened_by VARCHAR(64) NOT NULL, opened_at TEXT NOT NULL, closed_at TEXT NULL, corkage_bottles INT DEFAULT 0 NOT NULL, locked_grand_total_cents BIGINT NULL, locked_tax_included_cents BIGINT NULL, shift_id INT NULL, void_reason VARCHAR(300) NULL, voided_by VARCHAR(64) NULL, CONSTRAINT fk_checks_table_id__id FOREIGN KEY (table_id) REFERENCES dining_tables(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT chk_checks_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_checks_signed_integer_corkage_bottles CHECK (corkage_bottles BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_checks_signed_integer_shift_id CHECK (shift_id BETWEEN -2147483648 AND 2147483647));

CREATE TABLE IF NOT EXISTS check_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, check_id INT NOT NULL, item_id VARCHAR(64) NOT NULL, variant_id VARCHAR(96) NOT NULL, qty INT NOT NULL, unit_price_cents BIGINT NOT NULL, note VARCHAR(500) NULL, status VARCHAR(10) DEFAULT 'ACTIVE' NOT NULL, created_at TEXT NOT NULL, CONSTRAINT fk_check_lines_check_id__id FOREIGN KEY (check_id) REFERENCES checks(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_check_lines_item_id__id FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_check_lines_variant_id__id FOREIGN KEY (variant_id) REFERENCES item_variants(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT chk_check_lines_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_check_lines_signed_integer_check_id CHECK (check_id BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_check_lines_signed_integer_qty CHECK (qty BETWEEN -2147483648 AND 2147483647));

CREATE TABLE IF NOT EXISTS shifts (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(10) NOT NULL, opened_at TEXT NOT NULL, opened_by VARCHAR(64) NOT NULL, opening_float_cents BIGINT NOT NULL, closed_at TEXT NULL, closed_by VARCHAR(64) NULL, closing_count_cents BIGINT NULL, expected_cash_cents BIGINT NULL, over_short_cents BIGINT NULL, CONSTRAINT chk_shifts_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647));
