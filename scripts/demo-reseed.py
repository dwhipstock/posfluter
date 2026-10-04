#!/usr/bin/env python3
"""Give the hosted Copper Lantern portal a clean, realistic sales history for a demo.

What it does, per store (default: vieux-port = Glenwood South, and express):

  1. WIPE the synced sales the cloud holds for the store before today 00:00
     store time (``--include-today``: also today), plus every row this script
     ever inserted (its number range, any date -- today's too).
  2. GENERATE ``--days`` (default 365: a year) of history ending yesterday
     from the store's CURRENT cloud menu (prices, sizes, categories, day-only
     items, day and happy-hour prices), with the store's own tax and
     cash-rounding math; ``--today-until HH:MM`` also generates today's sales
     up to that store time (typical of the weekday), next to the live ones.
  3. VERIFY by running the reports' own SQL (the portal Dashboard and Reports
     read these sums) and printing totals per store and per day.

Plan mode is the default: it prints exactly what would be deleted and inserted
and changes nothing. ``--yes`` does it: first a full ``pg_dump`` (gzip) to
``~/backups`` on the server, then everything in ONE transaction. ``--check``
is read-only: each store's catalog as the tool sees it (specials, day-only
items, floor, staff, the tax of the latest sale, sales on file, highest
check number).

Tables it deletes from and inserts into (and nothing else):

  checks          the closed and voided sales (reports, dashboard, journal)
  check_lines     their items (items / categories reports)
  check_tenders   their payments (payments report, cash rounding)
  refunds         refunds (netted out of sales and tax)
  refund_lines    products a by-line refund returned (stock) -- delete only
  shifts          Z-reports; the card tips live in their tender_breakdown JSON
  cash_movements  paid in / paid out (cash report, drawer math)
  fuel_sales      fuellings (always 0 rows here; a sale table, so it is wiped)

There is no separate tips table and no report cache in the cloud: the reports
sum these tables on every request (cloud/api reports/Reports.kt). It never
touches the menu (catalog_*, item_photos, menu_*), the floor (floor_things),
staff, settings, the raw event log (events: a store replaying an event it
already sent is then still a no-op), ingest_quarantine, sync clocks or feeds,
devices, API keys, or the venues rows. OPEN shifts are kept (a tablet's
running shift).

Where the store's facts come from (so the history looks like what it sends):
  tax       NC 7.25% + Wake 1% on the pre-tax subtotal, rounded ONCE at 8.25%
            half-up and split by largest remainder (TaxRounding.COMBINED,
            sdk/Policies.kt) -- check level, never per line
  cash      rounds to the nearest nickel (RoundingPolicy.NICKEL); the exact
            total is the sale, the rounding rides on the cash tender
  specials  the store's MenuSpecials rules: business days start at 4 a.m.,
            windows are half-open, the cheaper special wins; a line is priced
            when it is added
  counter   Express sales sit on the "counter-1" register; kiosk orders are
            opened by "kiosk"; Glenwood carry-out on "carry-out-1"
  numbers   generated checks, shifts, refunds and cash movements are
            numbered from 20,000 (to 499,999), so the journal reads like a
            store's own (#20,417); line and tender ids, which nobody sees, are
            in [1,000,000, 1,999,999]. Real stores number from 1 and are in
            the low thousands. The guard: the run aborts (plan and, again,
            inside the transaction) if a store ever SENT a check, shift,
            refund or cash movement numbered in that range -- its event is in
            the ingest log; rows this script writes never have one. Then the
            store's own numbering reached the demo range and nothing there can
            be wiped. So everything numbered 20,000+ without an event is ours
            (today's generated sales included) and is replaced on a rerun,
            while live sales (low numbers, with events) are never touched.
            The plan prints each store's highest check number and its headroom.

The year's shape: slow growth (+10% a year), seasons (Glenwood's patio
summer, a January dip, December holidays; Express steadier, lunch-led), the
week around Thanksgiving busier, both stores closed on Thanksgiving Day and
Christmas Day, four local-event Saturdays at Glenwood (+40%), weather-like
day noise, Monday the slowest weekday. The specials (and the day-only dishes
that came with them) exist from ``--specials-since`` (default: ten weeks
before today); before that the same items sell at the menu price with no
lift. While they run, the ones that work lift their item (Tuesday burgers
about +90-150% vs other weekdays; happy-hour drinks about +60-100% vs the
same hours at the weekend and, per check, vs 2-4 pm), a happy hour brings a
few more guests in, and one does not work (Glenwood's Cabernet Merlot glass,
about +5%). The plan prints each special's lift and verdict.

Performance: one transaction of COPY blocks (``--sql-style insert`` writes
plain INSERTs instead), sent over ssh with compression. A year of both
stores (~35k + ~46k checks; ``--volume 1.6`` for ~55k + ~73k) is ~80 MB
(~125 MB) of SQL and applies in seconds.

Sales are generated between 10:30 and 23:59 store time, so the reports'
midnight business day and the specials' 4 a.m. business day name the same
date for every generated sale. Dine-in / take-out and kiosk / counter are
not columns in the cloud (the store keeps them); the plan prints the mix.
Tips are not in the cloud's sales tables either: they are in each shift's
tender_breakdown (tipCents), as the store's Z-report sends them.

Usage (Sunday: plan first, read it, then the same command with --yes):

  # plan (changes nothing)
  scripts/demo-reseed.py --ssh ubuntu@<box ip> --ssh-key ~/.ssh/<key>.pem
  # do it: backup, wipe + insert in one transaction, verify
  scripts/demo-reseed.py --ssh ubuntu@<box ip> --ssh-key ~/.ssh/<key>.pem --yes
  # read the SQL first
  scripts/demo-reseed.py --ssh ... --dry-run-sql /tmp/reseed.sql
  # numbers only, again later
  scripts/demo-reseed.py --ssh ... --verify-only
  # read-only look at the hosted catalog as the tool sees it (specials, day-only items, staff, tax)
  scripts/demo-reseed.py --ssh ... --check
  # demo morning: also today's sales up to 11:30, so the Today dashboard is not empty
  scripts/demo-reseed.py --ssh ... --yes --today-until 11:30

  The ssh target, key and container also come from DEMO_RESEED_SSH,
  DEMO_RESEED_SSH_KEY and DEMO_RESEED_CONTAINER. The Postgres container is
  found by name (copper-lantern-manager-db-1 or copperlantern-portal-db-1)
  unless --container names it. ``--dsn postgresql://...`` (or
  DEMO_RESEED_DSN) talks to a local Postgres instead (tests, rehearsal).
  ``--menu-json FILE`` generates offline from a catalog file (no database).

Options: --stores vieux-port,express  --days 365  --seed N  --today YYYY-MM-DD
         --include-today  --today-until HH:MM  --specials-since YYYY-MM-DD
         --volume 1.0  --sql-style copy|insert  --plan-json FILE (expected totals, for tests)

Tests: python3 -m unittest scripts/tests/test_demo_reseed.py (generator);
cloud/api DemoReseedReportTest (the SQL against the migrated schema, read
back through the real report endpoints); scripts/tests/test_demo_reseed_db.py
(full plan / --yes / verify against a local Postgres, when
DEMO_RESEED_TEST_DSN is set).
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import os
import random
import re
import shlex
import subprocess
import sys
import time
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

TENANT = "copperlantern"
DEFAULT_STORES = ("vieux-port", "express")
KNOWN_STORES = ("vieux-port", "express", "plateau")
# Numbers people see (journal check #, shift, refund, cash movement): from 20,000, one sequence per
# store. Real stores number from 1 and are in the low thousands; the run refuses if a store's own
# numbers ever reached this range (see wipe_predicates "clash").
NUM_MIN = 20_000
NUM_MAX = 499_999
# Ids nobody sees (line and tender ids): far above anything a store makes.
ID_MIN = 1_000_000
ID_MAX = 1_999_999
RANGES = {"check": (NUM_MIN, NUM_MAX), "shift": (NUM_MIN, NUM_MAX), "refund": (NUM_MIN, NUM_MAX),
          "movement": (NUM_MIN, NUM_MAX), "line": (ID_MIN, ID_MAX), "tender": (ID_MIN, ID_MAX)}
DEFAULT_SEED = 20261008
DAY_STARTS_AT_HOUR = 4  # sdk/MenuSpecials.kt
DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
CONTAINER_NAMES = ("copper-lantern-manager-db-1", "copperlantern-portal-db-1")

# CopperLanternConfig.NC_TAXES: code, labelFr, labelEn, ratePercent, remitTo
NC_TAXES = (
    ("NC_SALES", "NC sales tax", "NC sales tax", "7.25", "NCDOR"),
    ("WAKE_FOOD", "Wake prepared food tax", "Wake prepared food tax", "1", "Wake County"),
)

# The store's sale locations off the floor (orders/SaleLocations.kt, restaurant/QuickServe.kt)
CARRY_OUT = ("carry-out-1", "Carry-out", "carry-out", "À emporter", "Carry-out")
COUNTER = ("counter-1", "1", "counter", "Comptoir", "Counter")
KIOSK_USER = "kiosk"  # QuickServe.place(): a kiosk order is opened by "kiosk"

# Glenwood's floor as CopperLanternSeed lays it out: used only when the cloud has
# no floor for the store yet (floor_things empty). (id, zone, label, seats)
SEED_ROOMS = (("upper", "Salle à manger et bar", "Dining Room & Bar"), ("outside", "Terrasse", "Patio"),
              ("lower", "Salle de jeux", "Games Room"))
SEED_TABLES = tuple(
    [(f"seat-u{i}", "upper", f"U-{i}", 1) for i in range(1, 8)]
    + [("t1", "upper", "U-8", 6), ("t1-1", "upper", "U-9", 4), ("t2", "upper", "U-10", 6), ("u2-2", "upper", "U-11", 4),
       ("u3", "upper", "U-12", 8), ("u3-3", "upper", "U-13", 4), ("u4", "upper", "U-14", 4), ("u4-4", "upper", "U-15", 4),
       ("u5", "upper", "U-16", 4), ("u5-5", "upper", "U-17", 4),
       ("t3", "outside", "O-1", 4), ("t4", "outside", "O-2", 4), ("o3", "outside", "O-3", 4), ("o4", "outside", "O-4", 6)]
    + [(f"low{i}", "lower", f"L-{i}", 4) for i in range(1, 9)])


class Refused(SystemExit):
    """A safety refusal: printed, exit code 2."""

    def __init__(self, message: str):
        super().__init__(2)
        self.message = message


# ------------------------------------------------------------------ store math

def thousandths(rate: str) -> int:
    """'7.25' -> 7250 (thousandths of a percent), exactly."""
    return int(Decimal(rate) * 1000)


def tax_lines(base: int, taxes=NC_TAXES) -> list[dict]:
    """The store's added taxes on a pre-tax [base] (TaxPolicy.AddedTaxes, TaxRounding.COMBINED):
    the combined rate rounded half-up ONCE, then split into the components by the largest
    remainder of their exact shares (ties to the first). The store's taxes JSON shape."""
    rates = [thousandths(t[3]) for t in taxes]
    if base > 0 and len(rates) > 1:
        total = (base * sum(rates) * 2 + 100_000) // 200_000
        cents = [base * r // 100_000 for r in rates]
        rem = [base * r % 100_000 for r in rates]
        order = sorted(range(len(rates)), key=lambda i: (-rem[i], i))
        left, k = total - sum(cents), 0
        while left > 0:
            cents[order[k % len(order)]] += 1
            left -= 1
            k += 1
    else:  # each half-up on its own (one tax, or nothing to tax)
        cents = [(abs(base) * r * 2 + 100_000) // 200_000 * (1 if base >= 0 else -1) for r in rates]
    out = []
    for (code, fr, en, rate, remit), c in zip(taxes, cents):
        row = {"code": code, "labelFr": fr, "labelEn": en, "ratePercent": rate, "registrationNumber": ""}
        if remit:
            row["remitTo"] = remit
        row["amountCents"] = c
        out.append(row)
    return out


def nickel(cents: int) -> int:
    """RoundingPolicy.NICKEL: nearest 5 cents, symmetric around zero."""
    a = abs(cents)
    r = (a + 2) // 5 * 5
    return -r if cents < 0 else r


def cash_adjustment(cents: int) -> int:
    return nickel(cents) - cents


# ------------------------------------------------------------------ specials (sdk/MenuSpecials.kt)

@dataclasses.dataclass(frozen=True)
class Special:
    days: tuple
    start: str | None
    end: str | None
    label: str | None
    prices: dict


@dataclasses.dataclass(frozen=True)
class Moment:
    date: dt.date
    day: str
    minute: int


def minutes(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def moment(local: dt.datetime) -> Moment:
    """A venue-local wall time -> its business date (4 a.m. day start), weekday code, minutes since that date's midnight."""
    naive = local.replace(tzinfo=None)
    date = (naive - dt.timedelta(hours=DAY_STARTS_AT_HOUR)).date()
    minute = (naive.date() - date).days * 1440 + naive.hour * 60 + naive.minute
    return Moment(date, DAYS[date.weekday()], minute)


def in_force(sp: Special, m: Moment) -> bool:
    if m.day not in sp.days:
        return False
    if sp.start is None or sp.end is None:
        return True
    s = minutes(sp.start)
    if s < DAY_STARTS_AT_HOUR * 60:
        s += 1440
    e = minutes(sp.end)
    while e <= s:
        e += 1440
    return s <= m.minute < e


def price_at(specials, variant_id: str, regular: int, m: Moment) -> tuple[int, Special | None]:
    """The store's priceAt: the cheapest special in force for this size, when cheaper than the menu price."""
    live = [sp for sp in specials if variant_id in sp.prices and in_force(sp, m)]
    if not live:
        return regular, None
    sp = min(live, key=lambda s: s.prices[variant_id])
    p = sp.prices[variant_id]
    return (p, sp) if p < regular else (regular, None)


def sold_on(available_days, m: Moment) -> bool:
    return not available_days or m.day in available_days


# ------------------------------------------------------------------ menu model

@dataclasses.dataclass
class Variant:
    id: str
    label_fr: str
    label_en: str
    price: int
    sort: int


@dataclasses.dataclass
class Item:
    id: str
    name_fr: str
    name_en: str
    category_id: str
    is_alcohol: bool
    variants: list
    available_days: tuple
    specials: tuple
    role: str = "main"


@dataclasses.dataclass
class Store:
    id: str
    name: str
    tz: str
    currency: str
    items: list
    categories: dict
    tables: list  # (table_id, label, zone_id, zone_fr, zone_en, seats)
    staff: list   # (id, name, role)
    counter: bool
    last_taxes: list | None = None

    @property
    def zone(self) -> ZoneInfo:
        return ZoneInfo(self.tz)


DRINK_WORDS = ("beer", "cider", "wine", "cocktail", "drink", "soda", "sake", "beverage", "bar", "coffee", "tea", "spirit")
DESSERT_WORDS = ("dessert", "sweet", "cake")
SIDE_WORDS = ("starter", "appet", "side", "fries", "snack", "share")


def role_of(item_cat: str, cat_names: str, is_alcohol: bool) -> str:
    text = f"{item_cat} {cat_names}".lower()
    if is_alcohol or any(w in text for w in DRINK_WORDS):
        return "drink"
    if any(w in text for w in DESSERT_WORDS):
        return "dessert"
    if any(w in text for w in SIDE_WORDS):
        return "side"
    return "main"


def parse_days(raw) -> tuple:
    if raw is None:
        return ()
    if isinstance(raw, str):
        raw = json.loads(raw) if raw.strip() else None
    days = tuple(d for d in DAYS if d in {str(x).strip().lower()[:3] for x in (raw or [])})
    return () if len(days) == 7 else days


def parse_specials(raw) -> tuple:
    if raw is None:
        return ()
    if isinstance(raw, str):
        raw = json.loads(raw) if raw.strip() else []
    out = []
    for o in raw or []:
        try:
            prices = {k: int(v) for k, v in (o.get("prices") or {}).items() if k}
            days = tuple(d for d in DAYS if d in {str(x).lower()[:3] for x in o.get("days") or []})
            if days and prices:
                out.append(Special(days, o.get("from"), o.get("to"), o.get("label"), prices))
        except (TypeError, ValueError, AttributeError):
            continue  # the store drops a bad special too (lenient read)
    return tuple(out)


def store_from_doc(v: dict, staff_fallback=True) -> Store:
    """One venue of a catalog document (the --menu-json shape, or what load_catalog builds from the cloud)."""
    cats = {c["id"]: c for c in v["menu"]["categories"]}
    items = []
    for it in v["menu"]["items"]:
        if it.get("active") is False or it.get("deleted"):
            continue
        variants = [Variant(x["id"], x.get("labelFr") or "", x.get("labelEn") or "", int(x["priceCents"]), int(x.get("sortOrder", 0)))
                    for x in it.get("variants") or [] if not x.get("deleted") and int(x["priceCents"]) > 0]
        if not variants:
            continue
        variants.sort(key=lambda x: (x.sort, x.id))
        cat = cats.get(it["categoryId"], {})
        item = Item(it["id"], it.get("nameFr") or it.get("nameEn") or it["id"], it.get("nameEn") or it.get("nameFr") or it["id"],
                    it["categoryId"], bool(it.get("isAlcohol")), variants,
                    parse_days(it.get("availableDays")), parse_specials(it.get("specials")))
        item.role = role_of(item.category_id, f'{cat.get("nameEn", "")} {cat.get("nameFr", "")}', item.is_alcohol)
        items.append(item)
    items.sort(key=lambda i: i.id)
    floor = v.get("floor") or {}
    rooms = {r["id"]: r for r in floor.get("rooms") or []}
    tables = []
    for t in floor.get("tables") or []:
        z = rooms.get(t.get("zoneId"))
        if z is None or t.get("zoneId") in ("carry-out", "counter"):
            continue
        tables.append((t["id"], t.get("label") or t["id"], t["zoneId"], z.get("nameFr") or z.get("nameEn") or t["zoneId"],
                       z.get("nameEn") or z.get("nameFr") or t["zoneId"], max(1, int(t.get("seats") or 4))))
    counter = v["id"] == "express"
    if not tables and not counter:
        names = {r[0]: r for r in SEED_ROOMS}
        tables = [(tid, label, zid, names[zid][1], names[zid][2], seats) for tid, zid, label, seats in SEED_TABLES]
    tables.sort()
    staff = [(s["id"], s.get("name") or s["id"], (s.get("role") or "SERVER").upper()) for s in v.get("staff") or []]
    if not staff and staff_fallback:
        staff = [("manager", "Manager", "MANAGER")]
    staff.sort()
    return Store(v["id"], v.get("name") or v["id"], v.get("timezone") or "America/New_York", v.get("currency") or "USD",
                 items, cats, tables, staff, counter, v.get("lastTaxes"))


# ------------------------------------------------------------------ generation

@dataclasses.dataclass
class Line:
    line_id: int
    item: Item
    variant: Variant
    qty: int
    unit: int
    added_at: dt.datetime
    special: Special | None

    @property
    def total(self) -> int:
        return self.unit * self.qty


@dataclasses.dataclass
class Tender:
    tender_id: int
    type: str
    tendered: int
    applied: int
    rounding: int
    change: int
    tip: int
    at: dt.datetime


@dataclasses.dataclass
class Check:
    check_id: int
    status: str  # CLOSED | VOID
    table: tuple
    opened_at: dt.datetime
    closed_at: dt.datetime
    opened_by: str
    covers: int
    lines: list
    subtotal: int
    taxes: list
    tax: int
    total: int
    tenders: list
    source: str  # TABLE | CARRY_OUT | POS | KIOSK
    mode: str    # DINE_IN | TAKE_OUT | CARRY_OUT
    void_reason: str | None = None
    voided_by: str | None = None
    shift_id: int | None = 0


@dataclasses.dataclass
class Refund:
    refund_id: int
    check: Check
    gross: int
    net: int
    tax: int
    taxes: list
    tender_type: str
    rounding: int
    reason: str
    by: str
    at: dt.datetime
    shift_id: int = 0


@dataclasses.dataclass
class Movement:
    movement_id: int
    direction: str
    amount: int
    reason: str
    by: str
    at: dt.datetime
    shift_id: int = 0


@dataclasses.dataclass
class Shift:
    shift_id: int
    date: dt.date
    opened_at: dt.datetime
    closed_at: dt.datetime
    opened_by: str
    closed_by: str
    float_cents: int
    checks: list
    refunds: list
    movements: list
    over_short: int
    revenue: int = 0
    count: int = 0
    breakdown: list = dataclasses.field(default_factory=list)
    expected_cash: int = 0
    cash_rounding: int = 0
    tips: int = 0
    partial: bool = False


class Ids:
    """Sequential ids in the reserved range, one counter per kind, per store."""

    def __init__(self):
        self.n = {}

    def next(self, kind: str) -> int:
        lo, hi = RANGES[kind]
        v = self.n.get(kind, lo - 1) + 1
        if v > hi:
            raise SystemExit(f"too many {kind} rows for the reserved id range")
        self.n[kind] = v
        return v


def stable_unit(key: str) -> float:
    """A stable number in [0, 1) for [key] (an item's base popularity: same on every run and machine)."""
    return int(hashlib.sha256(key.encode()).hexdigest()[:8], 16) / 2**32


# Signature dishes sell more (ids from the seeded menus; absent ids are ignored).
BOOST = {"lantern-burger": 2.4, "wings": 2.0, "fish-chips": 2.0, "lantern-lager": 2.6, "nachos": 1.5, "steak-frites": 1.3,
         "amber-ale": 1.4, "cab-merlot": 1.7, "late-fries": 2.2, "double-cheeseburger": 2.0, "fountain-soda": 3.0, "chicken-tenders": 1.8,
         "poutine": 1.4, "club": 1.3, "copper-old-fashioned": 1.3, "pinot-noir": 1.2}

# Specials that WORK lift their item while in force; a few do not (so "which specials should I end?"
# has an answer). The pull is a weight on that item (and its special size) whenever a special is in
# force for it; tuned so the generated history shows, by units:
#   a day special (Tuesday burgers)   about +90-150% on its day vs the other weekdays
#   a happy-hour drink (4-6 pm)       about +60-100% vs the same hours at the weekend, and per check
#                                     vs 2-4 pm on the same days
#   a special that does not work      +3-5%
SPECIALS_WEEKS = 10  # by default the specials began ten weeks ago: "before vs after" has a before
DAY_PULL = 2.6
WINDOW_PULL = 1.5
SPECIAL_PULL = {
    "lantern-burger:regular": 2.9, "double-cheeseburger:regular": 2.8,
    "lantern-lager:pint": 3.4, "lantern-lager:16oz": 1.8, "pinot-noir:glass": 2.6,
    # the one that does not work: barely anyone switches to the deal (this pull only makes up for the
    # $5 lager drawing drinkers away in the same hour)
    "cab-merlot:glass": 1.45,
}
# a happy hour brings a few more guests in: checks closing in its window on its days
HAPPY_HOUR_HALO = 1.12
# a store's "how is my special doing" verdicts (plan output)
WORKING, NOT_WORKING = 0.30, 0.15

# ---- a year's shape (per business day; Generator.day_factor)
GROWTH_PER_YEAR = 0.10  # slow growth: a day now sells ~10% more than the same day a year ago
# month -> demand: the patio summer at Glenwood, the January dip, the December holidays
PUB_SEASON = {1: 0.80, 2: 0.86, 3: 0.94, 4: 1.00, 5: 1.05, 6: 1.15, 7: 1.18, 8: 1.17, 9: 1.10, 10: 1.00, 11: 0.97, 12: 1.10}
# Express is steadier: office lunches all year, a little summer, a softer January
COUNTER_SEASON = {1: 0.90, 2: 0.94, 3: 0.98, 4: 1.00, 5: 1.02, 6: 1.04, 7: 1.04, 8: 1.04, 9: 1.03, 10: 1.00, 11: 0.99, 12: 1.03}
EVENT_SATURDAYS = 4      # Glenwood: a few local-event Saturdays (game day, a festival) ...
EVENT_LIFT = 1.40        # ... at +40%


def thanksgiving(year: int) -> dt.date:
    """The fourth Thursday of November."""
    first = dt.date(year, 11, 1)
    return first + dt.timedelta(days=(3 - first.weekday()) % 7 + 21)


def closed_on(day: dt.date) -> bool:
    """Both stores close on Thanksgiving Day and Christmas Day: no sales at all."""
    return day == thanksgiving(day.year) or (day.month, day.day) == (12, 25)


def holiday_factor(day: dt.date, counter: bool) -> float:
    tg = thanksgiving(day.year)
    if tg - dt.timedelta(days=3) <= day <= tg + dt.timedelta(days=3):  # the week around Thanksgiving
        return 1.08 if counter else 1.18
    if day.month == 12 and day.day <= 23:  # office parties, shoppers
        return 1.04 if counter else 1.10
    if (day.month, day.day) == (12, 24):
        return 0.60
    if (day.month, day.day) == (12, 31):
        return 0.90 if counter else 1.25
    if (day.month, day.day) == (1, 1):
        return 0.65
    return 1.0


PUB_DAY = (0.72, 0.86, 0.86, 1.05, 1.32, 1.38, 0.92)        # Mon..Sun
COUNTER_DAY = (0.82, 1.02, 0.90, 0.95, 1.15, 1.22, 0.85)
PUB_HOURS = {11: 1, 12: 8, 13: 9, 14: 4, 15: 2, 16: 4, 17: 6, 18: 9, 19: 11, 20: 10, 21: 8, 22: 6, 23: 3}
PUB_WEEKEND_HOURS = {11: 3, 12: 10, 13: 10, 14: 6, 15: 4, 16: 4, 17: 6, 18: 9, 19: 11, 20: 10, 21: 9, 22: 7, 23: 4}
# Express: guests per order, and what one guest orders (a combo is main + side + drink)
COUNTER_PEOPLE = (90, 8, 2)
COUNTER_SHAPES = {("main", "side", "drink"): 20, ("main",): 30, ("main", "drink"): 17, ("main", "side"): 7,
                  ("side", "drink"): 13, ("drink",): 8, ("dessert", "drink"): 3, ("dessert",): 2}
COUNTER_HOURS = {10: 2, 11: 12, 12: 18, 13: 12, 14: 5, 15: 4, 16: 5, 17: 9, 18: 10, 19: 7, 20: 4}
VOID_REASONS = ("Entered on the wrong table", "Guest walked out before ordering", "Duplicate check", "Test by manager")
REFUND_REASONS = ("Food came out cold", "Wrong order", "Guest complaint", "Charged twice", "Order error")
PAID_OUT = ("Petty cash: limes and lemons", "Petty cash: ice", "Petty cash: cleaning supplies", "Petty cash: printer paper")


class Generator:
    def __init__(self, store: Store, seed: int, first_day: dt.date, days: int, volume: float = 1.0,
                 since: dt.date | None = None, today_until: dt.time | None = None):
        self.s = store
        self.seed = seed
        self.first = first_day
        self.days = days
        self.volume = volume
        # the specials (and the day-only dishes that came with them) exist from [since]; before it the
        # same items sell at the menu price with no lift. None = the whole window.
        self.since = since or first_day
        # also generate the day after the window (today) up to this store time; None = no
        self.today_until = today_until
        self.last = first_day + dt.timedelta(days=days - 1)
        self.events = self.event_days()
        self.ids = Ids()
        self.shifts: list[Shift] = []
        managers = [x for x in store.staff if x[2] in ("MANAGER", "OWNER", "ADMIN")] or store.staff
        self.managers = [x[0] for x in managers]
        servers = [x for x in store.staff if x[2] not in ("MANAGER", "OWNER", "ADMIN")] or store.staff
        self.servers = [x[0] for x in servers]
        self.base_weight = {i.id: (0.5 + 1.5 * stable_unit(i.id)) * BOOST.get(i.id, 1.0) * self.price_factor(i) for i in store.items}

    def price_factor(self, it: Item) -> float:
        """Pricier things sell less: a $14 pour far less than an $8 pint, a $35 main a bit less than a $19 burger."""
        same = sorted(min(v.price for v in x.variants) for x in self.s.items if x.role == it.role)
        typical = same[len(same) // 2]
        ratio = min(1.25, typical / min(v.price for v in it.variants))
        return ratio ** (1.6 if it.role == "drink" else 0.6)

    # --- helpers
    def local(self, day: dt.date, minute_of_day: int, second: int = 0) -> dt.datetime:
        h, m = divmod(minute_of_day, 60)
        return dt.datetime.combine(day, dt.time(h, m, second), tzinfo=self.s.zone)

    def live(self, it: Item, m: Moment) -> tuple:
        """[it]'s specials as they stood on [m]'s business day: none before they began."""
        return it.specials if m.date >= self.since else ()

    def event_days(self) -> set:
        """Glenwood's local-event Saturdays: a few, spread over the window, the same for a seed."""
        if self.s.counter or self.days < 60:
            return set()
        sats = [self.first + dt.timedelta(days=i) for i in range(self.days)
                if (self.first + dt.timedelta(days=i)).weekday() == 5 and not closed_on(self.first + dt.timedelta(days=i))]
        rng = random.Random(f"{self.seed}|{self.s.id}|events")
        step = len(sats) / EVENT_SATURDAYS
        return {sats[min(len(sats) - 1, int(step * k + rng.uniform(0, step)))] for k in range(EVENT_SATURDAYS)}

    def day_factor(self, rng: random.Random, day: dt.date) -> float:
        """How busy [day] is beside its weekday: growth, season, holidays, an event, the weather."""
        years_ago = (self.last - day).days / 365.0
        growth = (1 + GROWTH_PER_YEAR) ** -years_ago
        season = (COUNTER_SEASON if self.s.counter else PUB_SEASON)[day.month]
        event = EVENT_LIFT if day in self.events else 1.0
        # weather-like noise: most days a little either way, now and then a washout or a glorious day
        swing = 0.05 if self.s.counter else 0.08
        weather = rng.uniform(1 - swing, 1 + swing)
        roll = rng.random()
        if roll < 0.07:
            weather *= 0.88 if self.s.counter else (0.70 if 5 <= day.month <= 9 else 0.80)  # rain (the patio empties)
        elif roll > 0.96:
            weather *= 1.05 if self.s.counter else 1.12
        return growth * season * holiday_factor(day, self.s.counter) * event * weather

    def pick_item(self, rng: random.Random, role_set, at: dt.datetime, extra=None):
        m = moment(at)
        pool, weights = [], []
        for it in self.s.items:
            if it.role not in role_set or not sold_on(it.available_days, m):
                continue
            if it.available_days and m.date < self.since:
                continue  # a day-only dish came on the menu with the specials
            w = self.base_weight[it.id]
            if it.available_days:
                w *= 2.0  # the day's feature dish
            w *= self.pull(it, m)  # Tuesday burgers, happy-hour lager (a special that works)
            if extra:
                w *= extra(it)
            pool.append(it)
            weights.append(w)
        if not pool:
            return None
        return rng.choices(pool, weights)[0]

    def pull(self, it: Item, m: Moment) -> float:
        """How much a special in force at [m] pulls [it]: its strongest special's pull, else 1."""
        best = 1.0
        for sp in self.live(it, m):
            if in_force(sp, m):
                for vid in sp.prices:
                    best = max(best, SPECIAL_PULL.get(vid, WINDOW_PULL if sp.start else DAY_PULL))
        return best

    def pick_variant(self, rng: random.Random, item: Item, m: Moment | None = None) -> Variant:
        if len(item.variants) == 1:
            return item.variants[0]
        low = min(v.price for v in item.variants)
        weights = [(low / v.price) ** 1.8 for v in item.variants]
        if m is not None:  # the size on special is the one people order (a pint at $5 rather than a pitcher)
            for i, v in enumerate(item.variants):
                if any(v.id in sp.prices and in_force(sp, m) for sp in self.live(item, m)):
                    weights[i] *= max(1.0, SPECIAL_PULL.get(v.id, 1.5))
        return rng.choices(item.variants, weights)[0]

    def halo_hours(self, day: dt.date) -> set:
        """The hours a happy hour of this store runs on [day] (its checks get [HAPPY_HOUR_HALO])."""
        code = DAYS[day.weekday()]
        hours = set()
        if day < self.since:
            return hours
        for it in self.s.items:
            for sp in it.specials:
                if sp.start and sp.end and code in sp.days:
                    hours.update(range(minutes(sp.start) // 60, (minutes(sp.end) + 59) // 60))
        return hours

    def hour_weights(self, day: dt.date, hours: dict) -> tuple[list, list]:
        halo = self.halo_hours(day)
        return list(hours), [w * (HAPPY_HOUR_HALO if h in halo else 1.0) for h, w in hours.items()]

    def add(self, rng, lines, role_set, at, extra=None, qty=1):
        it = self.pick_item(rng, role_set, at, extra)
        if it is None:
            return
        m = moment(at)
        v = self.pick_variant(rng, it, m)
        unit, sp = price_at(self.live(it, m), v.id, v.price, m)
        for ln in lines:  # the same size at the same price again: one more on that line
            if ln.variant.id == v.id and ln.unit == unit:
                ln.qty += qty
                return
        lines.append(Line(0, it, v, qty, unit, at, sp))

    def finish_check(self, rng, check_lines, table, opened, closed, by, covers, source, mode, day_checks):
        if not check_lines:
            return None
        for ln in check_lines:
            ln.line_id = self.ids.next("line")
        subtotal = sum(ln.total for ln in check_lines)
        taxes = tax_lines(subtotal)
        tax = sum(t["amountCents"] for t in taxes)
        c = Check(self.ids.next("check"), "CLOSED", table, opened, closed, by, covers, check_lines, subtotal, taxes, tax,
                  subtotal + tax, [], source, mode)
        day_checks.append(c)
        return c

    def pay(self, rng, c: Check, card_share: float, tip_share: float, tip_range, split_ok: bool):
        if rng.random() < card_share:
            parts = 1
            if split_ok and c.covers >= 3 and rng.random() < 0.10:
                parts = rng.randint(2, min(4, c.covers))
            share, left = c.total // parts, c.total
            for p in range(parts):
                amt = left if p == parts - 1 else share
                left -= amt
                tip = 0
                if rng.random() < tip_share:
                    tip = round(amt * rng.uniform(*tip_range))
                    if rng.random() < 0.35:
                        tip = max(100, round(tip / 100) * 100)  # "make it an even $12"
                c.tenders.append(Tender(0, "CARD", amt, amt, 0, 0, tip, c.closed_at))
        else:
            rounding = cash_adjustment(c.total)
            due = c.total + rounding
            bills = [n for n in (500, 1000, 2000, 5000, 10000) if n >= due]
            if rng.random() < 0.25:
                tendered = due  # exact change
            elif bills and rng.random() < 0.6:
                tendered = bills[0]  # the next bill up
            else:
                tendered = (due + 999) // 1000 * 1000  # some tens
            c.tenders.append(Tender(0, "CASH", tendered, c.total, rounding, tendered - due, 0, c.closed_at))
        for t in c.tenders:
            t.tender_id = self.ids.next("tender")

    # --- one business day
    def day_pub(self, rng: random.Random, day: dt.date, index: int):
        wd = day.weekday()
        n = round(95 * PUB_DAY[wd] * self.day_factor(rng, day) * self.volume)
        n = max(round(30 * self.volume), min(round(200 * self.volume), n)) if self.volume >= 1 else max(3, n)
        hours = PUB_WEEKEND_HOURS if wd >= 5 else PUB_HOURS
        out: list[Check] = []
        closes = sorted(self.local(day, h * 60 + rng.randint(0, 59), rng.randint(0, 59))
                        for h in rng.choices(*self.hour_weights(day, hours), k=n))
        open_at = self.local(day, 11 * 60)
        for close in closes:
            h = close.hour
            # 2-6 pm is the afternoon drinking crowd, happy hour or not: the special is what changes at 4
            meal = "lunch" if h < 14 else "happy" if h < 18 else "dinner" if h < 21 else "late"
            covers = rng.choices((1, 2, 3, 4, 5, 6), (30, 42, 10, 12, 3, 3))[0]
            stay = {"lunch": (35, 70), "happy": (40, 120), "dinner": (55, 110), "late": (35, 150)}[meal]
            opened = max(open_at, close - dt.timedelta(minutes=rng.randint(*stay) + 5 * covers))
            span = (close - opened).total_seconds()

            def at(frac):
                return opened + dt.timedelta(seconds=int(span * frac))

            lines: list[Line] = []
            main_p = {"lunch": 0.85, "happy": 0.25, "dinner": 0.82, "late": 0.2}[meal]
            drinks = {"lunch": (0, 0, 1), "happy": (1, 1, 2), "dinner": (0, 1, 1, 1, 2), "late": (1, 1, 2, 2)}[meal]
            # a pub's drinks are mostly beer, wine and cocktails; the alcohol-free ones sell at lunch
            soft_w = 0.9 if meal == "lunch" else 0.25
            soft = (lambda it, w=soft_w: 1.0 if it.is_alcohol else w)
            if rng.random() < {"lunch": 0.12, "happy": 0.3, "dinner": 0.3, "late": 0.25}[meal]:
                self.add(rng, lines, {"side"}, at(0.12))
            for _ in range(covers):
                for k in range(rng.choice(drinks)):
                    self.add(rng, lines, {"drink"}, at(0.05 + 0.6 * k / 3 + rng.uniform(0, 0.1)), soft)
                if rng.random() < main_p:
                    self.add(rng, lines, {"main"} if meal != "late" else {"main", "side"}, at(rng.uniform(0.15, 0.3)))
                if meal in ("dinner", "lunch") and rng.random() < (0.12 if meal == "dinner" else 0.05):
                    self.add(rng, lines, {"dessert"}, at(rng.uniform(0.7, 0.85)))
            table = self.pick_table(rng, covers)
            c = self.finish_check(rng, lines, table, opened, close, rng.choice(self.servers), covers, "TABLE", "DINE_IN", out)
            if c:
                self.pay(rng, c, 0.70, 0.97, (0.15, 0.22), True)
        # carry-out: a few a day, food first
        for _ in range(rng.choice((2, 3, 3, 4, 5, 6)) if self.volume >= 1 else rng.choice((0, 1))):
            close = self.local(day, rng.choice((12, 12, 13, 17, 18, 18, 19, 20)) * 60 + rng.randint(0, 59), rng.randint(0, 59))
            opened = close - dt.timedelta(minutes=rng.randint(15, 35))
            lines = []
            for _ in range(rng.choice((1, 1, 2, 2, 3))):
                self.add(rng, lines, {"main"}, opened + dt.timedelta(minutes=1))
            if rng.random() < 0.4:
                self.add(rng, lines, {"side"}, opened + dt.timedelta(minutes=2))
            if rng.random() < 0.15:
                self.add(rng, lines, {"dessert"}, opened + dt.timedelta(minutes=2))
            table = (CARRY_OUT[0], CARRY_OUT[1], CARRY_OUT[2], CARRY_OUT[3], CARRY_OUT[4], 0)
            c = self.finish_check(rng, lines, table, opened, close, rng.choice(self.servers), 1, "CARRY_OUT", "CARRY_OUT", out)
            if c:
                self.pay(rng, c, 0.85, 0.40, (0.0, 0.10), False)
        out.sort(key=lambda c: c.closed_at)
        return out

    def day_counter(self, rng: random.Random, day: dt.date, index: int):
        wd = day.weekday()
        n = round(135 * COUNTER_DAY[wd] * self.day_factor(rng, day) * self.volume)
        n = max(round(60 * self.volume), min(round(260 * self.volume), n)) if self.volume >= 1 else max(3, n)
        out: list[Check] = []
        closes = sorted(self.local(day, h * 60 + rng.randint(0 if h > 10 else 45, 59), rng.randint(0, 59))
                        for h in rng.choices(*self.hour_weights(day, COUNTER_HOURS), k=n))
        for close in closes:
            kiosk = rng.random() < 0.40
            mode = "TAKE_OUT" if rng.random() < 0.58 else "DINE_IN"
            opened = close - dt.timedelta(seconds=rng.randint(150, 480) if kiosk else rng.randint(60, 240))
            people = rng.choices((1, 2, 3), COUNTER_PEOPLE)[0]
            lines: list[Line] = []
            beer = 0.25 if close.hour >= 18 else 0.08
            drink_w = (lambda it, b=beer: b * 4 if it.is_alcohol else 1.0)
            for _ in range(people):
                shape = rng.choices(list(COUNTER_SHAPES), list(COUNTER_SHAPES.values()))[0]
                for role in shape:
                    self.add(rng, lines, {role}, opened, drink_w if role == "drink" else self.counter_main if role == "main" else None)
            by = KIOSK_USER if kiosk else rng.choice(self.servers)
            c = self.finish_check(rng, lines, COUNTER + (0,), opened, close, by, people, "KIOSK" if kiosk else "POS", mode, out)
            if c:
                self.pay(rng, c, 0.88, 0.30, (0.10, 0.18), False)
        return out

    @staticmethod
    def counter_main(it: Item) -> float:
        return 1.0 if min(v.price for v in it.variants) < 1300 else 0.5

    def pick_table(self, rng, covers):
        fit = [t for t in self.s.tables if t[5] >= covers] or self.s.tables
        weights = []
        for t in fit:
            w = 1.0
            if t[5] == 1:
                w = 1.6 if covers == 1 else 0.0
            if t[5] > covers + 3:
                w *= 0.3
            weights.append(w)
        if not any(weights):
            weights = [1.0] * len(fit)
        return rng.choices(fit, weights)[0]

    def generate(self) -> "Generator":
        extra = 1 if self.today_until else 0
        for index in range(self.days + extra):
            day = self.first + dt.timedelta(days=index)
            if closed_on(day):
                continue
            rng = random.Random(f"{self.seed}|{self.s.id}|{day.isoformat()}")
            checks = self.day_counter(rng, day, index) if self.s.counter else self.day_pub(rng, day, index)
            partial = index == self.days
            if partial:  # today, so far: what a typical day of this weekday has sold by then
                until = dt.datetime.combine(day, self.today_until, tzinfo=self.s.zone)
                checks = [c for c in checks if c.closed_at <= until]
            if not checks:
                continue
            # a few voids: a check that was rung and then voided by a manager (no payment, no lines sent)
            void_p = 0.006 if self.s.counter else 0.012
            for c in checks:
                if rng.random() < void_p:
                    c.status = "VOID"
                    c.tenders = []
                    c.void_reason = rng.choice(VOID_REASONS)
                    c.voided_by = rng.choice(self.managers)
            closed = [c for c in checks if c.status == "CLOSED"]
            refunds: list[Refund] = []
            for _ in range((1 if rng.random() < (0.10 if self.s.counter else 0.17) else 0) + (1 if rng.random() < 0.03 else 0)):
                pool = [c for c in closed if len(c.tenders) == 1 and c.closed_at.hour < 22
                        and all(r.check is not c for r in refunds)]
                if not pool:
                    break
                c = rng.choice(pool)
                at = min(c.closed_at + dt.timedelta(minutes=rng.randint(15, 90)), self.local(day, 23 * 60 + 58))
                tt = c.tenders[0].type
                refunds.append(Refund(self.ids.next("refund"), c, c.total, c.subtotal, c.tax, c.taxes, tt,
                                      cash_adjustment(c.total) if tt == "CASH" else 0, rng.choice(REFUND_REASONS),
                                      rng.choice(self.managers), at))
            if partial:
                refunds = [x for x in refunds if x.at <= until]
            movements = []
            if rng.random() < (0.12 if self.s.counter else 0.2) and not partial:
                movements.append(Movement(self.ids.next("movement"), "OUT", rng.choice((1500, 2000, 2500, 3500, 4800)),
                                          rng.choice(PAID_OUT), rng.choice(self.managers),
                                          self.local(day, rng.randint(14 * 60, 17 * 60))))
            first_open = min(c.opened_at for c in checks)
            last = max([c.closed_at for c in checks] + [r.at for r in refunds])
            sh = Shift(self.ids.next("shift"), day, first_open - dt.timedelta(minutes=rng.randint(20, 40)),
                       last + dt.timedelta(minutes=rng.randint(15, 45)), rng.choice(self.managers), rng.choice(self.managers),
                       20000 if self.s.counter else 30000, checks, refunds, movements,
                       rng.choices((0, 0, 0, 0, 0, -5, 5, -100, 100, -250, 200), k=1)[0])
            sh.partial = partial  # today: no Z-report yet, so no shift row; its sales carry no shift
            for c in checks:
                c.shift_id = None if partial else sh.shift_id
            for r in refunds:
                r.shift_id = None if partial else sh.shift_id
            for mv in movements:
                mv.shift_id = sh.shift_id
            self.close_shift(sh)
            self.shifts.append(sh)
        return self

    @property
    def history(self) -> list:
        """The closed days of the window (today's partial day left out)."""
        return [sh for sh in self.shifts if not sh.partial]

    @property
    def today(self):
        return next((sh for sh in self.shifts if sh.partial), None)

    @staticmethod
    def close_shift(sh: Shift):
        """The Z-report the store sends (ShiftService.buildReport)."""
        closed = [c for c in sh.checks if c.status == "CLOSED"]
        sh.revenue = sum(c.total for c in closed)
        sh.count = len(closed)
        by_type: dict[str, list] = {}
        order = []
        for c in closed:
            for t in c.tenders:
                if t.type not in by_type:
                    by_type[t.type] = [0, 0, 0]
                    order.append(t.type)
                b = by_type[t.type]
                b[0] += t.applied
                b[1] += 1
                b[2] += t.tip
        sh.breakdown = sorted(({"type": k, "amountCents": by_type[k][0], "count": by_type[k][1], "tipCents": by_type[k][2]}
                               for k in order), key=lambda r: -r["amountCents"])
        cash = [t for c in closed for t in c.tenders if t.type == "CASH"]
        cash_refunds = [r for r in sh.refunds if r.tender_type == "CASH"]
        paid_in = sum(m.amount for m in sh.movements if m.direction == "IN")
        paid_out = sum(m.amount for m in sh.movements if m.direction == "OUT")
        sh.expected_cash = (sh.float_cents + sum(t.tendered - t.change for t in cash) + paid_in - paid_out
                            - sum(r.gross + r.rounding for r in cash_refunds))
        sh.cash_rounding = sum(t.rounding for t in cash) - sum(r.rounding for r in cash_refunds)
        sh.tips = sum(b["tipCents"] for b in sh.breakdown)

    # --- what the reports should show
    def expected(self, today: bool = False) -> dict:
        """What the reports should show for the window (or, [today], for today's generated sales so far)."""
        days, pay, items, hours = {}, {}, {}, {}
        rounding = 0

        def day_row(d):
            return days.setdefault(d, {"checks": 0, "gross": 0, "tax": 0, "voids": 0, "voidCents": 0,
                                       "refunds": 0, "refundCents": 0, "refundTax": 0})
        for sh in ([self.today] if self.today else []) if today else self.history:
            for c in sh.checks:
                d = day_row(c.closed_at.date().isoformat())
                if c.status == "VOID":
                    d["voids"] += 1
                    d["voidCents"] += c.total
                    continue
                d["checks"] += 1
                d["gross"] += c.total
                d["tax"] += c.tax
                h = hours.setdefault(str(c.closed_at.hour), [0, 0])
                h[0] += 1
                h[1] += c.total
                for t in c.tenders:
                    p = pay.setdefault(t.type, [0, 0])
                    p[0] += 1
                    p[1] += t.applied
                    rounding += t.rounding
                for ln in c.lines:
                    i = items.setdefault(ln.item.id, [0, 0])
                    i[0] += ln.qty
                    i[1] += ln.total
            for r in sh.refunds:
                d = day_row(r.at.date().isoformat())
                d["refunds"] += 1
                d["refundCents"] += r.gross
                d["refundTax"] += r.tax
                rounding -= r.rounding
        tot = {k: sum(d[k] for d in days.values()) for k in
               ("checks", "gross", "tax", "voids", "voidCents", "refunds", "refundCents", "refundTax")}
        gross = tot["gross"] - tot["refundCents"]
        tax = tot["tax"] - tot["refundTax"]
        return {
            "store": self.s.id, "name": self.s.name, "currency": self.s.currency,
            "from": self.first.isoformat(), "to": (self.first + dt.timedelta(days=self.days - 1)).isoformat(),
            "totals": {"grossCents": gross, "taxCents": tax, "netCents": gross - tax, "checkCount": tot["checks"],
                       "avgCheckCents": tot["gross"] // tot["checks"] if tot["checks"] else 0,
                       "voidCount": tot["voids"], "voidAmountCents": tot["voidCents"], "refundCount": tot["refunds"],
                       "refundAmountCents": tot["refundCents"], "cashRoundingCents": rounding},
            "days": {d: {"grossCents": v["gross"] - v["refundCents"], "taxCents": v["tax"] - v["refundTax"],
                         "checkCount": v["checks"]} for d, v in sorted(days.items())},
            "payments": {k: {"count": v[0], "amountCents": v[1]} for k, v in sorted(pay.items())},
            "items": {k: {"qty": v[0], "revenueCents": v[1]} for k, v in sorted(items.items())},
            "hourly": {k: {"checkCount": v[0], "grossCents": v[1]} for k, v in sorted(hours.items(), key=lambda kv: int(kv[0]))},
            "months": {m: {"checkCount": sum(v["checks"] for d, v in days.items() if d[:7] == m),
                           "grossCents": sum(v["gross"] - v["refundCents"] for d, v in days.items() if d[:7] == m)}
                       for m in sorted({d[:7] for d in days})},
        }

    def facts(self) -> dict:
        """The plan's description of what will be inserted (beyond the report figures)."""
        checks = [c for sh in self.shifts for c in sh.checks]
        closed = [c for c in checks if c.status == "CLOSED"]
        tenders = [t for c in closed for t in c.tenders]
        lines = [ln for c in closed for ln in c.lines]
        card = sum(t.applied for t in tenders if t.type == "CARD")
        total = sum(t.applied for t in tenders) or 1
        per_day = [sum(1 for c in sh.checks if c.status == "CLOSED") for sh in self.history]
        specials = {}
        for ln in lines:
            if ln.special:
                specials[ln.variant.id] = specials.get(ln.variant.id, 0) + ln.qty
        day_only = {}
        for ln in lines:
            if ln.item.available_days:
                day_only.setdefault(ln.item.id, set()).add(moment(ln.added_at).day)
        return {
            "checks": len(closed), "voids": len(checks) - len(closed), "lines": len(lines), "tenders": len(tenders),
            "refunds": sum(len(sh.refunds) for sh in self.shifts), "shifts": len(self.history),
            "today": sum(1 for c in self.today.checks if c.status == "CLOSED") if self.today else 0,
            "closedDays": [d.isoformat() for d in (self.first + dt.timedelta(days=i) for i in range(self.days)) if closed_on(d)],
            "events": sorted(d.isoformat() for d in self.events),
            "movements": sum(len(sh.movements) for sh in self.shifts),
            "perDay": (min(per_day), max(per_day)) if per_day else (0, 0),
            "cardShare": card / total, "tips": sum(sh.tips for sh in self.shifts),
            "covers": sum(c.covers for c in closed if c.source == "TABLE") / max(1, sum(1 for c in closed if c.source == "TABLE")),
            "carryOut": sum(1 for c in closed if c.source == "CARRY_OUT"),
            "kiosk": sum(1 for c in closed if c.source == "KIOSK"), "counterTotal": sum(1 for c in closed if c.source in ("KIOSK", "POS")),
            "takeOut": sum(1 for c in closed if c.mode == "TAKE_OUT"),
            "specialLines": specials, "dayOnly": {k: sorted(v, key=DAYS.index) for k, v in day_only.items()},
            "items": len(self.s.items),
        }


def special_lifts(g: "Generator") -> list[dict]:
    """How each special did in the generated history, the way a manager (or the menu AI) would ask it,
    from closed sales by business day and closing hour:
      day special    units per special day vs per other weekday (Mon-Fri)
      window special units in the window on its days vs the same hours on the other days (the weekend),
                     and units per check in the window vs the two hours before it, same days
    plus the window's checks per day vs the other days (the halo)."""
    closed = [c for sh in g.history for c in sh.checks if c.status == "CLOSED"]
    all_days = sorted({sh.date for sh in g.history})
    days = [d for d in all_days if d >= g.since]      # while the specials run
    before = [d for d in all_days if g.since - dt.timedelta(days=len(days) or 1) <= d < g.since]  # as long, just before
    checks = {}
    for c in closed:
        k = (c.closed_at.date(), c.closed_at.hour)
        checks[k] = checks.get(k, 0) + 1

    def mean(xs):
        return sum(xs) / len(xs) if xs else 0.0

    def lift(a, b):
        return a / b - 1 if b else None
    out = []
    for it in g.s.items:
        for sp in it.specials:
            for vid in sorted(sp.prices):
                units = {}
                for c in closed:
                    for ln in c.lines:
                        if ln.variant.id == vid:
                            k = (c.closed_at.date(), c.closed_at.hour)
                            units[k] = units.get(k, 0) + ln.qty
                on = [d for d in days if DAYS[d.weekday()] in sp.days]
                on_before = [d for d in before if DAYS[d.weekday()] in sp.days]
                row = {"item": it.id, "name": it.name_en, "variant": vid, "days": list(sp.days), "from": sp.start, "to": sp.end,
                       "specialDays": len(on), "since": g.since.isoformat()}
                if sp.start and sp.end:
                    win = range(minutes(sp.start) // 60, (minutes(sp.end) + 59) // 60)
                    pre = range(max(0, win[0] - 2), win[0])
                    off = [d for d in days if d not in on]

                    def per_day(ds, hours):
                        return mean([sum(units.get((d, h), 0) for h in hours) for d in ds])

                    def share(ds, hours):
                        n = sum(checks.get((d, h), 0) for d in ds for h in range(24))
                        return sum(checks.get((d, h), 0) for d in ds for h in hours) / n if n else 0.0

                    def per_check(ds, hours):
                        n = sum(checks.get((d, h), 0) for d in ds for h in hours)
                        return sum(units.get((d, h), 0) for d in ds for h in hours) / n if n else 0.0
                    row.update(kind="window", unitsPerDay=per_day(on, win), otherUnitsPerDay=per_day(off, win),
                               vsBeforeStart=lift(per_day(on, win), per_day(on_before, win)) if on_before else None,
                               vsOtherDays=lift(per_day(on, win), per_day(off, win)),
                               vsBefore=lift(per_check(on, win), per_check(on, pre)),
                               # the window's share of the day's checks, vs the same weekdays before the start
                               halo=lift(share(on, win), share(on_before, win)) if on_before else None)
                else:
                    off = [d for d in days if d not in on and d.weekday() < 5] or [d for d in days if d not in on]
                    a = mean([sum(units.get((d, h), 0) for h in range(24)) for d in on])
                    b = mean([sum(units.get((d, h), 0) for h in range(24)) for d in off])
                    a0 = mean([sum(units.get((d, h), 0) for h in range(24)) for d in on_before])
                    row.update(kind="day", unitsPerDay=a, otherUnitsPerDay=b, vsOtherDays=lift(a, b), vsBefore=None, halo=None,
                               vsBeforeStart=lift(a, a0) if on_before else None)
                # the verdict: the comparisons made while the special runs, averaged (steadier than either)
                during = [x for x in (row["vsOtherDays"], row["vsBefore"]) if x is not None]
                main = mean(during) if during else None
                row["lift"] = main
                row["verdict"] = ("working" if main is not None and main >= WORKING else
                                  "not working" if main is not None and main < NOT_WORKING else "so-so")
                out.append(row)
    return out


def pct(x) -> str:
    return "n/a" if x is None else f"{x * 100:+.0f}%"


# ------------------------------------------------------------------ SQL

def q(v) -> str:
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def ts(t: dt.datetime) -> str:
    return f"'{t.isoformat(sep=' ', timespec='seconds')}'::timestamptz"


def window_end(store: Store, today: dt.date, include_today: bool) -> dt.datetime:
    day = today + dt.timedelta(days=1) if include_today else today
    return dt.datetime.combine(day, dt.time(0, 0), tzinfo=store.zone)


def wipe_predicates(store: Store, end: dt.datetime) -> dict:
    """WHERE clauses (alias-free) selecting what the wipe removes for one store: every synced sale
    before [end], and every row this script ever wrote (the 20000+ numbers, any date -- today's too)."""
    base = f"tenant_id = {q(TENANT)} AND venue_id = {q(store.id)}"
    rng = f"BETWEEN {NUM_MIN} AND {NUM_MAX}"
    e = ts(end)
    T, V = q(TENANT), q(store.id)

    # The guard: a store SENT something numbered 20000+ -- its check.*, shift.*, refund.created or
    # cash.movement event names a number in the range. Rows this script writes never have an event.
    # Then the store's own numbering reached the demo range: refuse, never delete or overwrite.
    # One pass over the store's own events (no aggregate_id index needed, no per-row lookups).
    clash = (f"SELECT ev.aggregate_type FROM events ev WHERE ev.tenant_id = {T} AND ev.venue_id = {V} "
             f"AND ev.aggregate_type IN ('check', 'shift', 'refund', 'cash_movement') "
             f"AND (CASE WHEN ev.aggregate_id ~ '^[0-9]{{1,12}}$' THEN ev.aggregate_id::bigint END) {rng}")
    return {
        "checks": f"{base} AND (closed_at < {e} OR closed_at IS NULL OR check_id {rng})",
        "refunds": f"{base} AND (created_at < {e} OR created_at IS NULL OR refund_id {rng})",
        "shifts": f"{base} AND ((status <> 'OPEN' AND (opened_at < {e} OR opened_at IS NULL)) OR shift_id {rng})",
        "cash_movements": f"{base} AND (created_at < {e} OR created_at IS NULL OR movement_id {rng})",
        "fuel_sales": f"{base} AND (completed_at < {e} OR completed_at IS NULL)",  # never generated
        "clash": clash,
    }


class Json:
    """A jsonb value for the SQL writers."""

    def __init__(self, v):
        self.text = json.dumps(v, separators=(",", ":"), ensure_ascii=False)


def sql_value(v) -> str:
    if isinstance(v, Json):
        return q(v.text) + "::jsonb"
    if isinstance(v, dt.datetime):
        return ts(v)
    return q(v)


def copy_value(v) -> str:
    """COPY text format: \\N is NULL; backslash, tab, newline and CR are escaped."""
    if v is None:
        return "\\N"
    if isinstance(v, Json):
        v = v.text
    elif isinstance(v, dt.datetime):
        v = v.isoformat(sep=" ", timespec="seconds")
    elif isinstance(v, bool):
        v = "t" if v else "f"
    v = str(v)
    return v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


TABLE_COLUMNS = {
    "shifts": "tenant_id,venue_id,shift_id,status,opened_at,opened_by,opening_float_cents,closed_at,closed_by,revenue_cents,"
              "transaction_count,avg_check_cents,corkage_cents,tender_breakdown,expected_cash_cents,closing_count_cents,"
              "over_short_cents,currency,cash_rounding_cents",
    "checks": "tenant_id,venue_id,check_id,status,table_id,table_label,zone_id,zone_name_fr,zone_name_en,shift_id,opened_at,"
              "closed_at,opened_by,grand_total_cents,tax_included_cents,corkage_bottles,corkage_cents,service_charge_cents,"
              "void_reason,voided_by,gst_cents,qst_cents,taxes,currency",
    "check_lines": "tenant_id,venue_id,check_id,line_id,item_id,variant_id,category_id,name_fr,name_en,variant_label_fr,"
                   "variant_label_en,display_name,qty,unit_price_cents,line_total_cents",
    "check_tenders": "tenant_id,venue_id,tender_id,check_id,type,amount_tendered_cents,amount_applied_cents,"
                     "rounding_adjustment_cents,change_cents,tendered_at",
    "refunds": "tenant_id,venue_id,refund_id,check_id,shift_id,gross_cents,net_cents,tax_included_cents,tender_type,reason,"
               "refunded_by,table_label,zone_id,zone_name_fr,zone_name_en,created_at,gst_cents,qst_cents,taxes,currency,"
               "rounding_adjustment_cents",
    "cash_movements": "tenant_id,venue_id,movement_id,shift_id,direction,amount_cents,reason,created_by,created_at,currency",
}


def rows_for(store: Store, g: "Generator") -> dict:
    """Every row to insert for one store, per table, as value tuples."""
    T, V, C = TENANT, store.id, store.currency
    out = {t: [] for t in TABLE_COLUMNS}
    for sh in g.shifts:
        if not sh.partial:
            out["shifts"].append((T, V, sh.shift_id, "CLOSED", sh.opened_at, sh.opened_by, sh.float_cents, sh.closed_at,
                                  sh.closed_by, sh.revenue, sh.count, sh.revenue // sh.count if sh.count else 0, 0,
                                  Json(sh.breakdown), sh.expected_cash, sh.expected_cash + sh.over_short, sh.over_short, C,
                                  sh.cash_rounding))
        for c in sh.checks:
            tid, tlabel, zid, zfr, zen, _ = c.table
            if c.status == "CLOSED":
                out["checks"].append((T, V, c.check_id, "CLOSED", tid, tlabel, zid, zfr, zen, c.shift_id, c.opened_at,
                                      c.closed_at, c.opened_by, c.total, c.tax, 0, 0, 0, None, None, 0, 0, Json(c.taxes), C))
                for ln in c.lines:
                    multi = len(ln.item.variants) > 1
                    out["check_lines"].append((T, V, c.check_id, ln.line_id, ln.item.id, ln.variant.id, ln.item.category_id,
                                               ln.item.name_fr, ln.item.name_en, ln.variant.label_fr if multi else None,
                                               ln.variant.label_en if multi else None, None, ln.qty, ln.unit, ln.total))
                for t in c.tenders:
                    out["check_tenders"].append((T, V, t.tender_id, c.check_id, t.type, t.tendered, t.applied, t.rounding,
                                                 t.change, t.at))
            else:
                out["checks"].append((T, V, c.check_id, "VOID", tid, tlabel, zid, zfr, zen, c.shift_id, c.opened_at,
                                      c.closed_at, c.opened_by, c.total, c.tax, None, None, None, c.void_reason, c.voided_by,
                                      0, 0, Json(c.taxes), C))
        for rf in sh.refunds:
            _, tlabel, zid, zfr, zen, _ = rf.check.table
            out["refunds"].append((T, V, rf.refund_id, rf.check.check_id, rf.shift_id, rf.gross, rf.net, rf.tax,
                                   rf.tender_type, rf.reason, rf.by, tlabel, zid, zfr, zen, rf.at, 0, 0, Json(rf.taxes), C,
                                   rf.rounding))
        for m in sh.movements:
            out["cash_movements"].append((T, V, m.movement_id, m.shift_id, m.direction, m.amount, m.reason, m.by, m.at, C))
    return out


def emit(table: str, rows: list, style: str) -> list[str]:
    cols = TABLE_COLUMNS[table]
    if not rows:
        return []
    if style == "copy":  # psql reads the data that follows from the same script
        return [f"COPY {table} ({cols}) FROM STDIN;",
                "\n".join("\t".join(copy_value(v) for v in row) for row in rows), "\\."]
    return [f"INSERT INTO {table} ({cols}) VALUES\n" + ",\n".join(
        "(" + ",".join(sql_value(v) for v in row) + ")" for row in rows[i:i + 500]) + ";" for i in range(0, len(rows), 500)]


def render_sql(stores: list[Store], gens: dict, end_by_store: dict, style: str = "copy") -> str:
    """The whole run as one transaction. style "copy" (psql: COPY ... FROM STDIN, compact and fast) or
    "insert" (plain multi-row INSERTs: readable, and what a JDBC client can run)."""
    T = q(TENANT)
    ids = ",".join(q(s.id) for s in stores)
    out = [
        "-- scripts/demo-reseed.py: wipe + regenerate demo sales history, one transaction.",
        f"-- Generated checks, shifts, refunds and cash movements are numbered from {NUM_MIN} (to {NUM_MAX});",
        f"-- line and tender ids are in [{ID_MIN}, {ID_MAX}]. Rerunning replaces them.",
        "BEGIN;",
        "SET LOCAL lock_timeout = '20s';",
        "DO $guard$ BEGIN",
        f"  IF NOT EXISTS (SELECT 1 FROM tenants WHERE id = {T}) THEN RAISE EXCEPTION 'tenant {TENANT} not in this database'; END IF;",
        f"  IF (SELECT count(*) FROM venues WHERE tenant_id = {T} AND id IN ({ids})) <> {len(stores)} THEN",
        f"    RAISE EXCEPTION 'stores {ids.replace(chr(39), '')} are not all in tenant {TENANT}'; END IF;",
        "END $guard$;",
    ]
    for s in stores:
        p = wipe_predicates(s, end_by_store[s.id])
        V = q(s.id)
        out += [
            f"-- ===== {s.id}: wipe synced sales before {end_by_store[s.id].isoformat()} (and every row numbered {NUM_MIN}+)",
            "DO $clash$ BEGIN",
            f"  IF EXISTS ({p['clash']}) THEN",
            f"    RAISE EXCEPTION 'store {s.id}: numbers {NUM_MIN}-{NUM_MAX} hold a sale or shift that is not ours'; END IF;",
            "END $clash$;",
            f"CREATE TEMP TABLE reseed_checks ON COMMIT DROP AS SELECT check_id FROM checks WHERE {p['checks']};",
            f"CREATE TEMP TABLE reseed_refunds ON COMMIT DROP AS SELECT refund_id FROM refunds WHERE {p['refunds']};",
            "ANALYZE reseed_checks;",  # good plans for the deletes below (a year is ~50k checks)
            "ANALYZE reseed_refunds;",
            f"DELETE FROM check_lines l USING reseed_checks d WHERE l.tenant_id = {T} AND l.venue_id = {V} AND l.check_id = d.check_id;",
            f"DELETE FROM check_tenders t USING reseed_checks d WHERE t.tenant_id = {T} AND t.venue_id = {V} AND t.check_id = d.check_id;",
            f"DELETE FROM checks c USING reseed_checks d WHERE c.tenant_id = {T} AND c.venue_id = {V} AND c.check_id = d.check_id;",
            f"DELETE FROM refund_lines l USING reseed_refunds d WHERE l.tenant_id = {T} AND l.venue_id = {V} AND l.refund_id = d.refund_id;",
            f"DELETE FROM refunds r USING reseed_refunds d WHERE r.tenant_id = {T} AND r.venue_id = {V} AND r.refund_id = d.refund_id;",
            f"DELETE FROM shifts WHERE {p['shifts']};",
            f"DELETE FROM cash_movements WHERE {p['cash_movements']};",
            f"DELETE FROM fuel_sales WHERE {p['fuel_sales']};",
            "DROP TABLE reseed_checks;",
            "DROP TABLE reseed_refunds;",
        ]
        rows = rows_for(s, gens[s.id])
        out.append(f"-- ===== {s.id}: insert " + ", ".join(f"{len(v)} {k}" for k, v in rows.items()))
        for table in ("shifts", "checks", "check_lines", "check_tenders", "refunds", "cash_movements"):
            out += emit(table, rows[table], style)
        out += [
            "DO $after$ BEGIN",
            f"  IF (SELECT count(*) FROM checks WHERE tenant_id = {T} AND venue_id = {V} AND check_id BETWEEN {NUM_MIN} AND {NUM_MAX}) <> {len(rows['checks'])}",
            f"     OR (SELECT count(*) FROM check_lines WHERE tenant_id = {T} AND venue_id = {V} AND check_id BETWEEN {NUM_MIN} AND {NUM_MAX}) <> {len(rows['check_lines'])}",
            f"  THEN RAISE EXCEPTION 'store {s.id}: inserted rows do not add up; rolled back'; END IF;",
            "END $after$;",
        ]
    out.append("COMMIT;")
    return "\n".join(out) + "\n"


# ------------------------------------------------------------------ database access

class Db:
    """psql on the server (ssh + docker exec) or a local DSN. SQL goes on stdin; rows come back tab-separated."""

    def __init__(self, ssh=None, ssh_key=None, container=None, user="pos", dbname="pos_cloud", dsn=None):
        self.ssh, self.ssh_key, self.container, self.user, self.dbname, self.dsn = ssh, ssh_key, container, user, dbname, dsn

    def describe(self) -> str:
        if self.dsn:
            return f"local psql ({re.sub(r'://[^@/]*@', '://***@', self.dsn)})"
        return f"ssh {self.ssh} -> docker container {self.container} (db {self.dbname}, user {self.user})"

    def ssh_cmd(self, remote: str) -> list[str]:
        cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "-o", "Compression=yes"]
        if self.ssh_key:
            cmd += ["-i", os.path.expanduser(self.ssh_key)]
        return cmd + [self.ssh, remote]

    def find_container(self):
        if self.dsn or self.container:
            return
        r = subprocess.run(self.ssh_cmd("docker ps --format '{{.Names}}'"), capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit(f"ssh {self.ssh} failed: {r.stderr.strip()}")
        names = r.stdout.split()
        hits = [n for n in names if n in CONTAINER_NAMES] or [n for n in names if "copper" in n and n.endswith("-db-1")]
        if len(hits) != 1:
            raise SystemExit("can't tell which Postgres container is Copper Lantern's; pass --container. Running: "
                             + ", ".join(names))
        self.container = hits[0]

    def psql_args(self) -> list[str]:
        return ["-X", "-q", "-v", "ON_ERROR_STOP=1", "-A", "-t", "-F", "\t"]

    def run(self, sql: str, check=True) -> str:
        if self.dsn:
            cmd = ["psql", self.dsn] + self.psql_args()
        else:
            remote = " ".join(["docker", "exec", "-i", shlex.quote(self.container), "psql", "-U", shlex.quote(self.user),
                               "-d", shlex.quote(self.dbname)] + [shlex.quote(a) for a in self.psql_args()])
            cmd = self.ssh_cmd(remote)
        r = subprocess.run(cmd, input=sql, capture_output=True, text=True)
        if check and r.returncode != 0:
            raise SystemExit(f"psql failed (exit {r.returncode}):\n{r.stderr.strip()[-3000:]}")
        return r.stdout

    def rows(self, sql: str) -> list[list[str]]:
        return [ln.split("\t") for ln in self.run(sql).splitlines() if ln.strip()]

    def backup(self, backup_dir: str) -> str:
        stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S")
        name = f"pos_cloud-pre-reseed-{stamp}.sql.gz"
        if self.dsn:
            path = Path(os.path.expanduser(backup_dir)) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with open(path, "wb") as f:
                dump = subprocess.Popen(["pg_dump", "--clean", "--if-exists", self.dsn], stdout=subprocess.PIPE)
                gz = subprocess.run(["gzip", "-c"], stdin=dump.stdout, stdout=f)
                dump.stdout.close()
                if dump.wait() != 0 or gz.returncode != 0:
                    raise SystemExit("backup failed: pg_dump | gzip did not finish; nothing was changed")
            if subprocess.run(["gzip", "-t", str(path)]).returncode != 0 or path.stat().st_size < 1000:
                raise SystemExit(f"backup {path} looks broken; nothing was changed")
            return str(path)
        c = shlex.quote(self.container)
        script = (
            "set -euo pipefail; mkdir -p ~/backups; f=~/backups/" + name + "; "
            # room for it? a gzip'd dump is far smaller than the live database
            f"need=$(docker exec {c} psql -U {shlex.quote(self.user)} -d {shlex.quote(self.dbname)} -XAtc "
            "\"select pg_database_size(current_database())/1024\"); "
            "have=$(df -Pk ~/backups | awk 'NR==2{print $4}'); "
            "if [ \"$have\" -lt \"$need\" ]; then echo \"only ${have} KB free in ~/backups, database is ${need} KB\" >&2; exit 3; fi; "
            f"docker exec {c} pg_dump --clean --if-exists -U {shlex.quote(self.user)} {shlex.quote(self.dbname)} | gzip > \"$f\"; "
            "gzip -t \"$f\"; test \"$(stat -c%s \"$f\")\" -gt 1000; echo \"$f\"; ls -l \"$f\" >&2"
        )
        r = subprocess.run(self.ssh_cmd("bash -c " + shlex.quote(script)), capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit(f"backup failed, nothing was changed:\n{r.stderr.strip()}")
        return r.stdout.strip().splitlines()[-1]


CATALOG_SQL = """
SELECT json_build_object(
 'tenants', (SELECT coalesce(json_agg(id ORDER BY id), '[]') FROM tenants),
 'venues', (SELECT coalesce(json_agg(json_build_object('id', id, 'name', name, 'timezone', timezone, 'currency', currency) ORDER BY id), '[]')
            FROM venues WHERE tenant_id = {T}),
 'categories', (SELECT coalesce(json_agg(json_build_object('venueId', venue_id, 'id', id, 'nameFr', name_fr, 'nameEn', name_en,
               'sortOrder', sort_order)), '[]') FROM catalog_categories WHERE tenant_id = {T} AND NOT deleted),
 'items', (SELECT coalesce(json_agg(json_build_object('venueId', venue_id, 'id', id, 'nameFr', name_fr, 'nameEn', name_en,
          'categoryId', category_id, 'isAlcohol', is_alcohol, 'active', active, 'availableDays', available_days,
          'specials', specials)), '[]') FROM catalog_items WHERE tenant_id = {T} AND NOT deleted),
 'variants', (SELECT coalesce(json_agg(json_build_object('venueId', venue_id, 'id', id, 'itemId', item_id, 'labelFr', label_fr,
             'labelEn', label_en, 'priceCents', price_cents, 'sortOrder', sort_order)), '[]')
             FROM catalog_variants WHERE tenant_id = {T} AND NOT deleted),
 'floor', (SELECT coalesce(json_agg(json_build_object('venueId', venue_id, 'entity', entity, 'id', id, 'zoneId', zone_id,
          'fields', fields)), '[]') FROM floor_things WHERE tenant_id = {T} AND NOT deleted AND entity IN ('room', 'table')),
 'staff', (SELECT coalesce(json_agg(json_build_object('venueId', venue_id, 'id', id, 'name', name, 'role', role)), '[]')
          FROM store_staff WHERE tenant_id = {T} AND active AND NOT deleted),
 'lastTaxes', (SELECT coalesce(json_agg(json_build_object('venueId', venue_id, 'taxes', taxes)), '[]') FROM (
          SELECT DISTINCT ON (venue_id) venue_id, taxes FROM checks
          WHERE tenant_id = {T} AND status = 'CLOSED' AND taxes IS NOT NULL ORDER BY venue_id, closed_at DESC) x)
);
"""


def load_catalog(db: Db) -> dict:
    """The cloud's current menu, floor and staff per store, in the --menu-json document shape."""
    raw = json.loads(db.run(CATALOG_SQL.replace("{T}", q(TENANT))).strip())
    doc = {"tenant": TENANT, "tenantsInDb": raw["tenants"], "venues": []}
    for v in raw["venues"]:
        vid = v["id"]
        variants = {}
        for x in raw["variants"]:
            if x["venueId"] == vid:
                variants.setdefault(x["itemId"], []).append(x)
        items = [dict(i, variants=variants.get(i["id"], [])) for i in raw["items"] if i["venueId"] == vid]
        f = [x for x in raw["floor"] if x["venueId"] == vid]
        rooms = [dict(id=x["id"], nameFr=x["fields"].get("nameFr"), nameEn=x["fields"].get("nameEn")) for x in f if x["entity"] == "room"
                 and not x["fields"].get("deleted")]
        tables = [dict(id=x["id"], zoneId=x["fields"].get("zoneId") or x["zoneId"], label=x["fields"].get("label"),
                       seats=x["fields"].get("seats")) for x in f if x["entity"] == "table" and not x["fields"].get("deleted")]
        last = next((x["taxes"] for x in raw["lastTaxes"] if x["venueId"] == vid), None)
        doc["venues"].append(dict(v, menu={"categories": [c for c in raw["categories"] if c["venueId"] == vid], "items": items},
                                  floor={"rooms": rooms, "tables": tables},
                                  staff=[s for s in raw["staff"] if s["venueId"] == vid], lastTaxes=last))
    return doc


def count_wipe(db: Db, store: Store, end: dt.datetime) -> dict:
    p = wipe_predicates(store, end)
    T, V = q(TENANT), q(store.id)
    e = ts(end)
    sql = f"""
WITH d AS (SELECT check_id, closed_at, status, grand_total_cents FROM checks WHERE {p['checks']}),
     r AS (SELECT refund_id FROM refunds WHERE {p['refunds']})
SELECT 'checks', count(*), coalesce(min(closed_at AT TIME ZONE {q(store.tz)})::date::text, ''),
       coalesce(max(closed_at AT TIME ZONE {q(store.tz)})::date::text, ''),
       coalesce(sum(grand_total_cents) FILTER (WHERE status = 'CLOSED'), 0) FROM d
UNION ALL SELECT 'check_lines', count(*), '', '', 0 FROM check_lines l JOIN d ON l.check_id = d.check_id
  WHERE l.tenant_id = {T} AND l.venue_id = {V}
UNION ALL SELECT 'check_tenders', count(*), '', '', 0 FROM check_tenders t
  WHERE t.tenant_id = {T} AND t.venue_id = {V} AND t.check_id IN (SELECT check_id FROM d)
UNION ALL SELECT 'refunds', count(*), '', '', 0 FROM r
UNION ALL SELECT 'refund_lines', count(*), '', '', 0 FROM refund_lines l WHERE l.tenant_id = {T} AND l.venue_id = {V}
  AND l.refund_id IN (SELECT refund_id FROM r)
UNION ALL SELECT 'shifts', count(*), '', '', 0 FROM shifts WHERE {p['shifts']}
UNION ALL SELECT 'cash_movements', count(*), '', '', 0 FROM cash_movements WHERE {p['cash_movements']}
UNION ALL SELECT 'fuel_sales', count(*), '', '', 0 FROM fuel_sales WHERE {p['fuel_sales']}
UNION ALL SELECT 'kept_checks', count(*), '', '', coalesce(sum(grand_total_cents) FILTER (WHERE status = 'CLOSED'), 0) FROM checks
  WHERE tenant_id = {T} AND venue_id = {V} AND closed_at >= {e} AND check_id NOT BETWEEN {NUM_MIN} AND {NUM_MAX}
UNION ALL SELECT 'open_shifts', count(*), coalesce(min(opened_at AT TIME ZONE {q(store.tz)})::text, ''), '', 0 FROM shifts
  WHERE tenant_id = {T} AND venue_id = {V} AND status = 'OPEN' AND shift_id NOT BETWEEN {NUM_MIN} AND {NUM_MAX}
UNION ALL SELECT 'clash', count(*), '', '', 0 FROM ({p['clash']}) x
UNION ALL SELECT 'real_max', count(*), '', '', coalesce(max(aggregate_id::bigint), 0) FROM events
  WHERE tenant_id = {T} AND venue_id = {V} AND aggregate_type = 'check' AND aggregate_id ~ '^[0-9]{{1,12}}$';
"""
    return {r[0]: {"n": int(r[1]), "from": r[2], "to": r[3], "cents": int(r[4])} for r in db.rows(sql)}


# ------------------------------------------------------------------ verification: the reports' own SQL

def verify(db: Db, stores: list[Store], first: dt.date, last: dt.date) -> dict:
    """Per store, per business day: the sums Reports.kt asks Postgres for (checkSums, tenderSums, itemSums,
    hourly, refunds), over each store's own midnights, exactly as the portal's summary / by-venue /
    payments / items / hourly endpoints compute them."""
    T = q(TENANT)
    z = "CASE c.venue_id " + " ".join(f"WHEN {q(s.id)} THEN {q(s.tz)}" for s in stores) + " END"
    zr = z.replace("c.venue_id", "r.venue_id")
    scope = " OR ".join(
        f"(c.venue_id = {q(s.id)} AND c.closed_at >= {ts(dt.datetime.combine(first, dt.time(0), tzinfo=s.zone))} "
        f"AND c.closed_at < {ts(dt.datetime.combine(last + dt.timedelta(days=1), dt.time(0), tzinfo=s.zone))})" for s in stores)
    rscope = scope.replace("c.venue_id", "r.venue_id").replace("c.closed_at", "r.created_at")
    out = {s.id: {"days": {}, "payments": {}, "items": {}, "hourly": {}, "voids": [0, 0], "refunds": [0, 0, 0, 0], "rounding": 0}
           for s in stores}
    for v, d, status, n, gross, tax in db.rows(f"""
        SELECT c.venue_id, (c.closed_at AT TIME ZONE {z})::date, c.status, count(*), coalesce(sum(c.grand_total_cents), 0),
               coalesce(sum(c.tax_included_cents), 0)
        FROM checks c WHERE c.tenant_id = {T} AND ({scope}) GROUP BY 1, 2, 3 ORDER BY 1, 2;"""):
        if status == "CLOSED":
            row = out[v]["days"].setdefault(d, [0, 0, 0, 0, 0])
            row[0] += int(n)
            row[1] += int(gross)
            row[2] += int(tax)
        elif status == "VOID":
            out[v]["voids"][0] += int(n)
            out[v]["voids"][1] += int(gross)
    for v, d, n, gross, tax, rounding in db.rows(f"""
        SELECT r.venue_id, (r.created_at AT TIME ZONE {zr})::date, count(*), coalesce(sum(r.gross_cents), 0),
               coalesce(sum(r.tax_included_cents), 0), coalesce(sum(r.rounding_adjustment_cents), 0)
        FROM refunds r WHERE r.tenant_id = {T} AND ({rscope}) GROUP BY 1, 2;"""):
        row = out[v]["days"].setdefault(d, [0, 0, 0, 0, 0])
        row[3] += int(gross)
        row[4] += int(tax)
        rf = out[v]["refunds"]
        rf[0] += int(n)
        rf[1] += int(gross)
        rf[2] += int(tax)
        rf[3] += int(rounding)
    for v, typ, n, applied, rounding in db.rows(f"""
        SELECT t.venue_id, t.type, count(*), coalesce(sum(t.amount_applied_cents), 0), coalesce(sum(t.rounding_adjustment_cents), 0)
        FROM check_tenders t JOIN checks c ON c.tenant_id = t.tenant_id AND c.venue_id = t.venue_id AND c.check_id = t.check_id
        WHERE c.tenant_id = {T} AND ({scope}) AND c.status = 'CLOSED' GROUP BY 1, 2;"""):
        out[v]["payments"][typ] = {"count": int(n), "amountCents": int(applied)}
        out[v]["rounding"] += int(rounding)
    for v, item, name, qty, rev in db.rows(f"""
        SELECT l.venue_id, coalesce(l.item_id, ''), min(l.name_en), coalesce(sum(l.qty), 0), coalesce(sum(l.line_total_cents), 0)
        FROM check_lines l JOIN checks c ON c.tenant_id = l.tenant_id AND c.venue_id = l.venue_id AND c.check_id = l.check_id
        WHERE c.tenant_id = {T} AND ({scope}) AND c.status = 'CLOSED' GROUP BY 1, 2;"""):
        out[v]["items"][item] = {"name": name, "qty": int(qty), "revenueCents": int(rev)}
    for v, h, n, gross in db.rows(f"""
        SELECT c.venue_id, extract(hour FROM c.closed_at AT TIME ZONE {z})::int, count(*), coalesce(sum(c.grand_total_cents), 0)
        FROM checks c WHERE c.tenant_id = {T} AND ({scope}) AND c.status = 'CLOSED' GROUP BY 1, 2;"""):
        out[v]["hourly"][h] = {"checkCount": int(n), "grossCents": int(gross)}
    for s in stores:
        o = out[s.id]
        o["rounding"] -= o["refunds"][3]
        cl = sum(r[0] for r in o["days"].values())
        cg = sum(r[1] for r in o["days"].values())
        g = cg - o["refunds"][1]
        t = sum(r[2] for r in o["days"].values()) - o["refunds"][2]
        o["totals"] = {"grossCents": g, "taxCents": t, "netCents": g - t, "checkCount": cl,
                       "avgCheckCents": cg // cl if cl else 0, "voidCount": o["voids"][0], "voidAmountCents": o["voids"][1],
                       "refundCount": o["refunds"][0], "refundAmountCents": o["refunds"][1], "cashRoundingCents": o["rounding"]}
    return out


# ------------------------------------------------------------------ printing

def money(cents: int) -> str:
    sign = "-" if cents < 0 else ""
    return f"{sign}${abs(cents) / 100:,.2f}"


def fmt_n(n: int) -> str:
    return f"{n:,}"


def describe_specials(store: Store) -> list[str]:
    out = []
    for it in store.items:
        if it.available_days:
            out.append(f"{it.name_en} only {'/'.join(d.capitalize() for d in it.available_days)}")
        for sp in it.specials:
            for vid, cents in sorted(sp.prices.items()):
                window = f" {sp.start}-{sp.end}" if sp.start else ""
                out.append(f"{it.name_en} ({vid.split(':')[-1]}) {money(cents)} on {'/'.join(d.capitalize() for d in sp.days)}{window}")
    return out


def print_plan(stores, gens, wipes, ends, args, first, last, db_desc):
    print("demo-reseed: " + ("DOING IT (--yes)" if args.yes else "PLAN, nothing changed (add --yes to do it)"))
    print(f"  target   {db_desc}")
    print(f"  tenant   {TENANT}    seed {args.seed}    {args.days} days: {first} to {last} (ends yesterday)")
    for s in stores:
        f, e = gens[s.id].facts(), gens[s.id].expected()
        print()
        print(f"{s.name} ({s.id}), {s.tz}, {s.currency}")
        w = wipes.get(s.id)
        end = ends[s.id]
        print(f"  DELETE   everything before {end.strftime('%Y-%m-%d %H:%M %Z')}" + ("" if w else "  (offline: not counted)"))
        if w:
            c = w["checks"]
            span = f"{c['from']} to {c['to']}, " if c["n"] else ""
            print(f"           checks {fmt_n(c['n'])} ({span}{money(c['cents'])} closed sales)")
            print("           " + "  ".join(f"{k} {fmt_n(w[k]['n'])}" for k in
                                             ("check_lines", "check_tenders", "refunds", "refund_lines", "shifts", "cash_movements", "fuel_sales")))
            print(f"  KEEP     {fmt_n(w['kept_checks']['n'])} sale(s) from {end.date()} on ({money(w['kept_checks']['cents'])}); "
                  f"open shifts: {w['open_shifts']['n']}" + (f" (oldest opened {w['open_shifts']['from'][:16]})" if w["open_shifts"]["n"] else ""))
            if w["clash"]["n"]:
                print(f"  REFUSE   the store itself sent {w['clash']['n']} sale/shift/refund/cash number(s) in {NUM_MIN:,}-{NUM_MAX:,}: "
                      "its own numbering reached the demo range, so nothing numbered there can be wiped")
            print(f"  NUMBERS  demo checks #{NUM_MIN:,} on; the store's own highest check # is {w['real_max']['cents']:,}"
                  f" ({fmt_n(NUM_MIN - w['real_max']['cents'])} to go before it would reach them)")
        t = e["totals"]
        print(f"  INSERT   checks {fmt_n(f['checks'])} closed + {f['voids']} void ({f['perDay'][0]}-{f['perDay'][1]} a day), "
              f"lines {fmt_n(f['lines'])}, tenders {fmt_n(f['tenders'])}, refunds {f['refunds']}, shifts {f['shifts']}, "
              f"cash movements {f['movements']}")
        print(f"           {e['from']} to {e['to']}: gross {money(t['grossCents'])}  tax {money(t['taxCents'])}  "
              f"net {money(t['netCents'])}  avg check {money(t['avgCheckCents'])}")
        print(f"           card {f['cardShare'] * 100:.0f}% / cash {100 - f['cardShare'] * 100:.0f}%   card tips {money(f['tips'])} "
              f"(Z-reports only)   cash rounding {money(t['cashRoundingCents'])}   voids {t['voidCount']} ({money(t['voidAmountCents'])})"
              f"   refunds {t['refundCount']} ({money(t['refundAmountCents'])})")
        if s.counter:
            print(f"           kiosk {f['kiosk'] * 100 // max(1, f['counterTotal'])}% of orders, take-out "
                  f"{f['takeOut'] * 100 // max(1, f['checks'])}% (both stay at the store; the cloud has no column for them)")
        else:
            print(f"           avg covers {f['covers']:.1f}, carry-out orders {f['carryOut']}")
        g = gens[s.id]
        months = e["months"]
        print("  MONTHS   " + "  ".join(f"{m[2:]} {money(v['grossCents'])[:-3]}" for m, v in months.items()))
        if f["closedDays"]:
            print(f"  CLOSED   {', '.join(f['closedDays'])} (Thanksgiving, Christmas: no sales)")
        if f["events"]:
            print(f"  EVENTS   local-event Saturdays (+{(EVENT_LIFT - 1) * 100:.0f}%): {', '.join(f['events'])}")
        if g.today_until:
            te = g.expected(today=True)["totals"]
            print(f"  TODAY    {fmt_n(f['today'])} sales up to {g.today_until.strftime('%H:%M')} ({money(te['grossCents'])}), "
                  f"numbered like the rest, no shift (live sales today are kept)")
        sp = describe_specials(s)
        print(f"  MENU     {f['items']} items from the cloud catalog" + (f"; {'; '.join(sp)}" if sp else ""))
        print(f"           specials and day-only dishes from {g.since} (before that: menu prices, no lift)")
        if f["specialLines"]:
            print("           special-price lines: " + ", ".join(f"{k} x{v}" for k, v in sorted(f["specialLines"].items())))
        for lf in special_lifts(gens[s.id]):
            when = "/".join(d.capitalize() for d in lf["days"]) + (f" {lf['from']}-{lf['to']}" if lf["from"] else "")
            size = lf["variant"].split(":")[-1]
            if lf["kind"] == "day":
                detail = (f"{lf['unitsPerDay']:.1f} a day vs {lf['otherUnitsPerDay']:.1f} on other weekdays "
                          f"({pct(lf['vsOtherDays'])})")
            else:
                detail = (f"{lf['unitsPerDay']:.1f} in the window a day vs {lf['otherUnitsPerDay']:.1f} at the weekend "
                          f"({pct(lf['vsOtherDays'])}); per check {pct(lf['vsBefore'])} vs the 2 hours before; "
                          f"the window's share of the day's checks {pct(lf['halo'])} vs before {lf['since']}")
            if lf["vsBeforeStart"] is not None:
                detail += f"; {pct(lf['vsBeforeStart'])} vs the same {'days' if lf['kind'] == 'day' else 'hours'} before {lf['since']}"
            print(f"  LIFT     {lf['name']} ({size}) {when}: {pct(lf['lift'])}, {lf['verdict'].upper()}: {detail}")
        if f["dayOnly"]:
            print("           day-only items sold on: " + ", ".join(f"{k} {'/'.join(v)}" for k, v in sorted(f["dayOnly"].items())))
        if s.last_taxes:
            now = sorted((x.get("code"), str(x.get("ratePercent"))) for x in s.last_taxes)
            mine = sorted((x[0], x[3]) for x in NC_TAXES)
            if now != mine:
                print(f"  WARNING  the store's latest sale was taxed {now}; this script charges {mine}")


def print_verify(stores, got, expected, first, last):
    print()
    print(f"VERIFY: the reports' own sums, {first} to {last} (compare with the portal: Reports, that date range)")
    ok = True
    combined = {}
    for s in stores:
        o = got[s.id]
        t = o["totals"]
        print(f"  {s.name} ({s.id})")
        print(f"    gross {money(t['grossCents'])}  net {money(t['netCents'])}  tax {money(t['taxCents'])}  checks {fmt_n(t['checkCount'])}"
              f"  avg {money(t['avgCheckCents'])}  voids {t['voidCount']}  refunds {t['refundCount']} ({money(t['refundAmountCents'])})"
              f"  cash rounding {money(t['cashRoundingCents'])}")
        pays = "  ".join(f"{k} {money(v['amountCents'])} ({fmt_n(v['count'])})" for k, v in sorted(o["payments"].items()))
        print(f"    payments: {pays}")
        top = sorted(o["items"].items(), key=lambda kv: -kv[1]["revenueCents"])[:5]
        print("    top items: " + ", ".join(f"{v['name']} {money(v['revenueCents'])}" for _, v in top))
        if o["hourly"]:
            peak = max(o["hourly"].items(), key=lambda kv: kv[1]["grossCents"])
            print(f"    busiest hour: {int(peak[0]):02d}:00 ({money(peak[1]['grossCents'])}, {fmt_n(peak[1]['checkCount'])} checks)")
        if expected:
            e = expected[s.id]
            diffs = [k for k in e["totals"] if e["totals"][k] != t.get(k)]
            if {k: v for k, v in e["payments"].items()} != o["payments"]:
                diffs.append("payments")
            if any(e["items"][k]["revenueCents"] != o["items"].get(k, {}).get("revenueCents") for k in e["items"]):
                diffs.append("items")
            if any(v["grossCents"] != o["hourly"].get(h, {}).get("grossCents") for h, v in e["hourly"].items()):
                diffs.append("hourly")
            print("    matches what was generated" if not diffs else f"    MISMATCH vs generated: {', '.join(diffs)}")
            ok = ok and not diffs
        for d, r in o["days"].items():
            c = combined.setdefault(d, {})
            c[s.id] = r
    days = sorted(combined)[-7:]
    print("  last 7 days (gross after refunds / checks):")
    head = "    date        " + "".join(f"{s.id:>24}" for s in stores) + f"{'all stores':>24}"
    print(head)
    for d in days:
        cells, tg, tc = [], 0, 0
        for s in stores:
            r = combined[d].get(s.id, [0, 0, 0, 0, 0])
            g = r[1] - r[3]
            tg += g
            tc += r[0]
            cells.append(f"{money(g)} / {r[0]:>4}")
        wd = dt.date.fromisoformat(d).strftime("%a")
        print(f"    {d} {wd}" + "".join(f"{c:>24}" for c in cells) + f"{money(tg) + ' / ' + str(tc):>24}")
    return ok


def check_figures(db: Db, store: Store, end: dt.datetime) -> dict:
    """--check: what the cloud holds for a store, read-only."""
    T, V, e = q(TENANT), q(store.id), ts(end)
    row = db.rows(f"""
SELECT (SELECT count(*) FROM checks WHERE tenant_id = {T} AND venue_id = {V} AND closed_at < {e}),
       (SELECT coalesce(min(closed_at AT TIME ZONE {q(store.tz)})::date::text, '') FROM checks WHERE tenant_id = {T} AND venue_id = {V}),
       (SELECT count(*) FROM checks WHERE tenant_id = {T} AND venue_id = {V} AND closed_at >= {e}),
       (SELECT count(*) FROM shifts WHERE tenant_id = {T} AND venue_id = {V} AND status = 'OPEN'),
       (SELECT coalesce(max(aggregate_id::bigint), 0) FROM events WHERE tenant_id = {T} AND venue_id = {V}
          AND aggregate_type = 'check' AND aggregate_id ~ '^[0-9]{{1,12}}$'),
       (SELECT count(*) FROM checks WHERE tenant_id = {T} AND venue_id = {V} AND check_id BETWEEN {NUM_MIN} AND {NUM_MAX});""")[0]
    return {"before": int(row[0]), "first": row[1], "today": int(row[2]), "open": int(row[3]), "realMax": int(row[4]),
            "inRange": int(row[5])}


def print_check(doc: dict, db: Db | None, desc: str, args) -> None:
    """--check: the catalog each store will be generated from, exactly as the generator reads it. Changes nothing."""
    print("demo-reseed --check: read-only, nothing changed")
    print(f"  target   {desc}")
    if doc.get("tenantsInDb"):
        print(f"  tenants in this database: {', '.join(doc['tenantsInDb'])}")
    for v in sorted(doc["venues"], key=lambda v: (v["id"] not in args.store_list, v["id"])):
        st = store_from_doc(v)
        chosen = "reseeded by default" if st.id in DEFAULT_STORES else "not reseeded unless --stores names it"
        print()
        print(f"{st.name} ({st.id}), {st.tz}, {st.currency}: {chosen}")
        roles = {}
        for it in st.items:
            roles[it.role] = roles.get(it.role, 0) + 1
        print(f"  menu     {len(st.items)} live items with a price in {len(st.categories)} categories "
              f"({', '.join(f'{k} {n}' for k, n in sorted(roles.items()))})")
        day_only = [it for it in st.items if it.available_days]
        print("  day-only " + ("; ".join(f"{it.name_en} ({it.id}) on {'/'.join(d.capitalize() for d in it.available_days)}"
                                         for it in day_only) or "none"))
        n = 0
        for it in st.items:
            sizes = {x.id: x for x in it.variants}
            for sp in it.specials:
                for vid, cents in sorted(sp.prices.items()):
                    n += 1
                    size = sizes.get(vid)
                    when = "/".join(d.capitalize() for d in sp.days) + (f" {sp.start}-{sp.end}" if sp.start else " all day")
                    note = ("" if size and cents < size.price else
                            "  <- NOT RUNG: no such size" if not size else "  <- NOT RUNG: not cheaper than the menu price")
                    reg = money(size.price) if size else "?"
                    print(f"  special  {it.name_en} {vid.split(':')[-1]}: {money(cents)} (menu {reg}) {when}"
                          f"{' ' + repr(sp.label) if sp.label else ''}{note}")
        if not n:
            print("  special  none")
        print(f"  floor    {len(st.tables)} tables" + (f" in {', '.join(sorted({t[4] for t in st.tables}))}" if st.tables else
                                                      " (counter store: sales on the counter register)"))
        print(f"  staff    {', '.join(f'{x[1]} ({x[2].lower()})' for x in st.staff)}")
        if st.last_taxes:
            got = ", ".join(f"{x.get('code')} {x.get('ratePercent')}%" for x in st.last_taxes)
            same = sorted((x.get("code"), str(x.get("ratePercent"))) for x in st.last_taxes) == sorted((t[0], t[3]) for t in NC_TAXES)
            print(f"  tax      latest sale: {got}" + ("  (matches what the tool charges)" if same else
                                                       f"  <- DIFFERS from what the tool charges: {', '.join(t[0] + ' ' + t[3] + '%' for t in NC_TAXES)}"))
        if db:
            today = args.today or dt.datetime.now(st.zone).date()
            f = check_figures(db, st, window_end(st, today, args.include_today))
            print(f"  sales    {fmt_n(f['before'])} before {today} (since {f['first'] or '-'}), {f['today']} from {today} on, "
                  f"{f['open']} open shift(s)")
            print(f"  numbers  the store's own highest check # is {f['realMax']:,}; demo checks start at #{NUM_MIN:,}"
                  f" ({f['inRange']:,} checks already numbered {NUM_MIN:,}-{NUM_MAX:,})")


# ------------------------------------------------------------------ main

def parse_args(argv):
    p = argparse.ArgumentParser(description="Wipe and regenerate the Copper Lantern demo sales history (plan by default).",
                                formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog="See the header of this file for what it touches and why.")
    p.add_argument("--tenant", default=TENANT, help="must be copperlantern (anything else is refused)")
    p.add_argument("--stores", default=",".join(DEFAULT_STORES), help="comma-separated store ids (default vieux-port,express)")
    p.add_argument("--days", type=int, default=365, help="days of history, ending yesterday (default 365: a year)")
    p.add_argument("--specials-since", type=dt.date.fromisoformat,
                   help=f"the day the specials (and day-only dishes) began; default {SPECIALS_WEEKS} weeks before today")
    p.add_argument("--today-until", metavar="HH:MM", type=lambda v: dt.time.fromisoformat(v),
                   help="also generate today's sales up to this store time (typical for the weekday); live sales stay")
    p.add_argument("--volume", type=float, default=1.0, help="scale every day's number of sales (default 1.0)")
    p.add_argument("--sql-style", choices=("copy", "insert"), default="copy",
                   help="copy (default: COPY ... FROM STDIN, compact and fast) or insert (plain INSERTs)")
    p.add_argument("--seed", type=int, default=DEFAULT_SEED, help=f"random seed (default {DEFAULT_SEED})")
    p.add_argument("--today", type=dt.date.fromisoformat, help="pretend today is this date (store time); default: today")
    p.add_argument("--include-today", action="store_true", help="also wipe today's sales (the history still ends yesterday)")
    p.add_argument("--yes", action="store_true", help="do it: backup, wipe and insert in one transaction, verify")
    p.add_argument("--dry-run-sql", metavar="FILE", help="write the SQL to FILE and stop (changes nothing)")
    p.add_argument("--verify-only", action="store_true", help="print the report figures for the window and stop")
    p.add_argument("--check", action="store_true",
                   help="read-only: print each store's catalog as this tool sees it (specials, day-only items, floor, "
                        "staff, tax, sales on file, highest check #) and stop")
    p.add_argument("--plan-json", metavar="FILE", help="write the expected report figures as JSON (tests)")
    p.add_argument("--menu-json", metavar="FILE", help="generate offline from this catalog document (no database)")
    p.add_argument("--ssh", default=os.environ.get("DEMO_RESEED_SSH"), help="user@host of the portal box (env DEMO_RESEED_SSH)")
    p.add_argument("--ssh-key", default=os.environ.get("DEMO_RESEED_SSH_KEY"), help="ssh identity file (env DEMO_RESEED_SSH_KEY)")
    p.add_argument("--container", default=os.environ.get("DEMO_RESEED_CONTAINER"),
                   help="Postgres container on the box (env DEMO_RESEED_CONTAINER; default: found by name)")
    p.add_argument("--db-user", default="pos")
    p.add_argument("--db-name", default="pos_cloud")
    p.add_argument("--dsn", default=os.environ.get("DEMO_RESEED_DSN"), help="local Postgres URL instead of ssh (env DEMO_RESEED_DSN)")
    p.add_argument("--backup-dir", default="~/backups", help="--dsn only: where the local backup goes (default ~/backups)")
    p.add_argument("--no-backup", action="store_true", help="--dsn only (tests): skip the backup")
    a = p.parse_args(argv)
    if a.tenant != TENANT:
        raise Refused(f"refusing: tenant '{a.tenant}' is not {TENANT}. This tool only reseeds the Copper Lantern demo "
                      "(never sagepoppy or a real client).")
    a.store_list = [x.strip() for x in a.stores.split(",") if x.strip()]
    bad = [x for x in a.store_list if x not in KNOWN_STORES]
    if bad or not a.store_list or len(set(a.store_list)) != len(a.store_list):
        raise Refused(f"refusing: stores must be among {', '.join(KNOWN_STORES)} (got {a.stores})")
    if not 1 <= a.days <= 366:
        p.error("--days must be 1-366")
    if a.no_backup and not a.dsn:
        raise Refused("refusing: --no-backup is for a local --dsn database only")
    if not a.menu_json and not a.dsn and not a.ssh:
        p.error("say where the database is: --ssh user@host (or DEMO_RESEED_SSH), --dsn, or --menu-json for offline")
    if a.menu_json and (a.yes or a.verify_only):
        p.error("--menu-json is offline: use it with --dry-run-sql / --plan-json, not --yes / --verify-only")
    return a


def main(argv=None) -> int:
    try:
        args = parse_args(argv)
    except Refused as r:
        print(r.message, file=sys.stderr)
        return 2
    db = None
    if args.menu_json:
        doc = json.loads(Path(args.menu_json).read_text())
        desc = f"offline catalog {args.menu_json} (no database)"
    else:
        db = Db(args.ssh, args.ssh_key, args.container, args.db_user, args.db_name, args.dsn)
        db.find_container()
        doc = load_catalog(db)
        desc = db.describe()
    if doc.get("tenant", TENANT) != TENANT:
        print(f"refusing: catalog is for tenant {doc.get('tenant')}", file=sys.stderr)
        return 2
    if args.check:
        print_check(doc, db, desc, args)
        return 0
    by_id = {v["id"]: v for v in doc["venues"]}
    missing = [s for s in args.store_list if s not in by_id]
    if missing:
        print(f"refusing: tenant {TENANT} has no store(s) {', '.join(missing)} here (has: {', '.join(sorted(by_id)) or 'none'})",
              file=sys.stderr)
        return 2
    stores = [store_from_doc(by_id[s]) for s in args.store_list]
    for s in stores:
        if not s.items:
            print(f"refusing: store {s.id} has no menu in the cloud (no live items with a price)", file=sys.stderr)
            return 2
    today = args.today or dt.datetime.now(stores[0].zone).date()
    first, last = today - dt.timedelta(days=args.days), today - dt.timedelta(days=1)
    if args.verify_only:
        print_verify(stores, verify(db, stores, first, last), None, first, last)
        return 0
    ends = {s.id: window_end(s, args.today or dt.datetime.now(s.zone).date(), args.include_today) for s in stores}
    since = args.specials_since or today - dt.timedelta(weeks=SPECIALS_WEEKS)
    t0 = time.monotonic()
    gens = {s.id: Generator(s, args.seed, first, args.days, args.volume * (0.25 if s.id == "plateau" else 1.0),
                            since=since, today_until=args.today_until).generate() for s in stores}
    gen_s = time.monotonic() - t0
    wipes = {s.id: count_wipe(db, s, ends[s.id]) for s in stores} if db else {}
    print_plan(stores, gens, wipes, ends, args, first, last, desc)
    expected = {s.id: gens[s.id].expected() for s in stores}
    if args.plan_json:
        Path(args.plan_json).write_text(json.dumps({
            "from": first.isoformat(), "to": last.isoformat(), "today": today.isoformat(), "since": since.isoformat(),
            "stores": expected, "todaySoFar": {s.id: gens[s.id].expected(today=True) for s in stores},
            "lifts": {s.id: special_lifts(gens[s.id]) for s in stores}}, indent=1))
    if any(w["clash"]["n"] for w in wipes.values()):
        print("\nrefusing: see REFUSE above (nothing changed)", file=sys.stderr)
        return 2
    t0 = time.monotonic()
    sql = render_sql(stores, gens, ends, args.sql_style)
    print(f"\ngenerated in {gen_s:.1f} s, SQL ({args.sql_style}) {len(sql.encode()) / 1e6:.1f} MB in {time.monotonic() - t0:.1f} s")
    if args.dry_run_sql:
        Path(args.dry_run_sql).write_text(sql)
        print(f"SQL written to {args.dry_run_sql}; nothing changed.")
        return 0
    if not args.yes:
        print("\nnothing changed. Run the same command with --yes to back up, wipe and insert (one transaction).")
        return 0
    print()
    if args.no_backup:
        print("backup: skipped (--no-backup, local test database)")
    else:
        print("backup: pg_dump of the whole database ...", flush=True)
        path = db.backup(args.backup_dir)
        print(f"backup: {path}")
        where = f"ssh {db.ssh} " if not db.dsn else ""
        restore = (f"gunzip -c {path} | docker exec -i {db.container} psql -U {db.user} -d {db.dbname}" if not db.dsn
                   else f"gunzip -c {path} | psql <dsn>")
        print(f"        to put everything back: {where}'{restore}'")
    print("applying: one transaction ...", flush=True)
    t0 = time.monotonic()
    db.run(sql)
    print(f"applied and committed in {time.monotonic() - t0:.1f} s.")
    good = print_verify(stores, verify(db, stores, first, last), expected, first, last)
    if args.today_until:
        now = verify(db, stores, today, today)
        for s in stores:
            t = now[s.id]["totals"]
            print(f"  today {today} at {s.id}: {fmt_n(t['checkCount'])} checks, {money(t['grossCents'])} "
                  f"(generated up to {args.today_until.strftime('%H:%M')}, plus any live sales)")
    return 0 if good else 1


if __name__ == "__main__":
    sys.exit(main())
