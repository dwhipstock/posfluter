-- Remove ONE store (venue) and everything the cloud holds for it from a
-- client's portal database — e.g. after that store moved to a portal of its
-- own (docs/hosted-client-split.md). One transaction; counts before and after
-- for every store of the tenant; aborts unless the venue ends with zero rows
-- AND every other store is exactly as it was.
--
--   psql -U pos -d pos_cloud -v tenant=copperlantern -v venue=sage-poppy -v commit=false -f remove-venue.sql
--
-- commit=false (rehearsal): runs everything, prints the counts, ROLLS BACK.
-- commit=true: the same, then COMMITs. Always rehearse first, with a backup.
--
-- Covers every table with (tenant_id, venue_id) — found from the schema, so a
-- table added by a later migration is covered too — plus the venue row itself
-- and group-level staff rows left with no store at all. The tenant, its portal
-- users and its other stores are never touched.
\set ON_ERROR_STOP on
\if :{?tenant}
\else
  \echo 'set -v tenant=<tenant id>'
  \quit
\endif
\if :{?venue}
\else
  \echo 'set -v venue=<venue id>'
  \quit
\endif
\if :{?commit}
\else
  \set commit false
\endif

BEGIN;
SELECT set_config('split.tenant', :'tenant', true) AS tenant, set_config('split.venue', :'venue', true) AS venue;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM venues WHERE tenant_id = current_setting('split.tenant') AND id = current_setting('split.venue')) THEN
    RAISE EXCEPTION 'no venue % in tenant %', current_setting('split.venue'), current_setting('split.tenant');
  END IF;
END $$;

CREATE TEMP TABLE split_tables ON COMMIT DROP AS
  SELECT c.table_name::text AS tbl
    FROM information_schema.columns c
    JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
   WHERE c.table_schema = current_schema() AND c.column_name = 'venue_id' AND t.table_type = 'BASE TABLE'
     AND EXISTS (SELECT 1 FROM information_schema.columns c2
                  WHERE c2.table_schema = c.table_schema AND c2.table_name = c.table_name AND c2.column_name = 'tenant_id');
CREATE TEMP TABLE split_counts (tbl text, venue text, before bigint, after bigint) ON COMMIT DROP;
-- group staff that belong to the venue ONLY (they would be left with no store)
CREATE TEMP TABLE split_staff ON COMMIT DROP AS
  SELECT sv.staff_id FROM staff_venues sv
   WHERE sv.tenant_id = current_setting('split.tenant') AND sv.venue_id = current_setting('split.venue')
     AND NOT EXISTS (SELECT 1 FROM staff_venues o WHERE o.tenant_id = sv.tenant_id AND o.staff_id = sv.staff_id
                                                   AND o.venue_id <> sv.venue_id);

-- counts BEFORE, per table and store
DO $$ DECLARE t text; v text; n bigint; BEGIN
  FOR t IN SELECT tbl FROM split_tables ORDER BY 1 LOOP
    FOR v IN SELECT id FROM venues WHERE tenant_id = current_setting('split.tenant') ORDER BY 1 LOOP
      EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id = %L AND venue_id = %L', t, current_setting('split.tenant'), v) INTO n;
      INSERT INTO split_counts VALUES (t, v, n, NULL);
    END LOOP;
  END LOOP;
  FOR v IN SELECT id FROM venues WHERE tenant_id = current_setting('split.tenant') LOOP
    INSERT INTO split_counts VALUES ('venues', v, 1, NULL);
  END LOOP;
  INSERT INTO split_counts SELECT 'staff (store-less after)', current_setting('split.venue'), count(*), NULL FROM split_staff;
END $$;

-- delete
DO $$ DECLARE t text; BEGIN
  FOR t IN SELECT tbl FROM split_tables ORDER BY 1 LOOP
    EXECUTE format('DELETE FROM %I WHERE tenant_id = %L AND venue_id = %L', t, current_setting('split.tenant'), current_setting('split.venue'));
  END LOOP;
  DELETE FROM staff s USING split_staff x WHERE s.tenant_id = current_setting('split.tenant') AND s.id = x.staff_id;
  DELETE FROM venues WHERE tenant_id = current_setting('split.tenant') AND id = current_setting('split.venue');
END $$;

-- counts AFTER
DO $$ DECLARE r record; n bigint; BEGIN
  FOR r IN SELECT tbl, venue FROM split_counts LOOP
    IF r.tbl = 'venues' THEN
      SELECT count(*) INTO n FROM venues WHERE tenant_id = current_setting('split.tenant') AND id = r.venue;
    ELSIF r.tbl = 'staff (store-less after)' THEN
      SELECT count(*) INTO n FROM staff s JOIN split_staff x ON s.id = x.staff_id WHERE s.tenant_id = current_setting('split.tenant');
    ELSE
      EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id = %L AND venue_id = %L', r.tbl, current_setting('split.tenant'), r.venue) INTO n;
    END IF;
    UPDATE split_counts SET after = n WHERE tbl = r.tbl AND venue = r.venue;
  END LOOP;
END $$;

\echo 'Rows per table and store (only non-empty ones), before → after:'
SELECT venue, tbl AS "table", before, after FROM split_counts WHERE before > 0 OR after > 0 ORDER BY venue, tbl;

-- the guard: the venue is gone completely, every other store is untouched
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM split_counts WHERE venue = current_setting('split.venue') AND after <> 0) THEN
    RAISE EXCEPTION 'check failed: rows of % remain', current_setting('split.venue');
  END IF;
  IF EXISTS (SELECT 1 FROM split_counts WHERE venue <> current_setting('split.venue') AND after IS DISTINCT FROM before) THEN
    RAISE EXCEPTION 'check failed: another store changed';
  END IF;
  RAISE NOTICE 'check passed: % removed, other stores unchanged', current_setting('split.venue');
END $$;

\if :commit
  COMMIT;
  \echo 'COMMITTED.'
\else
  ROLLBACK;
  \echo 'Rehearsal only: ROLLED BACK (run again with -v commit=true to apply).'
\endif
