-- 013: settlement-time split checks — bill groups.
-- A split does NOT create new checks: the check stays the system-of-record and
-- its lines are partitioned into bill groups. Quantities can split across groups
-- (2 of 3 beers in group A, 1 in group B) via per-group qty allocations — lines
-- are never cloned. Each group prints its own provisional bill and takes its own
-- tenders; the check finalizes when every group is fully covered.
--
-- locked_total_cents is stamped per group at the first group tender (the split
-- analogue of checks.locked_grand_total_cents). fixed_amount_cents is reserved
-- for money-only even-split (÷N) groups: NULL = a normal by-item group.

CREATE TABLE bill_groups (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  check_id INTEGER NOT NULL REFERENCES checks(id),
  group_number INTEGER NOT NULL,
  includes_corkage INTEGER NOT NULL DEFAULT 0,
  fixed_amount_cents BIGINT,
  locked_total_cents BIGINT,
  created_at TEXT NOT NULL
);

CREATE INDEX idx_bill_groups_check ON bill_groups(check_id);

CREATE TABLE bill_group_allocations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id INTEGER NOT NULL REFERENCES bill_groups(id),
  line_id INTEGER NOT NULL REFERENCES check_lines(id),
  qty INTEGER NOT NULL,
  UNIQUE (group_id, line_id)
);

CREATE INDEX idx_bill_group_allocations_group ON bill_group_allocations(group_id);

-- Tender scope: which bill group a tender pays into. Plain integer, no FK —
-- tenders stay base-tier and attach to the abstract Transaction; the group id
-- is an opaque settlement sub-scope (NULL = whole-transaction tender).
ALTER TABLE tenders ADD COLUMN bill_group_id INTEGER;
