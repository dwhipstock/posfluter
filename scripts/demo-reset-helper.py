#!/usr/bin/env python3
"""DEMO-ONLY helper for scripts/demo-reset.sh. Not used by the product.

Subcommands (the reset script calls them in this order):

  info      --db OLD                    what the old store database holds (JSON)
  prep      --db NEW --old OLD [--install-id ID] [--id-floor N]
            carry the store's identity over to a freshly seeded database: its
            sync install id (the cloud pins a venue to it), its id counters (so
            new checks never reuse an old check number the portal already
            has) and its venue settings (Wi-Fi slip, printer, receipt footer)
  photos    --url URL --from DIR        re-upload the menu photos from a backup
  seed      --url URL --kind pub|retail --days N --tz ZONE --plan PLAN.json
            ring up N past business days of realistic sales through the store's
            own API (reusing scripts/demo-seed.py / demo-seed-retail.py), one
            shift per day, then open today's shift. Writes a time plan.
  backdate  --db DB --plan PLAN.json --tz ZONE
            with the store STOPPED and before its first sync: move those sales
            to the planned times (tables + the unsent sync events' payloads)

Everything here talks to a store that is running OFFLINE (no cloud sync), so
nothing leaves the Mac until demo-reset.sh restarts the store with its cloud.
"""
from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import importlib.util
import io
import json
import mimetypes
import os
import random
import re
import sqlite3
import sys
import urllib.request
import uuid
from pathlib import Path
from zoneinfo import ZoneInfo

SCRIPTS = Path(__file__).resolve().parent
sys.dont_write_bytecode = True  # importing the seeders must not leave __pycache__ in scripts/
MANAGER_PIN = os.environ.get("DEMO_MANAGER_PIN", "1234")
UTC = dt.timezone.utc


def load_seeder(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), SCRIPTS / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# --------------------------------------------------------------------------- info/prep

# tables whose AUTOINCREMENT ids the cloud keys its history on (and the rest, for tidiness)
def sequences(con) -> dict[str, int]:
    try:
        return {n: s for n, s in con.execute("SELECT name, seq FROM sqlite_sequence")}
    except sqlite3.OperationalError:
        return {}


def cmd_info(a):
    con = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    state = dict(con.execute("SELECT key, value FROM sync_state"))
    out = {
        "install_id": state.get("install_id", ""),
        "push_hwm": int(state.get("push_hwm", "0") or 0),
        "outbox_max": con.execute("SELECT COALESCE(MAX(id),0) FROM sync_outbox").fetchone()[0],
        "sequences": sequences(con),
        "checks": con.execute("SELECT COUNT(*) FROM checks").fetchone()[0],
        "max_check": con.execute("SELECT COALESCE(MAX(id),0) FROM checks").fetchone()[0],
        "open_checks": con.execute("SELECT COUNT(*) FROM checks WHERE status='OPEN'").fetchone()[0],
        "photos": con.execute("SELECT COUNT(*) FROM items WHERE photo_path IS NOT NULL").fetchone()[0],
        "items": con.execute("SELECT COUNT(*) FROM items WHERE deleted_at IS NULL").fetchone()[0],
        "integrity": con.execute("PRAGMA integrity_check").fetchone()[0],
        "sales_span": "",
    }
    first, last = con.execute("SELECT MIN(closed_at), MAX(closed_at) FROM checks WHERE closed_at IS NOT NULL").fetchone()
    if first and a.tz:
        zone = ZoneInfo(a.tz)
        f, l = (dt.datetime.fromisoformat(x.replace("Z", "+00:00")).astimezone(zone).strftime("%a %d %b") for x in (first, last))
        out["sales_span"] = f if f == l else f"{f} – {l}"
    if a.field:
        v = out
        for part in a.field.split("."):
            v = v.get(part, "") if isinstance(v, dict) else ""
        print(v)
    else:
        print(json.dumps(out))


