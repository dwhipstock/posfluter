"""scripts/demo-reseed.py end to end against a real Postgres with the cloud migrations applied.

Skipped unless DEMO_RESEED_TEST_DSN names a Postgres server where this test may
create and drop its own database (e.g. postgresql://postgres@localhost:5432/postgres).
Needs psql, pg_dump and gzip on PATH.

  DEMO_RESEED_TEST_DSN=postgresql://postgres@localhost:55432/postgres \\
    python3 -m unittest scripts/tests/test_demo_reseed_db.py -v

It builds a database the way the hosted one looks: the cloud schema
(cloud/migrations, in order), tenant copperlantern with vieux-port, express
and plateau and their catalogs (tests/fixtures/copperlantern-catalog.json),
plus the junk a week of testing leaves: old checks, today's checks, an open
shift, a refund, a cash movement, and another tenant's sales. Then it runs the
plan, --yes, a rerun, and checks what changed and what did not.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("demo_reseed", REPO / "scripts" / "demo-reseed.py")
rs = importlib.util.module_from_spec(_spec)
sys.modules["demo_reseed"] = rs
_spec.loader.exec_module(rs)

ADMIN = os.environ.get("DEMO_RESEED_TEST_DSN")
FIXTURE = REPO / "scripts" / "tests" / "fixtures" / "copperlantern-catalog.json"
TODAY = "2026-10-04"
DBNAME = "demo_reseed_test"


def q(v):
    return rs.q(v)


def seed_sql() -> str:
    doc = json.loads(FIXTURE.read_text())
    out = ["BEGIN;",
           "INSERT INTO tenants (id, name, created_at, reporting_currency) VALUES ('copperlantern', 'Copper Lantern', now(), 'USD'),"
           " ('sagepoppy', 'Sage & Poppy', now(), 'USD');",
           "INSERT INTO venues (tenant_id, id, name, timezone, currency, country) VALUES"
           " ('sagepoppy', 'sage-poppy', 'Sage & Poppy', 'America/New_York', 'USD', 'US');"]
    for v in doc["venues"]:
        V = q(v["id"])
        out.append(f"INSERT INTO venues (tenant_id, id, name, timezone, currency, country, store_install_id) VALUES "
                   f"('copperlantern', {V}, {q(v['name'])}, {q(v['timezone'])}, 'USD', 'US', {q('install-' + v['id'])});")
        for c in v["menu"]["categories"]:
            out.append(f"INSERT INTO catalog_categories (tenant_id, venue_id, id, name_fr, name_en, sort_order, deleted, clock) VALUES "
                       f"('copperlantern', {V}, {q(c['id'])}, {q(c['nameFr'])}, {q(c['nameEn'])}, {c['sortOrder']}, false, '{{}}');")
        for it in v["menu"]["items"]:
            days = json.dumps(it["availableDays"]) if it["availableDays"] else None
            sp = json.dumps(it["specials"], separators=(",", ":")) if it["specials"] else None
            out.append(f"INSERT INTO catalog_items (tenant_id, venue_id, id, name_fr, name_en, description_fr, description_en, category_id,"
                       f" is_alcohol, active, deleted, clock, available_days, specials) VALUES ('copperlantern', {V}, {q(it['id'])},"
                       f" {q(it['nameFr'])}, {q(it['nameEn'])}, '', '', {q(it['categoryId'])}, {str(it['isAlcohol']).lower()}, true, false,"
                       f" '{{}}', {q(days)}, {q(sp)});")
            for x in it["variants"]:
                out.append(f"INSERT INTO catalog_variants (tenant_id, venue_id, id, item_id, label_fr, label_en, price_cents, sort_order,"
                           f" deleted, clock) VALUES ('copperlantern', {V}, {q(x['id'])}, {q(it['id'])}, {q(x['labelFr'])},"
                           f" {q(x['labelEn'])}, {x['priceCents']}, {x['sortOrder']}, false, '{{}}');")
        for r in v["floor"]["rooms"]:
            f = {"nameFr": r["nameFr"], "nameEn": r["nameEn"], "labelPrefix": r["id"][0].upper()}
            out.append(f"INSERT INTO floor_things (tenant_id, venue_id, entity, id, zone_id, fields) VALUES"
                       f" ('copperlantern', {V}, 'room', {q(r['id'])}, NULL, {q(json.dumps(f))}::jsonb);")
        for t in v["floor"]["tables"]:
            f = {"zoneId": t["zoneId"], "label": t["label"], "seats": t["seats"], "parentTableId": t["parentTableId"]}
            out.append(f"INSERT INTO floor_things (tenant_id, venue_id, entity, id, zone_id, fields) VALUES"
                       f" ('copperlantern', {V}, 'table', {q(t['id'])}, {q(t['zoneId'])}, {q(json.dumps(f))}::jsonb);")
        for s in v["staff"]:
            out.append(f"INSERT INTO store_staff (tenant_id, venue_id, id, name, role, active, deleted, updated_at) VALUES"
                       f" ('copperlantern', {V}, {q(s['id'])}, {q(s['name'])}, {q(s['role'])}, true, false, now());")
    # a week of test junk at both stores, plus today's real sales and the other tenant's
    junk = []
    for v, cid, at in (("vieux-port", 7, "2026-09-28 19:00-04"), ("vieux-port", 8, "2026-10-03 21:00-04"),
                       ("vieux-port", 900123, "2026-08-20 20:00-04"), ("express", 31, "2026-10-02 12:00-04"),
                       ("vieux-port", 9, "2026-10-04 12:30-04"), ("express", 32, "2026-10-04 11:45-04"),
                       ("plateau", 3, "2026-10-01 18:00-04")):
        gst = ", 'CAD'" if cid == 7 else ", 'USD'"
        junk.append(f"INSERT INTO checks (tenant_id, venue_id, check_id, status, closed_at, grand_total_cents, tax_included_cents,"
                    f" shift_id, taxes, currency) VALUES ('copperlantern', {q(v)}, {cid}, 'CLOSED', '{at}', 1083, 83, 5,"
                    f" '[{{\"code\":\"NC_SALES\",\"ratePercent\":\"7.25\",\"amountCents\":73}},{{\"code\":\"WAKE_FOOD\",\"ratePercent\":\"1\",\"amountCents\":10}}]'{gst});")
        junk.append(f"INSERT INTO check_lines (tenant_id, venue_id, check_id, line_id, item_id, qty, unit_price_cents, line_total_cents)"
                    f" VALUES ('copperlantern', {q(v)}, {cid}, {cid * 10}, 'wings', 1, 1000, 1000);")
        junk.append(f"INSERT INTO check_tenders (tenant_id, venue_id, tender_id, check_id, type, amount_applied_cents, tendered_at)"
                    f" VALUES ('copperlantern', {q(v)}, {cid * 10}, {cid}, 'CARD', 1083, '{at}');")
    junk += [
        "INSERT INTO checks (tenant_id, venue_id, check_id, status, closed_at, grand_total_cents) VALUES"
        " ('sagepoppy', 'sage-poppy', 7, 'CLOSED', '2026-09-28 19:00-04', 5000);",
        "INSERT INTO refunds (tenant_id, venue_id, refund_id, check_id, gross_cents, net_cents, tax_included_cents, created_at)"
        " VALUES ('copperlantern', 'vieux-port', 1, 7, 1083, 1000, 83, '2026-09-29 10:00-04');",
        "INSERT INTO refund_lines (tenant_id, venue_id, refund_id, line_id, item_id, qty, created_at)"
        " VALUES ('copperlantern', 'vieux-port', 1, 70, 'wings', 1, '2026-09-29 10:00-04');",
        "INSERT INTO shifts (tenant_id, venue_id, shift_id, status, opened_at, closed_at) VALUES"
        " ('copperlantern', 'vieux-port', 4, 'CLOSED', '2026-09-28 10:00-04', '2026-09-28 23:00-04'),"
        " ('copperlantern', 'vieux-port', 5, 'OPEN', '2026-10-03 10:00-04', NULL),"
        " ('copperlantern', 'express', 2, 'CLOSED', '2026-10-02 10:00-04', '2026-10-02 22:00-04');",
        "INSERT INTO cash_movements (tenant_id, venue_id, movement_id, direction, amount_cents, created_at) VALUES"
        " ('copperlantern', 'vieux-port', 3, 'OUT', 500, '2026-09-30 15:00-04');",
        "INSERT INTO events (tenant_id, venue_id, event_id, event_type, aggregate_type, aggregate_id, payload, store_seq,"
        " store_created_at, received_at) VALUES ('copperlantern', 'vieux-port', 'e-7', 'check.closed', 'check', '7',"
        " '{\"checkId\":7}', 1, '2026-09-28 19:00-04', now());",
    ]
    return "\n".join(out + junk + ["COMMIT;"]) + "\n"


def dsn_for(db: str) -> str:
    return re.sub(r"/[^/?]*(\?|$)", f"/{db}\\1", ADMIN, count=1) if ADMIN.count("/") >= 3 else ADMIN + "/" + db


def psql(dsn: str, sql: str) -> str:
    r = subprocess.run(["psql", dsn, "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-F", "\t"], input=sql,
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError(r.stderr)
    return r.stdout.strip()


@unittest.skipUnless(ADMIN and shutil.which("psql"), "set DEMO_RESEED_TEST_DSN to run against a local Postgres")
class DemoReseedDbTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        psql(ADMIN, f"DROP DATABASE IF EXISTS {DBNAME};\nCREATE DATABASE {DBNAME};")
        cls.dsn = dsn_for(DBNAME)
        for f in sorted((REPO / "cloud" / "migrations").glob("*.sql")):
            psql(cls.dsn, f.read_text())
        psql(cls.dsn, seed_sql())

    @classmethod
    def tearDownClass(cls):
        if not os.environ.get("DEMO_RESEED_KEEP_DB"):
            psql(ADMIN, f"DROP DATABASE IF EXISTS {DBNAME};")

    def run_tool(self, *args) -> tuple[int, str]:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            code = rs.main(["--dsn", self.dsn, "--today", TODAY, *args])
        return code, buf.getvalue()

    def snapshot(self) -> dict:
        tables = ["catalog_items", "catalog_variants", "catalog_categories", "floor_things", "store_staff", "events", "venues", "tenants"]
        snap = {t: psql(self.dsn, f"SELECT md5(string_agg(x::text, ',' ORDER BY x::text)) FROM {t} x;") for t in tables}
        snap["sagepoppy"] = psql(self.dsn, "SELECT count(*) FROM checks WHERE tenant_id = 'sagepoppy';")
        return snap

    def test_plan_then_yes_then_rerun(self):
        before = self.snapshot()
        counts = psql(self.dsn, "SELECT count(*) FROM checks")
        code, out = self.run_tool()
        self.assertEqual(0, code, out)
        self.assertIn("PLAN, nothing changed", out)
        # vieux-port: checks 7, 8 and the old seed's 900123 go; today's 9 stays
        self.assertRegex(out, r"checks 3 \(2026-08-20 to 2026-10-03")
        self.assertIn("KEEP     1 sale(s) from 2026-10-04 on ($10.83); open shifts: 1", out)
        self.assertEqual(counts, psql(self.dsn, "SELECT count(*) FROM checks"), "plan must change nothing")

        with tempfile.TemporaryDirectory() as backups:
            code, out = self.run_tool("--yes", "--backup-dir", backups)
            self.assertEqual(0, code, out)
            dumps = list(Path(backups).glob("pos_cloud-pre-reseed-*.sql.gz"))
            self.assertEqual(1, len(dumps), out)
            self.assertIn(str(dumps[0]), out)
        self.assertIn("matches what was generated", out)
        self.assertNotIn("MISMATCH", out)

        # kept: today's sales, the open shift, other stores and tenants, the menu, floor, staff, events
        self.assertEqual("1", psql(self.dsn, "SELECT count(*) FROM checks WHERE venue_id = 'vieux-port' AND check_id = 9"))
        self.assertEqual("1", psql(self.dsn, "SELECT count(*) FROM checks WHERE venue_id = 'express' AND check_id = 32"))
        self.assertEqual("1", psql(self.dsn, "SELECT count(*) FROM checks WHERE venue_id = 'plateau'"))
        self.assertEqual("OPEN", psql(self.dsn, "SELECT status FROM shifts WHERE venue_id = 'vieux-port' AND shift_id = 5"))
        self.assertEqual(before, self.snapshot())
        # gone: the old checks, their lines / tenders, the refund and its lines, the closed shifts, the movement
        self.assertEqual("0", psql(self.dsn, "SELECT count(*) FROM checks WHERE venue_id IN ('vieux-port','express')"
                                              " AND check_id IN (7, 8, 31, 900123)"))
        self.assertEqual("0", psql(self.dsn, "SELECT count(*) FROM check_lines WHERE check_id IN (7, 8, 31, 900123)"))
        self.assertEqual("0", psql(self.dsn, "SELECT count(*) FROM refunds WHERE refund_id = 1"))
        self.assertEqual("0", psql(self.dsn, "SELECT count(*) FROM refund_lines"))
        self.assertEqual("0", psql(self.dsn, "SELECT count(*) FROM shifts WHERE shift_id IN (2, 4)"))
        self.assertEqual("0", psql(self.dsn, "SELECT count(*) FROM cash_movements WHERE movement_id = 3"))
        # history: 60 shifts per store, every check in the window, nothing after yesterday
        self.assertEqual("60\t60", psql(self.dsn, "SELECT count(*) FILTER (WHERE venue_id = 'vieux-port'),"
                                                   " count(*) FILTER (WHERE venue_id = 'express') FROM shifts WHERE status = 'CLOSED'"))
        self.assertEqual("2026-08-05\t2026-10-03", psql(
            self.dsn, "SELECT min(closed_at AT TIME ZONE 'America/New_York')::date, max(closed_at AT TIME ZONE 'America/New_York')::date"
                      " FROM checks WHERE check_id >= 1000000"))
        totals = psql(self.dsn, "SELECT venue_id, count(*), sum(grand_total_cents) FROM checks WHERE check_id >= 1000000"
                                " GROUP BY 1 ORDER BY 1")

        # a rerun replaces only its own rows: the same totals, today's sale still there
        code, out = self.run_tool("--yes", "--no-backup")
        self.assertEqual(0, code, out)
        self.assertEqual(totals, psql(self.dsn, "SELECT venue_id, count(*), sum(grand_total_cents) FROM checks"
                                                " WHERE check_id >= 1000000 GROUP BY 1 ORDER BY 1"))
        self.assertEqual("1", psql(self.dsn, "SELECT count(*) FROM checks WHERE venue_id = 'vieux-port' AND check_id = 9"))

        code, out = self.run_tool("--verify-only")
        self.assertEqual(0, code, out)
        self.assertIn("last 7 days", out)
        self.assertIn("2026-10-03 Sat", out)

    def test_refuses_when_a_kept_sale_uses_the_reserved_range(self):
        psql(self.dsn, "INSERT INTO checks (tenant_id, venue_id, check_id, status, closed_at, grand_total_cents)"
                       " VALUES ('copperlantern', 'express', 1999999, 'CLOSED', '2026-10-04 13:00-04', 500);")
        try:
            code, out = self.run_tool()
            self.assertEqual(2, code, out)
            self.assertIn("REFUSE", out)
            code, out = self.run_tool("--yes", "--no-backup")
            self.assertEqual(2, code, out)
        finally:
            psql(self.dsn, "DELETE FROM checks WHERE venue_id = 'express' AND check_id = 1999999;")

    def test_transaction_rolls_back_on_error(self):
        before = psql(self.dsn, "SELECT count(*), coalesce(sum(grand_total_cents), 0) FROM checks")
        gen_sql = rs.render_sql  # break the SQL after the deletes: nothing may stick
        try:
            rs.render_sql = lambda *a: gen_sql(*a).replace("COMMIT;", "SELECT 1/0;\nCOMMIT;")
            with self.assertRaises(SystemExit):
                self.run_tool("--yes", "--no-backup")
        finally:
            rs.render_sql = gen_sql
        self.assertEqual(before, psql(self.dsn, "SELECT count(*), coalesce(sum(grand_total_cents), 0) FROM checks"))


if __name__ == "__main__":
    unittest.main()
