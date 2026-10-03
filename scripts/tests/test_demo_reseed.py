"""Unit tests for scripts/demo-reseed.py's generator (no database, no network).

Run: python3 -m unittest discover -s scripts/tests
"""

from __future__ import annotations

import datetime as dt
import hashlib
import importlib.util
import json
import re
import sys
import unittest
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("demo_reseed", REPO / "scripts" / "demo-reseed.py")
rs = importlib.util.module_from_spec(_spec)
sys.modules["demo_reseed"] = rs
_spec.loader.exec_module(rs)

FIXTURE = json.loads((REPO / "scripts" / "tests" / "fixtures" / "copperlantern-catalog.json").read_text())
FIRST = dt.date(2026, 8, 5)
DAYS = 28


def store(vid: str) -> "rs.Store":
    return rs.store_from_doc(next(v for v in FIXTURE["venues"] if v["id"] == vid))


def generate(vid: str, seed: int = 7, days: int = DAYS) -> "rs.Generator":
    return rs.Generator(store(vid), seed, FIRST, days).generate()


PUB = generate("vieux-port")
COUNTER = generate("express")


def all_checks(g):
    return [c for sh in g.shifts for c in sh.checks]


def ny(y, mo, d, h, mi):
    return dt.datetime(y, mo, d, h, mi, tzinfo=rs.ZoneInfo("America/New_York"))


class TaxMath(unittest.TestCase):
    """TaxPolicy.AddedTaxes with TaxRounding.COMBINED (server sdk/Policies.kt), as CopperLanternRaleighTest pins it."""

    def amounts(self, base):
        return [t["amountCents"] for t in rs.tax_lines(base)]

    def test_store_examples(self):
        self.assertEqual([73, 10], self.amounts(1000))     # $10.00 food: 0.825 -> 0.83
        self.assertEqual([147, 20], self.amounts(2025))    # $20.25 pitcher: 1.670625 -> 1.67
        self.assertEqual([725, 100], self.amounts(10_000))
        self.assertEqual([4, 0], self.amounts(50))          # once at 8.25%: 4.125 -> 4, not 4 + 1
        self.assertEqual([140, 19], self.amounts(1925))    # 158.8125 -> 159; NC has the larger remainder
        self.assertEqual([15, 2], self.amounts(200))       # 16.5 -> 17 (half up); the cent goes to NC

    def test_combined_rounding_on_every_subtotal(self):
        for base in range(1, 30_001):
            nc, wake = self.amounts(base)
            exact = Decimal(base) * Decimal("8.25") / 100
            self.assertEqual(int(exact.quantize(Decimal(1), ROUND_HALF_UP)), nc + wake, base)
            # each part is at most a cent from its own exact share
            self.assertLessEqual(abs(Decimal(nc) - Decimal(base) * Decimal("7.25") / 100), 1)
            self.assertLessEqual(abs(Decimal(wake) - Decimal(base) / 100), 1)

    def test_breakdown_shape_is_the_stores(self):
        t = rs.tax_lines(1000)
        self.assertEqual(["NC_SALES", "WAKE_FOOD"], [x["code"] for x in t])
        self.assertEqual(["7.25", "1"], [x["ratePercent"] for x in t])
        self.assertEqual(["NCDOR", "Wake County"], [x["remitTo"] for x in t])
        self.assertEqual("", t[0]["registrationNumber"])

    def test_every_generated_check_is_taxed_once_on_its_subtotal(self):
        per_line_differs = 0
        for c in all_checks(PUB) + all_checks(COUNTER):
            self.assertEqual(c.subtotal, sum(ln.unit * ln.qty for ln in c.lines))
            self.assertEqual(c.taxes, rs.tax_lines(c.subtotal))
            self.assertEqual(c.tax, sum(t["amountCents"] for t in c.taxes))
            self.assertEqual(c.total, c.subtotal + c.tax)
            line_level = sum(sum(t["amountCents"] for t in rs.tax_lines(ln.total)) for ln in c.lines)
            per_line_differs += line_level != c.tax
        self.assertGreater(per_line_differs, 100, "check-level rounding must differ from per-line rounding somewhere")