def cmd_prep(a):
    old = sqlite3.connect(f"file:{a.old}?mode=ro", uri=True)
    new = sqlite3.connect(a.db)
    with new:
        if a.install_id:
            new.execute("INSERT OR REPLACE INTO sync_state(key, value) VALUES ('install_id', ?)", (a.install_id,))
        # id counters: never below the old store's (or the floor, on a cloud switch)
        mine = sequences(new)
        for name, seq in sequences(old).items():
            want = max(seq, a.id_floor, mine.get(name, 0))
            if name in mine:
                new.execute("UPDATE sqlite_sequence SET seq=? WHERE name=?", (want, name))
            else:
                new.execute("INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)", (name, want))
        if a.id_floor:
            for (name,) in new.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND sql LIKE '%AUTOINCREMENT%'").fetchall():
                if name not in sequences(new):
                    new.execute("INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)", (name, a.id_floor))
        # venue settings: same schema (same server build) → copy the shared columns
        cols_old = [r[1] for r in old.execute("PRAGMA table_info(venue_settings)")]
        cols_new = {r[1] for r in new.execute("PRAGMA table_info(venue_settings)")}
        cols = [c for c in cols_old if c in cols_new and c != "id"]
        row = old.execute(f"SELECT {', '.join(cols)} FROM venue_settings WHERE id=1").fetchone()
        if row:
            new.execute(f"UPDATE venue_settings SET {', '.join(c + '=?' for c in cols)} WHERE id=1", row)
    print(f"  identity {a.install_id[:8] + '…' if a.install_id else '(new — minted on first sync)'}; "
          f"next check #{sequences(new).get('checks', 0) + 1}; venue settings kept")


# --------------------------------------------------------------------------- photos

def http(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw else {}


def cmd_photos(a):
    base = a.url.rstrip("/")
    token = http("POST", base + "/login", json.dumps({"pin": MANAGER_PIN}).encode(),
                 {"Content-Type": "application/json"})["token"]
    items = {i["id"] for i in http("GET", base + "/items", headers={"Authorization": "Bearer " + token})}
    done = skipped = 0
    for f in sorted(Path(a.src).glob("*")):
        if f.suffix.lower() not in (".jpg", ".jpeg", ".png") or f.stem not in items:
            skipped += f.is_file()
            continue
        mime = mimetypes.guess_type(f.name)[0] or "image/jpeg"
        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"managerPin\"\r\n\r\n{MANAGER_PIN}\r\n"
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"{f.name}\"\r\n"
                f"Content-Type: {mime}\r\n\r\n").encode() + f.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        http("POST", f"{base}/items/{f.stem}/photo", body,
             {"Authorization": "Bearer " + token, "Content-Type": f"multipart/form-data; boundary={boundary}"})
        done += 1
    print(f"  restored {done} menu photo(s)" + (f" ({skipped} file(s) had no matching item)" if skipped else ""))


# --------------------------------------------------------------------------- seed

# hour-of-day weights (venue local time): when sales happen
PUB_HOURS = {11: 1, 12: 5, 13: 5, 14: 2, 15: 1, 16: 2, 17: 4, 18: 7, 19: 8, 20: 6, 21: 4, 22: 2}
PUB_OPEN, PUB_CLOSE = dt.time(11, 30), dt.time(23, 15)
SHOP_HOURS = {10: 2, 11: 2, 12: 3, 13: 3, 14: 3, 15: 4, 16: 6, 17: 8, 18: 8, 19: 6, 20: 4}
SHOP_OPEN, SHOP_CLOSE = dt.time(9, 45), dt.time(21, 10)

# retail baskets: product ids from SagePoppySeed, grouped the way people shop
SHOP_BASKETS = [
    ["golden-lager-6", "chips-sea-salt", "ice-7"],
    ["coastal-cab", "valley-chard"],
    ["club-soda", "tonic", "lime-juice", "ice-20"],
    ["wave-variety-12", "tortilla-chips"],
    ["agave-blanco", "marg-mix", "ice-7"],
]


def times_for_day(day: dt.date, n: int, hours: dict, opens: dt.time, closes: dt.time, zone, rng, first_after=10):
    slots = [h for h, w in hours.items() for _ in range(w)]
    out = []
    for _ in range(n):
        h = rng.choice(slots)
        t = dt.datetime.combine(day, dt.time(h, rng.randrange(60), rng.randrange(60)), zone)
        lo = dt.datetime.combine(day, opens, zone) + dt.timedelta(minutes=first_after)
        hi = dt.datetime.combine(day, closes, zone) - dt.timedelta(minutes=15)
        if t < lo:  # too early/late for the opening hours: somewhere just inside them
            t = lo + dt.timedelta(seconds=rng.randrange(1800))
        if t > hi:
            t = hi - dt.timedelta(seconds=rng.randrange(1800))
        out.append(t)
    return sorted(out)


