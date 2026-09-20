-- Floor objects: non-orderable structural props a manager places so the plan
-- matches the real room — POOL tables, the BAR_FRONT, PILLARs. Deliberately
-- NOT dining_tables: these carry no check, no seats, no label sequence, and
-- never take a status color. Same LOGICAL 0–1000 canvas per zone as tables.
--
-- Hard delete (no deleted_at): nothing references a floor object — no check FK,
-- no receipts — so removing one leaves nothing dangling.
CREATE TABLE floor_objects (
  id        TEXT PRIMARY KEY,
  zone_id   TEXT NOT NULL REFERENCES zones(id),
  type      TEXT NOT NULL,                 -- POOL | BAR_FRONT | PILLAR
  x         INT  NOT NULL DEFAULT 0,
  y         INT  NOT NULL DEFAULT 0,
  width     INT  NOT NULL DEFAULT 100,
  height    INT  NOT NULL DEFAULT 100,
  rotation  INT  NOT NULL DEFAULT 0,       -- degrees, 0–359
  label     TEXT NULL                      -- optional caption; type drives the shape
);

CREATE INDEX idx_floor_objects_zone ON floor_objects(zone_id);
