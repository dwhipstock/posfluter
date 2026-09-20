-- Floor plan: spatial geometry on dining_tables. Coordinates are LOGICAL
-- units on a 0–1000 × 0–1000 canvas per zone (top-left origin); the client
-- scales to its viewport, so the same layout renders on any screen.
-- shape: ROUND | SQUARE | RECT | BAR. rotation in degrees (0–359).
--
-- deleted_at: tables soft-delete like catalog items — closed checks reference
-- table ids forever, and checks.table_id is FK RESTRICT anyway.
ALTER TABLE dining_tables ADD COLUMN x INT NOT NULL DEFAULT 0;
ALTER TABLE dining_tables ADD COLUMN y INT NOT NULL DEFAULT 0;
ALTER TABLE dining_tables ADD COLUMN width INT NOT NULL DEFAULT 100;
ALTER TABLE dining_tables ADD COLUMN height INT NOT NULL DEFAULT 100;
ALTER TABLE dining_tables ADD COLUMN rotation INT NOT NULL DEFAULT 0;
ALTER TABLE dining_tables ADD COLUMN shape VARCHAR(10) NOT NULL DEFAULT 'SQUARE';
ALTER TABLE dining_tables ADD COLUMN seats INT NOT NULL DEFAULT 4;
ALTER TABLE dining_tables ADD COLUMN deleted_at TEXT NULL;

-- Auto-layout every existing table into a 5-per-row grid within its zone,
-- ordered by (sort_order, id) — the same order the old card grid showed.
-- Non-breaking upgrade: the venue opens to a usable layout, and the manager
-- rearranges in edit mode instead of staring at a blank canvas.
UPDATE dining_tables SET
  x = 60 + 180 * ((SELECT COUNT(*) FROM dining_tables d2
      WHERE d2.zone_id = dining_tables.zone_id
        AND (d2.sort_order < dining_tables.sort_order
          OR (d2.sort_order = dining_tables.sort_order AND d2.id < dining_tables.id))) % 5),
  y = 60 + 160 * ((SELECT COUNT(*) FROM dining_tables d2
      WHERE d2.zone_id = dining_tables.zone_id
        AND (d2.sort_order < dining_tables.sort_order
          OR (d2.sort_order = dining_tables.sort_order AND d2.id < dining_tables.id))) / 5);