class Plan:
    """(real instant, target instant) markers: everything the store stamps between
    two markers is moved by the first marker's offset (see backdate)."""

    def __init__(self):
        self.marks = []

    def at(self, target: dt.datetime):
        self.marks.append([dt.datetime.now(UTC).isoformat(), target.astimezone(UTC).isoformat()])

    def dump(self, path):
        Path(path).write_text(json.dumps({"marks": self.marks}, indent=1))


def cmd_seed(a):
    zone = ZoneInfo(a.tz)
    rng = random.Random(a.seed)
    today = dt.datetime.now(zone).date()
    days = [today - dt.timedelta(days=d) for d in range(a.days, 0, -1)]
    plan = Plan()
    pub = a.kind == "pub"
    mod = load_seeder("demo-seed" if pub else "demo-seed-retail")
    mod.BASE = a.url.rstrip("/")
    money = mod.cad if pub else mod.usd
    quiet = contextlib.redirect_stdout(io.StringIO())

    def login(pin):
        if pub:
            mod.MANAGER_PIN = pin
            with quiet:
                mod.login()
        else:
            mod.login(pin)

    login(MANAGER_PIN)
    if pub:
        menu = mod.menu()
        # skip open-price / zero-price lines: pick real menu items
        menu = [m for m in menu if m[3] > 0]
        tables = [(t["id"], t.get("label", t["id"]), z.get("nameEn", z.get("id")))
                  for z in mod.call("GET", "/zones") for t in z.get("tables", [])]
        if len(menu) < 6 or not tables:
            raise SystemExit("the pub menu/floor looks unseeded")
    else:
        items = {i["id"]: i for i in mod.call("GET", "/items")}
        scannable = [i for i, v in items.items() if v.get("barcode") and v.get("active", True)]
        baskets = [b for b in SHOP_BASKETS if all(p in items for p in b)]
    float_cents = 30000 if pub else 20000
    summary = []

    for day in days:
        is_weekend = day.weekday() >= 4
        n = rng.randint(30, 38) if pub else rng.randint(34, 44)
        if is_weekend:
            n += 6
        hours, opens, closes = (PUB_HOURS, PUB_OPEN, PUB_CLOSE) if pub else (SHOP_HOURS, SHOP_OPEN, SHOP_CLOSE)
        # a check closes after a meal: the pub's first ones close ~45 min after opening
        stamps = times_for_day(day, n, hours, opens, closes, zone, rng, first_after=45 if pub else 10)
        plan.at(dt.datetime.combine(day, opens, zone))
        login(MANAGER_PIN)
        mod.call("POST", "/shifts", {"openingFloatCents": float_cents, "managerPin": MANAGER_PIN})
        gross = 0
        for when in stamps:
            plan.at(when)
            if pub:
                # most checks by the floor server, some by the manager
                login("9999" if rng.random() < 0.7 else MANAGER_PIN)
                basket = [rng.randrange(len(menu)) for _ in range(rng.choice([2, 3, 3, 4, 4, 5, 6, 7]))]
                pay = "CASH" if rng.random() < 0.3 else "CARD"
                with quiet:
                    gross += mod.make_sale(rng.choice(tables), basket, pay, menu)
            else:
                # the Spanish-speaking cashier rings some sales (Spanish receipts)
                login("5555" if rng.random() < 0.25 else ("9999" if rng.random() < 0.6 else MANAGER_PIN))
                if baskets and rng.random() < 0.55:
                    basket = list(rng.choice(baskets))
                else:
                    basket = rng.sample(scannable, rng.choice([1, 1, 2, 2, 3, 4]))
                pay = "CASH" if rng.random() < 0.35 else "CARD"
                _, total, _ = mod.ring_sale(items, basket, pay)
                gross += total
        # close the day: count the drawer (a small over/short now and then)
        plan.at(dt.datetime.combine(day, closes, zone))
        login(MANAGER_PIN)
        expected = mod.call("GET", "/shifts/current/report").get("expectedCashCents") or 0
        counted = expected + rng.choice([0, 0, 0, 0, 5, -5, 25, -10])
        mod.call("POST", "/shifts/current/close", {"closingCountCents": counted, "managerPin": MANAGER_PIN})
        summary.append(f"{day:%a %d %b}: {len(stamps)} {'checks' if pub else 'sales'}, {money(gross)}")

    # today: the register is open with a fresh float, at the real time
    now = dt.datetime.now(zone)
    plan.at(now)
    login(MANAGER_PIN)
    mod.call("POST", "/shifts", {"openingFloatCents": float_cents, "managerPin": MANAGER_PIN})
    plan.dump(a.plan)
    for line in summary:
        print("  " + line)
    print(f"  today: {'shift' if pub else 'register'} open with a {money(float_cents)} float, no sales yet")


