-- 014: open / misc items — a check line no longer has to point at the catalog.
-- item_id + variant_id become nullable and the line gains display_name: an open
-- line is (display_name, unit_price_cents, qty) with no catalog reference.
-- unit_price_cents was always captured on the line, so pricing/tax/receipts
-- work unchanged; renderers fall back to display_name when item_id is NULL.
--
-- SQLite can't ALTER a column's NOT NULL, so this is the standard rebuild:
-- new table → copy → drop → rename. IDs are preserved (bill_group_allocations
-- references them); sqlite_sequence follows the rename. FK enforcement is off
-- in this runner, so the drop/rename pair is safe mid-transaction.

CREATE TABLE check_lines_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  check_id INT NOT NULL,
  item_id VARCHAR(64) NULL,
  variant_id VARCHAR(96) NULL,
  display_name VARCHAR(200) NULL,
  qty INT NOT NULL,
  unit_price_cents BIGINT NOT NULL,
  note VARCHAR(500) NULL,
  status VARCHAR(10) DEFAULT 'ACTIVE' NOT NULL,
  created_at TEXT NOT NULL,
  CONSTRAINT fk_check_lines_check_id__id FOREIGN KEY (check_id) REFERENCES checks(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_check_lines_item_id__id FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_check_lines_variant_id__id FOREIGN KEY (variant_id) REFERENCES item_variants(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_check_lines_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647),
  CONSTRAINT chk_check_lines_signed_integer_check_id CHECK (check_id BETWEEN -2147483648 AND 2147483647),
  CONSTRAINT chk_check_lines_signed_integer_qty CHECK (qty BETWEEN -2147483648 AND 2147483647)
);

INSERT INTO check_lines_new (id, check_id, item_id, variant_id, display_name, qty, unit_price_cents, note, status, created_at)
  SELECT id, check_id, item_id, variant_id, NULL, qty, unit_price_cents, note, status, created_at FROM check_lines;

DROP TABLE check_lines;

ALTER TABLE check_lines_new RENAME TO check_lines;