class CashRounding(unittest.TestCase):
    def test_nickel_matches_the_store(self):
        self.assertEqual(1085, rs.nickel(1083))
        self.assertEqual(2190, rs.nickel(2192))
        self.assertEqual(10_775, rs.nickel(10_775))
        self.assertEqual(2, rs.cash_adjustment(1083))
        self.assertEqual(-2, rs.cash_adjustment(2192))
        self.assertEqual(-1085, rs.nickel(-1083))  # a refund rounds like a sale
        for c, want in ((1, 0), (2, 0), (3, 5), (4, 5), (6, 5), (7, 5), (8, 10), (9, 10)):
            self.assertEqual(want, rs.nickel(c))

    def test_cash_tenders_round_and_change_adds_up(self):
        n = 0
        for c in all_checks(PUB) + all_checks(COUNTER):
            for t in c.tenders:
                self.assertEqual(c.total, sum(x.applied for x in c.tenders))
                if t.type == "CASH":
                    n += 1
                    self.assertEqual(rs.cash_adjustment(c.total), t.rounding)
                    self.assertEqual(c.total + t.rounding, t.tendered - t.change)
                    self.assertGreaterEqual(t.change, 0)
                    self.assertEqual(0, t.tip)
                else:
                    self.assertEqual((0, 0), (t.rounding, t.change))
        self.assertGreater(n, 100)


class Specials(unittest.TestCase):
    burger = next(i for i in store("vieux-port").items if i.id == "lantern-burger")
    lager = next(i for i in store("vieux-port").items if i.id == "lantern-lager")

    def price(self, item, vid, at):
        regular = next(v.price for v in item.variants if v.id == vid)
        return rs.price_at(item.specials, vid, regular, rs.moment(at))[0]

    def test_happy_hour_window_is_half_open_and_weekdays_only(self):
        pint = "lantern-lager:pint"
        self.assertEqual(750, self.price(self.lager, pint, ny(2026, 9, 30, 15, 59)))  # Wednesday
        self.assertEqual(500, self.price(self.lager, pint, ny(2026, 9, 30, 16, 0)))
        self.assertEqual(500, self.price(self.lager, pint, ny(2026, 9, 30, 17, 59)))
        self.assertEqual(750, self.price(self.lager, pint, ny(2026, 9, 30, 18, 0)))
        self.assertEqual(750, self.price(self.lager, pint, ny(2026, 10, 3, 16, 30)))  # Saturday
        self.assertEqual(2025, self.price(self.lager, "lantern-lager:pitcher", ny(2026, 9, 30, 16, 30)))  # not on special

    def test_tuesday_burger_and_the_4am_business_day(self):
        self.assertEqual(1495, self.price(self.burger, "lantern-burger:regular", ny(2026, 9, 29, 12, 0)))  # Tuesday
        self.assertEqual(1925, self.price(self.burger, "lantern-burger:regular", ny(2026, 9, 30, 12, 0)))  # Wednesday
        # 1:30 a.m. Wednesday is still Tuesday night
        self.assertEqual(1495, self.price(self.burger, "lantern-burger:regular", ny(2026, 9, 30, 1, 30)))
        self.assertEqual("tue", rs.moment(ny(2026, 9, 30, 3, 59)).day)
        self.assertEqual("wed", rs.moment(ny(2026, 9, 30, 4, 0)).day)

    def test_generated_lines_have_special_prices_exactly_in_their_windows(self):
        specials = 0
        for g in (PUB, COUNTER):
            for c in all_checks(g):
                for ln in c.lines:
                    m = rs.moment(ln.added_at)
                    want, sp = rs.price_at(ln.item.specials, ln.variant.id, ln.variant.price, m)
                    self.assertEqual(want, ln.unit, (ln.item.id, ln.added_at))
                    self.assertEqual(sp, ln.special)
                    if ln.special:
                        specials += 1
                        self.assertTrue(rs.in_force(ln.special, m))
                        self.assertLess(ln.unit, ln.variant.price)
                        if ln.special.start:  # happy hour: 16:00-18:00 on a weekday
                            self.assertTrue(16 * 60 <= m.minute < 18 * 60 and m.day in ("mon", "tue", "wed", "thu", "fri"))
                        else:
                            self.assertEqual("tue", m.day)
                    else:
                        self.assertEqual(ln.variant.price, ln.unit)
        self.assertGreater(specials, 200)
        kinds = {ln.variant.id for g in (PUB, COUNTER) for c in all_checks(g) for ln in c.lines if ln.special}
        self.assertEqual({"lantern-burger:regular", "lantern-lager:pint", "cab-merlot:glass",
                          "double-cheeseburger:regular", "lantern-lager:16oz", "pinot-noir:glass"}, kinds)


class DayOnlyItems(unittest.TestCase):
    def test_prime_rib_on_friday_and_saturday_and_the_roast_on_sunday_only(self):
        seen = {}
        for c in all_checks(PUB):
            for ln in c.lines:
                if ln.item.id in ("prime-rib", "sunday-roast"):
                    seen.setdefault(ln.item.id, set()).add(rs.moment(ln.added_at).day)
        self.assertEqual({"fri", "sat"}, seen["prime-rib"])
        self.assertEqual({"sun"}, seen["sunday-roast"])

    def test_sales_stay_inside_the_business_day(self):
        for g in (PUB, COUNTER):
            for sh in g.shifts:
                for c in sh.checks:
                    self.assertEqual(sh.date, c.closed_at.date())
                    self.assertEqual(sh.date, rs.moment(c.closed_at).date)  # midnight day == 4 a.m. day
                    self.assertTrue(dt.time(10, 30) <= c.opened_at.timetz().replace(tzinfo=None) <= c.closed_at.time())
                    self.assertLessEqual(sh.opened_at, c.opened_at)
                    self.assertLessEqual(c.closed_at, sh.closed_at)