# --------------------------------------------------------------------------- backdate

ISO = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:\d{2})$")
# only sales/shift history moves; sessions, devices, the catalog keep real times
TABLES = ["checks", "check_lines", "tenders", "shifts", "cash_movements", "refunds", "age_checks",
          "bill_groups", "bill_group_allocations", "stripe_payments", "sync_outbox"]


def cmd_backdate(a):
    zone = ZoneInfo(a.tz)
    marks = [(dt.datetime.fromisoformat(r), dt.datetime.fromisoformat(t))
             for r, t in json.loads(Path(a.plan).read_text())["marks"]]
    marks.sort()
    first = marks[0][0]

    def move(ts: dt.datetime) -> dt.datetime:
        if ts < first:
            return ts
        real, target = next((m for m in reversed(marks) if m[0] <= ts), marks[0])
        return target + (ts - real)

    def fix(s: str) -> str:
        m = ISO.match(s)
        if not m:
            return s
        ts = dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
        moved = move(ts)
        if moved == ts:
            return s
        frac = f".{moved.microsecond // 1000:03d}" if m.group(2) else ""
        if m.group(3) == "Z":
            return moved.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S") + frac + "Z"
        local = moved.astimezone(zone)
        off = local.strftime("%z")
        return local.strftime("%Y-%m-%dT%H:%M:%S") + frac + off[:3] + ":" + off[3:]

    def walk(v):
        if isinstance(v, str):
            return fix(v)
        if isinstance(v, list):
            return [walk(x) for x in v]
        if isinstance(v, dict):
            return {k: walk(x) for k, x in v.items()}
        return v

    con = sqlite3.connect(a.db)
    moved_rows = 0
    # the checks this seed rang up (stamped after the first marker), before moving them
    seeded = {cid for cid, o in con.execute("SELECT id, opened_at FROM checks")
              if dt.datetime.fromisoformat(o.replace("Z", "+00:00")) >= first}
    with con:
        have = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table in TABLES:
            if table not in have:
                continue
            cols = [r[1] for r in con.execute(f"PRAGMA table_info({table})")]
            for row in con.execute(f"SELECT rowid, {', '.join(cols)} FROM {table}").fetchall():
                rowid, values = row[0], row[1:]
                changes = {}
                for col, val in zip(cols, values):
                    if not isinstance(val, str):
                        continue
                    if table == "sync_outbox" and col == "payload":
                        try:
                            new = json.dumps(walk(json.loads(val)), ensure_ascii=False, separators=(",", ":"))
                        except ValueError:
                            continue
                        if json.loads(new) != json.loads(val):
                            changes[col] = new
                    else:
                        new = fix(val)
                        if new != val:
                            changes[col] = new
                if changes:
                    con.execute(f"UPDATE {table} SET {', '.join(c + '=?' for c in changes)} WHERE rowid=?",
                                [*changes.values(), rowid])
                    moved_rows += 1
        dwell(con, a.kind, seeded)
    print(f"  moved {moved_rows} row(s) to the planned business days")


OPENING_EVENTS = ("check.opened", "check.line_added", "check.line_open_added", "check.line_qty_changed")


def dwell(con, kind: str, seeded: set):
    """The seed rings each check up in one go; give it a believable time open
    (pub: 25–100 min at the table, shop: 1–4 min at the counter) by moving its
    opening and ordering back — never before its shift opened or before the
    previous check at the same table closed."""
    def parse(s):
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))

    def fmt(t):
        return t.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"

    shifts = {sid: parse(o) for sid, o in con.execute("SELECT id, opened_at FROM shifts")}
    last_close: dict[str, dt.datetime] = {}
    rows = con.execute("SELECT id, table_id, opened_at, closed_at, shift_id FROM checks "
                       "WHERE closed_at IS NOT NULL ORDER BY closed_at").fetchall()
    for cid, table, opened, closed, shift in rows:
        o, c = parse(opened), parse(closed)
        if cid not in seeded:
            continue
        rng = random.Random(cid)
        want = dt.timedelta(minutes=rng.uniform(25, 100) if kind == "pub" else rng.uniform(1, 4))
        floor = max(shifts.get(shift, o) + dt.timedelta(minutes=2),
                    last_close.get(table, dt.datetime.min.replace(tzinfo=UTC)) + dt.timedelta(minutes=5 if kind == "pub" else 0, seconds=20))
        floor = min(floor, o)
        # when the floor clamps it, spread the openings out instead of stacking them
        jitter = dt.timedelta(seconds=rng.uniform(0, max(0.0, (c - floor).total_seconds()) * 0.6))
        new_open = max(c - want, floor + jitter)
        last_close[table] = c
        delta = o - new_open
        if delta <= dt.timedelta(0):
            continue
        con.execute("UPDATE checks SET opened_at=? WHERE id=?", (fmt(o - delta), cid))
        for lid, created in con.execute("SELECT id, created_at FROM check_lines WHERE check_id=?", (cid,)).fetchall():
            con.execute("UPDATE check_lines SET created_at=? WHERE id=?", (fmt(parse(created) - delta), lid))
        for oid, etype, created, payload in con.execute(
                "SELECT id, event_type, created_at, payload FROM sync_outbox WHERE aggregate_type='check' AND aggregate_id=?",
                (str(cid),)).fetchall():
            p = json.loads(payload)
            if isinstance(p.get("openedAt"), str):
                p["openedAt"] = shift_iso(p["openedAt"], delta)
            new_created = fmt(parse(created) - delta) if etype in OPENING_EVENTS else created
            con.execute("UPDATE sync_outbox SET created_at=?, payload=? WHERE id=?",
                        (new_created, json.dumps(p, ensure_ascii=False, separators=(",", ":")), oid))


def shift_iso(s: str, delta: dt.timedelta) -> str:
    """Move an offset ISO stamp ('…T11:40:00.065-04:00') back by delta, keeping its style."""
    t = dt.datetime.fromisoformat(s.replace("Z", "+00:00")) - delta
    off = t.strftime("%z")
    zone_part = "Z" if s.endswith("Z") else off[:3] + ":" + off[3:]
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}" + zone_part


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("info"); s.add_argument("--db", required=True); s.add_argument("--field")
    s.add_argument("--tz", default="")
    s = sub.add_parser("prep"); s.add_argument("--db", required=True); s.add_argument("--old", required=True)
    s.add_argument("--install-id", default=""); s.add_argument("--id-floor", type=int, default=0)
    s = sub.add_parser("photos"); s.add_argument("--url", required=True); s.add_argument("--from", dest="src", required=True)
    s = sub.add_parser("seed"); s.add_argument("--url", required=True)
    s.add_argument("--kind", choices=["pub", "retail"], required=True)
    s.add_argument("--days", type=int, default=2); s.add_argument("--tz", required=True)
    s.add_argument("--plan", required=True); s.add_argument("--seed", type=int, default=None)
    s = sub.add_parser("backdate"); s.add_argument("--db", required=True)
    s.add_argument("--kind", choices=["pub", "retail"], default="pub")
    s.add_argument("--plan", required=True); s.add_argument("--tz", required=True)
    a = p.parse_args()
    {"info": cmd_info, "prep": cmd_prep, "photos": cmd_photos, "seed": cmd_seed, "backdate": cmd_backdate}[a.cmd](a)


if __name__ == "__main__":
    main()