class Determinism(unittest.TestCase):
    def sql(self, seed):
        stores = [store("vieux-port"), store("express")]
        gens = {s.id: rs.Generator(s, seed, FIRST, 5).generate() for s in stores}
        ends = {s.id: rs.window_end(s, dt.date(2026, 8, 10), False) for s in stores}
        return rs.render_sql(stores, gens, ends)

    def test_same_seed_same_sql_other_seed_other_sql(self):
        a, b, c = self.sql(42), self.sql(42), self.sql(43)
        self.assertEqual(hashlib.sha256(a.encode()).hexdigest(), hashlib.sha256(b.encode()).hexdigest())
        self.assertNotEqual(a, c)

    def test_plan_figures_are_deterministic(self):
        self.assertEqual(generate("vieux-port", 9, 3).expected(), generate("vieux-port", 9, 3).expected())
        self.assertNotEqual(generate("vieux-port", 9, 3).expected(), generate("vieux-port", 10, 3).expected())


class Shape(unittest.TestCase):
    def test_volumes(self):
        for sh in PUB.shifts:
            closed = [c for c in sh.checks if c.status == "CLOSED"]
            self.assertTrue(55 <= len(closed) <= 146, len(closed))
            self.assertTrue(1 <= sum(c.source == "CARRY_OUT" for c in sh.checks) <= 6)
        for sh in COUNTER.shifts:
            self.assertTrue(78 <= sum(c.status == "CLOSED" for c in sh.checks) <= 200)
        fri = [len(sh.checks) for sh in PUB.shifts if sh.date.weekday() in (4, 5)]
        mon = [len(sh.checks) for sh in PUB.shifts if sh.date.weekday() == 0]
        self.assertGreater(min(fri), max(mon))
        f = PUB.facts()
        self.assertTrue(0.64 <= f["cardShare"] <= 0.76, f["cardShare"])
        self.assertTrue(1.0 <= f["covers"] <= 6)
        cf = COUNTER.facts()
        self.assertTrue(0.34 <= cf["kiosk"] / cf["counterTotal"] <= 0.46)
        self.assertGreater(cf["cardShare"], 0.8)

    def test_average_checks(self):
        pub = PUB.expected()["totals"]["avgCheckCents"]
        counter = COUNTER.expected()["totals"]["avgCheckCents"]
        self.assertTrue(4800 <= pub <= 6000, pub)        # ~$22-28 a head with drinks
        self.assertTrue(1400 <= counter <= 1700, counter)  # a burger, fries and a drink, mostly alone

    def test_lunch_and_dinner_peaks(self):
        hours = PUB.expected()["hourly"]
        self.assertGreater(hours["13"]["checkCount"], hours["15"]["checkCount"])
        self.assertGreater(hours["19"]["checkCount"], hours["16"]["checkCount"])
        ch = COUNTER.expected()["hourly"]
        self.assertEqual("12", max(ch, key=lambda h: ch[h]["checkCount"]))

    def test_card_tips_15_to_22_percent_at_the_pub(self):
        tips = [(t.tip, t.applied) for c in all_checks(PUB) if c.source == "TABLE" for t in c.tenders
                if t.type == "CARD" and t.applied >= 3000]
        self.assertGreater(sum(t > 0 for t, _ in tips) / len(tips), 0.93)  # a few guests leave nothing on the card
        tips = [x for x in tips if x[0] > 0]
        self.assertTrue(all(0.12 <= t / a <= 0.25 for t, a in tips), [t / a for t, a in tips if not 0.12 <= t / a <= 0.25][:5])

    def test_ids_are_in_the_reserved_range_and_unique(self):
        for g in (PUB, COUNTER):
            ids = {"check": [], "tender": [], "line": [], "shift": [], "refund": []}
            for sh in g.shifts:
                ids["shift"].append(sh.shift_id)
                ids["refund"] += [r.refund_id for r in sh.refunds]
                for c in sh.checks:
                    ids["check"].append(c.check_id)
                    ids["tender"] += [t.tender_id for t in c.tenders]
                    ids["line"] += [ln.line_id for ln in c.lines]
            for kind, xs in ids.items():
                lo, hi = rs.RANGES[kind]
                self.assertEqual(len(xs), len(set(xs)), kind)
                self.assertTrue(all(lo <= x <= hi for x in xs), kind)
            # what people see starts at #20,000 and counts up; line / tender ids are far from any store's
            self.assertEqual(20_000, min(ids["check"]))
            self.assertEqual(20_000, min(ids["shift"]))
            self.assertGreaterEqual(min(ids["line"] + ids["tender"]), 1_000_000)
        self.assertLess(rs.NUM_MIN + 366 * 200, rs.NUM_MAX, "a year of the busiest counter fits the range")

    def test_voids_refunds_and_the_z_report(self):
        voids = [c for c in all_checks(PUB) if c.status == "VOID"]
        self.assertTrue(voids)
        self.assertTrue(all(not c.tenders and c.void_reason and c.voided_by for c in voids))
        refunds = [r for sh in PUB.shifts for r in sh.refunds]
        self.assertTrue(1 <= len(refunds) / (DAYS / 7) <= 2.6, len(refunds))
        for sh in PUB.shifts + COUNTER.shifts:
            closed = [c for c in sh.checks if c.status == "CLOSED"]
            self.assertEqual(sum(c.total for c in closed), sh.revenue)
            self.assertEqual(len(closed), sh.count)
            cash = [t for c in closed for t in c.tenders if t.type == "CASH"]
            out = sum(m.amount for m in sh.movements if m.direction == "OUT")
            back = sum(r.gross + r.rounding for r in sh.refunds if r.tender_type == "CASH")
            self.assertEqual(sh.float_cents + sum(t.applied + t.rounding for t in cash) - out - back, sh.expected_cash)
            self.assertEqual(sum(b["amountCents"] for b in sh.breakdown), sum(t.applied for c in closed for t in c.tenders))
            for r in sh.refunds:
                self.assertEqual("CLOSED", r.check.status)
                self.assertEqual((r.check.total, r.check.subtotal, r.check.tax), (r.gross, r.net, r.tax))
                self.assertEqual(sh.date, r.at.date())
                self.assertGreater(r.at, r.check.closed_at)


class Safety(unittest.TestCase):
    def test_refuses_other_tenants_and_stores(self):
        for argv in (["--tenant", "sagepoppy", "--menu-json", "x"], ["--tenant", "acme", "--menu-json", "x"],
                     ["--stores", "sage-poppy", "--menu-json", "x"], ["--stores", "vieux-port,vieux-port", "--menu-json", "x"],
                     ["--ssh", "h", "--no-backup"]):
            with self.assertRaises(rs.Refused, msg=argv):
                rs.parse_args(argv)
        self.assertEqual(2, rs.main(["--tenant", "sagepoppy", "--menu-json", "x"]))

    def test_sql_touches_only_sales_tables_in_one_transaction(self):
        sql = Determinism().sql(1)
        self.assertEqual(1, sql.count("\nBEGIN;"))
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertEqual(1, sql.count("COMMIT;"))
        deleted = set(re.findall(r"DELETE FROM (\w+)", sql))
        inserted = set(re.findall(r"INSERT INTO (\w+)", sql))
        self.assertEqual({"checks", "check_lines", "check_tenders", "refunds", "refund_lines", "shifts", "cash_movements",
                          "fuel_sales"}, deleted)
        self.assertEqual({"checks", "check_lines", "check_tenders", "refunds", "shifts", "cash_movements"}, inserted)
        self.assertNotRegex(sql, r"(?i)\b(UPDATE|TRUNCATE|DROP TABLE (?!reseed_)|ALTER)\b")
        self.assertIn("tenant_id = 'copperlantern' AND venue_id = 'vieux-port'", sql)
        # the guard: a number in the demo range that a store sent (it is in the ingest log) aborts the run
        self.assertIn("c.check_id::text IN (SELECT ev.aggregate_id FROM events ev", sql)
        self.assertIn("RAISE EXCEPTION 'store vieux-port: numbers 20000-99999 hold a sale or shift that is not ours'", sql)
        self.assertNotIn("sagepoppy", sql)
        # every DELETE is scoped to the tenant and one store
        for stmt in re.findall(r"DELETE FROM [^;]+;", sql):
            self.assertIn("tenant_id = 'copperlantern'", stmt)
            self.assertRegex(stmt, r"venue_id = '(vieux-port|express)'")

    def test_open_shifts_are_kept(self):
        p = rs.wipe_predicates(store("vieux-port"), rs.window_end(store("vieux-port"), dt.date(2026, 10, 4), False))
        self.assertIn("status <> 'OPEN'", p["shifts"])
        self.assertIn("'2026-10-04 00:00:00-04:00'::timestamptz", p["checks"])
        p2 = rs.wipe_predicates(store("vieux-port"), rs.window_end(store("vieux-port"), dt.date(2026, 10, 4), True))
        self.assertIn("'2026-10-05 00:00:00-04:00'::timestamptz", p2["checks"])


if __name__ == "__main__":
    unittest.main()
