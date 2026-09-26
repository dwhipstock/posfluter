"""Store load: N concurrent devices doing real flows against one store server.

Three demo kinds, each on its own store jar, port and database:
  restaurant  Copper Lantern Plateau: open a check, add items, send to the
              kitchen, add a second round, print the bill, split (30%), pay, close.
              A kitchen screen polls the board and bumps tickets.
  retail      Sage & Poppy (5,000 products): scan barcodes by sales weight,
              search, ID check when the basket needs it, pay, close.
  gas         Pronghorn: pumps through the forecourt simulator (postpay and
              prepay) plus convenience-store baskets.

Devices run closed-loop with no think time: each one starts its next sale as
soon as the last is closed. That measures what the store can take, not what a
real cashier produces (a busy cashier rings maybe one sale a minute).
"""
from __future__ import annotations

import datetime
import os
import random
import sqlite3
import threading
import time

from .common import (PORTS, WORK, Client, HttpError, Recorder, Sampler, fresh_dir, log, save_result,
                     start_simulator, start_store)

MANAGER, CASHIER = "1234", "9999"
DOB = (datetime.date.today() - datetime.timedelta(days=365 * 34)).isoformat()


def store_dir(kind: str) -> str:
    return os.path.join(WORK, f"store-{kind}")


def pay(c: Client, sale: dict, rng: random.Random, card_share: float = 0.6) -> None:
    sid = sale["id"]
    total = sale["grandTotalCents"]
    if rng.random() < card_share:
        c.call("POST", f"/checks/{sid}/tenders/initiate", {"type": "CARD", "amountCents": total},
               name="POST /checks/{id}/tenders/initiate")
        c.call("POST", f"/checks/{sid}/tenders/confirm", {"type": "CARD", "amountCents": total},
               name="POST /checks/{id}/tenders/confirm")
    else:
        tendered = ((total // 2000) + 1) * 2000
        c.call("POST", f"/checks/{sid}/tenders", {"type": "CASH", "amountTenderedCents": tendered},
               name="POST /checks/{id}/tenders")
    c.call("POST", f"/checks/{sid}/finalize", name="POST /checks/{id}/finalize")


def age_check(c: Client, sale: dict) -> dict:
    if sale.get("ageCheckRequired") and not sale.get("ageCleared"):
        r = c.call("POST", f"/retail/sales/{sale['id']}/age-check",
                   {"method": "MANUAL", "dateOfBirth": DOB, "cashierSawId": True},
                   name="POST /retail/sales/{id}/age-check")
        return r.get("check", r)
    return sale


def open_shift(base: str) -> Client:
    m = Client(base)
    m.login(MANAGER)
    try:
        m.call("POST", "/shifts", {"openingFloatCents": 50000, "managerPin": MANAGER})
    except HttpError as e:
        if e.status != 409:
            raise
    return m


def make_lanes(m: Client, n: int) -> list[str]:
    """Checkout lanes for a counter store: register-1 plus n-1 more counter
    tables. The app today has one register per tablet; extra lanes let N
    devices sell at once against the same store (a stress beyond the demo)."""
    lanes = ["register-1"]
    zones = m.call("GET", "/zones")
    counter = next((z for z in zones if z["id"] == "counter"), None)
    existing = [t["id"] for t in (counter or {}).get("tables", []) if t["id"] != "register-1"]
    lanes += existing
    m.call("POST", "/retail/sales")  # makes sure register-1 exists
    while len(lanes) < n:
        t = m.call("POST", "/zones/counter/tables",
                   {"x": 40 * len(lanes), "y": 40, "seats": 0, "managerPin": MANAGER})
        lanes.append(t.get("id") or t.get("tableId"))
    return lanes[:n]


# ------------------------------------------------------------------ restaurant

class Restaurant:
    kind = "restaurant"
    venue = "plateau"

    def __init__(self, base: str):
        self.base = base
        m = open_shift(base)
        # the kitchen: every station on the kitchen screen (no printers on this Mac)
        cfg = m.call("GET", "/kitchen/config")
        for s in cfg.get("stations", []):
            m.call("POST", "/kitchen/stations", {"id": s["id"], "nameFr": s["nameFr"], "nameEn": s["nameEn"],
                                                 "output": "screen"})
        zones = m.call("GET", "/zones")
        self.tables = [t["id"] for z in zones for t in z.get("tables", [])]
        items = m.call("GET", "/items")
        self.menu = [(i["id"], i["variants"][0]["id"]) for i in items if i.get("variants") and i.get("active", True)]
        m.close()

    def tables_for(self, i: int, n: int) -> list[str]:
        mine = self.tables[i::n]
        return mine or [self.tables[i % len(self.tables)]]

    def sale(self, c: Client, rng: random.Random, dev: dict) -> None:
        dev["k"] = dev.get("k", -1) + 1
        table = dev["tables"][dev["k"] % len(dev["tables"])]
        c.call("GET", "/zones", name="GET /zones")
        chk = c.call("POST", f"/tables/{table}/checks", {}, name="POST /tables/{id}/checks")
        cid = dev["cid"] = chk["id"]
        for item, variant in rng.sample(self.menu, rng.randint(3, 6)):
            chk = c.call("POST", f"/checks/{cid}/lines", {"itemId": item, "variantId": variant, "qty": rng.choice([1, 1, 2])},
                         name="POST /checks/{id}/lines")
        c.call("POST", f"/checks/{cid}/kitchen/send", name="POST /checks/{id}/kitchen/send")
        c.call("GET", f"/checks/{cid}", name="GET /checks/{id}")
        for item, variant in rng.sample(self.menu, rng.randint(1, 2)):
            chk = c.call("POST", f"/checks/{cid}/lines", {"itemId": item, "variantId": variant, "qty": 1},
                         name="POST /checks/{id}/lines")
        c.call("POST", f"/checks/{cid}/kitchen/send", name="POST /checks/{id}/kitchen/send")
        c.call("POST", f"/checks/{cid}/bill", name="POST /checks/{id}/bill")
        if rng.random() < 0.3:
            self.split_and_pay(c, chk, rng)
        else:
            pay(c, chk, rng)

    def split_and_pay(self, c: Client, chk: dict, rng: random.Random) -> None:
        cid = chk["id"]
        chk = c.call("POST", f"/checks/{cid}/split", {"groups": 2}, name="POST /checks/{id}/split")
        groups = [g["id"] for g in chk["split"]["groups"]]
        for n, line in enumerate(chk["lines"]):
            chk = c.call("POST", f"/checks/{cid}/split/groups/{groups[n % 2]}/lines",
                         {"lineId": line["id"], "qty": line["qty"]}, name="POST /checks/{id}/split/groups/{g}/lines")
        for g in chk["split"]["groups"]:
            due = g.get("cashDueCents") or g["outstandingCents"]
            if due <= 0:
                continue
            c.call("POST", f"/checks/{cid}/tenders",
                   {"type": "CASH", "amountTenderedCents": ((due // 2000) + 1) * 2000, "groupId": g["id"]},
                   name="POST /checks/{id}/tenders")
        c.call("POST", f"/checks/{cid}/finalize", name="POST /checks/{id}/finalize")

    def background(self, rec: Recorder, stop: threading.Event) -> list[threading.Thread]:
        """The kitchen screen: polls the board every 2 s and bumps what it shows."""
        def kds():
            c = Client(self.base, rec)
            while not stop.is_set():
                try:
                    if not c.token:
                        c.login(MANAGER)
                    board = c.call("GET", "/kitchen/board", name="GET /kitchen/board")
                    for card in (board.get("cards") or []):
                        if card.get("checkId") and card.get("stationId"):
                            c.call("POST", "/kitchen/board/bump",
                                   {"checkId": card["checkId"], "stationId": card["stationId"]},
                                   name="POST /kitchen/board/bump")
                except Exception:
                    pass
                stop.wait(2)
        return [threading.Thread(target=kds, daemon=True)]

    def device_state(self, i: int, n: int) -> dict:
        return {"tables": self.tables_for(i, n)}


# ------------------------------------------------------------------ retail

def search_words(items: list[dict]) -> list[str]:
    words = []
    for i in items[:400]:
        w = (i.get("nameEn") or "").split()
        if w:
            words.append(w[0][:5].lower())
    return words or ["ipa"]


class Retail:
    kind = "retail"
    venue = "sage-poppy"

    def __init__(self, base: str, lanes: int):
        self.base = base
        m = open_shift(base)
        items = m.call("GET", "/items")
        pool = [i for i in items if i.get("barcode") and i.get("active", True)]
        self.codes = [i["barcode"] for i in pool]
        self.weights = [max(int(i.get("salesWeight") or 0), 1) for i in pool]
        self.words = search_words(sorted(pool, key=lambda i: -(i.get("salesWeight") or 0)))
        self.lanes = make_lanes(m, lanes)
        m.close()

    def device_state(self, i: int, n: int) -> dict:
        return {"lane": self.lanes[i]}

    def open(self, c: Client, dev: dict) -> dict:
        lane = dev["lane"]
        if lane == "register-1":
            sale = c.call("POST", "/retail/sales", name="POST /retail/sales")
        else:
            sale = c.call("POST", f"/tables/{lane}/checks", {}, name="POST /tables/{id}/checks")
        dev["cid"] = sale["id"]
        return sale

    def scan_basket(self, c: Client, sale: dict, rng: random.Random, n: int) -> dict:
        for code in rng.choices(self.codes, weights=self.weights, k=n):
            sale = c.call("POST", f"/retail/sales/{sale['id']}/scan", {"barcode": code},
                          name="POST /retail/sales/{id}/scan")
        return sale

    def sale(self, c: Client, rng: random.Random, dev: dict) -> None:
        sale = self.open(c, dev)
        if rng.random() < 0.35:
            c.call("GET", f"/items?q={rng.choice(self.words)}&limit=20", name="GET /items?q=")
        sale = self.scan_basket(c, sale, rng, rng.choice([1, 1, 2, 2, 3, 3, 4, 5, 6, 8]))
        sale = age_check(c, sale)
        pay(c, sale, rng, card_share=0.55)

    def background(self, rec, stop):
        return []


# ------------------------------------------------------------------ gas

class Gas(Retail):
    kind = "gas"
    venue = "pronghorn"

    def __init__(self, base: str, lanes: int, fdc: str):
        super().__init__(base, lanes)
        self.fdc = fdc
        s = Client(fdc)
        s.call("POST", "/sim/v1/speed", {"multiplier": 20})
        s.close()
        m = Client(base)
        m.login(MANAGER)
        items = m.call("GET", "/items")
        self.coffee = next(((i["id"], i["variants"][0]["id"]) for i in items
                            if not i.get("barcode") and i.get("variants") and "coffee" in i["id"]), None)
        m.close()

    def device_state(self, i: int, n: int) -> dict:
        return {"lane": self.lanes[i], "pump": i + 1, "sim": Client(self.fdc)}

    def wait(self, fn, timeout=30.0, every=0.1):
        end = time.time() + timeout
        while time.time() < end:
            v = fn()
            if v:
                return v
            time.sleep(every)
        raise TimeoutError("forecourt wait timed out")

    def pump_view(self, c: Client, n: int) -> dict:
        return next(p for p in c.call("GET", "/forecourt", name="GET /forecourt")["pumps"] if p["pump"] == n)

    def fill(self, sim: Client, n: int, gallons: float) -> None:
        sim.call("POST", f"/sim/v1/pumps/{n}/lift", {"grade": random.choice(["REG", "REG", "MID", "PRE", "DSL"])})
        sim.call("POST", f"/sim/v1/pumps/{n}/trigger", {"on": True})
        target = int(gallons * 1000)

        def done():
            cur = sim.call("GET", f"/fdc/v1/pumps/{n}")["pump"].get("current") or {}
            return cur.get("limitReached") or cur.get("volumeMilli", 0) >= target
        self.wait(done, 60, 0.2)
        sim.call("POST", f"/sim/v1/pumps/{n}/trigger", {"on": False})
        sim.call("POST", f"/sim/v1/pumps/{n}/hangup")

    def shop(self, c: Client, sale: dict, rng: random.Random, n: int) -> dict:
        if n and self.coffee and rng.random() < 0.3:
            sale = c.call("POST", f"/checks/{sale['id']}/lines",
                          {"itemId": self.coffee[0], "variantId": self.coffee[1], "qty": 1},
                          name="POST /checks/{id}/lines")
            n -= 1
        return self.scan_basket(c, sale, rng, n) if n else sale

    def sale(self, c: Client, rng: random.Random, dev: dict) -> None:
        n, sim, r = dev["pump"], dev["sim"], rng.random()
        if r < 0.5:  # postpay: authorise, fill, pay inside (often with shop items)
            c.call("POST", f"/forecourt/pumps/{n}/authorise", name="POST /forecourt/pumps/{n}/authorise")
            self.fill(sim, n, rng.uniform(5, 14))
            trx = self.wait(lambda: next(iter(self.pump_view(c, n)["payable"]), None), 20, 0.3)
            sale = self.open(c, dev)
            sale = c.call("POST", f"/retail/sales/{sale['id']}/fuel", {"trxId": trx["trxId"]},
                          name="POST /retail/sales/{id}/fuel")
            sale = self.shop(c, sale, rng, rng.choice([0, 0, 1, 2, 3]))
            pay(c, age_check(c, sale), rng)
        elif r < 0.7:  # prepay: pay first, pump, the store gives back the change
            sale = self.open(c, dev)
            cents = rng.choice([2000, 3000, 4000])
            sale = c.call("POST", f"/retail/sales/{sale['id']}/prepay", {"pump": n, "amountCents": cents},
                          name="POST /retail/sales/{id}/prepay")
            sale = self.shop(c, sale, rng, rng.choice([0, 0, 1, 2]))
            pay(c, age_check(c, sale), rng)
            self.wait(lambda: (self.pump_view(c, n).get("prepay") or {}).get("status") == "AUTHORISED", 20, 0.3)
            self.fill(sim, n, rng.uniform(4, 16))
            ch = self.wait(lambda: self.pump_view(c, n).get("change")
                           or (self.pump_view(c, n).get("prepay") is None and {"refundCents": 0}), 20, 0.3)
            if ch.get("fuelSaleId"):
                c.call("POST", f"/forecourt/prepays/{ch['fuelSaleId']}/change-given",
                       name="POST /forecourt/prepays/{id}/change-given")
        else:  # the shop only
            sale = self.open(c, dev)
            sale = self.shop(c, sale, rng, rng.choice([1, 1, 2, 3, 4, 5]))
            pay(c, age_check(c, sale), rng)


# ------------------------------------------------------------------ the runner

def recover(c: Client, dev: dict, rec: Recorder) -> None:
    """A failed sale leaves its check open (or locked, or split): void it with
    the manager's PIN so the table or lane is free for the next sale. Not timed."""
    cid = dev.get("cid")
    if cid is None:
        return
    c.rec = None
    try:
        chk = c.call("GET", f"/checks/{cid}")
        if chk.get("status") in ("OPEN", "TOTAL_LOCKED") and chk.get("paidCents", 0) > 0 \
                and chk.get("outstandingCents", 1) <= 0:
            c.call("POST", f"/checks/{cid}/finalize")  # paid in full: the cashier taps Done again
        elif chk.get("status") in ("OPEN", "TOTAL_LOCKED"):
            c.call("POST", f"/checks/{cid}/void", {"reason": "load test: failed sale"})
    except Exception:
        pass
    finally:
        c.rec = rec


def staff_pins(base: str, n: int) -> list[str]:
    """One cashier per device (PINs 7001…), as on a real floor: a store caps
    the live sessions of one user, so 25 devices can't all share one login."""
    m = Client(base)
    m.login(MANAGER)
    have = {s.get("name"): s["id"] for s in m.call("GET", "/staff/manage")["staff"]}
    pins = []
    for i in range(n):
        name, pin = f"Load Cashier {i + 1}", str(7001 + i)
        sid = have.get(name) or m.call("POST", "/staff/manage", {"name": name, "role": "SERVER", "pin": pin})["id"]
        # the void grant, so clearing up after a failed sale needs no manager PIN
        m.call("PUT", f"/staff/manage/{sid}/grants", {"overrides": {"void": True}})
        pins.append(pin)
    m.close()
    return pins


def run_level(scn, n: int, seconds: float, pid: int, seed: int, pins: list[str]) -> dict:
    rec = Recorder()
    logins = Recorder()
    stop = threading.Event()
    sampler = Sampler(pid)
    clients = []
    for i in range(n):  # sign the devices in first: a login is not part of the steady state
        c = Client(scn.base, rec)
        c.rec = logins
        c.login(pins[i])
        c.rec = rec
        clients.append((c, scn.device_state(i, n)))

    def device(i: int):
        rng = random.Random(seed * 1000 + i)
        c, dev = clients[i]
        while not stop.is_set():
            t0 = time.perf_counter()
            try:
                scn.sale(c, rng, dev)
                rec.sale((time.perf_counter() - t0) * 1000)
            except Exception as e:  # recorded by the client; void what's left and keep selling
                if not isinstance(e, HttpError):
                    rec.record("flow", 0, False, repr(e))
                rec.count("(failed sales)")
                recover(c, dev, rec)
                time.sleep(0.2)
            dev.pop("cid", None)
        c.close()

    def shift_change():
        """Someone signs in every 3 s while the others sell: with the most staff
        to check, the slowest PIN there is. Timed on its own (not in "all")."""
        c = Client(scn.base, logins)
        while not stop.wait(3):
            try:
                c.call("POST", "/login", {"pin": pins[-1]}, name="POST /login (during load)")
            except Exception:
                pass
        c.close()

    threads = [threading.Thread(target=device, args=(i,), daemon=True) for i in range(n)]
    threads.append(threading.Thread(target=shift_change, daemon=True))
    for t in threads:
        t.start()
    bg = scn.background(rec, stop)
    for t in bg:
        t.start()
    sampler.start()
    t0 = time.time()
    rec.started = t0
    stop.wait(seconds)
    stop.set()
    duration = time.time() - t0
    for t in threads + bg:
        t.join(60)
    for c, _ in clients:
        c.close()
    out = rec.snapshot(duration) | {"devices": n} | sampler.stop()
    ls = logins.snapshot(1)["endpoints"]
    out["login"] = ls.get("POST /login", {})  # signing the devices in, before the clock starts
    out["login_during_load"] = ls.get("POST /login (during load)", {})
    return out


def db_stats(db: str) -> dict:
    con = sqlite3.connect(db)
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    q = lambda s: con.execute(s).fetchone()[0]
    stats = {
        "closed_sales": q("select count(*) from checks where status='CLOSED'"),
        "check_lines": q("select count(*) from check_lines"),
        "outbox_events": q("select count(*) from sync_outbox"),
        "db_bytes": os.path.getsize(db),
    }
    try:
        stats["outbox_bytes"] = q("select sum(pgsize) from dbstat where name like 'sync_outbox%'")
    except sqlite3.OperationalError:
        pass
    con.close()
    return stats


def run(kind: str, levels: list[int], seconds: float, warmup: float = 20) -> dict:
    port = PORTS[kind]
    data = fresh_dir(store_dir(kind))
    procs = []
    try:
        extra = {}
        if kind == "restaurant":
            extra["POS_KITCHEN_PRINTING"] = "on"
        if kind == "gas":
            fdc = start_simulator(PORTS["fdc"], max(8, max(levels)), data)
            procs.append(fdc)
            extra |= {"FORECOURT_URL": f"http://127.0.0.1:{PORTS['fdc']}", "FORECOURT_PUMPS": str(max(8, max(levels)))}
        log(f"{kind}: starting the store on :{port} (data in {os.path.relpath(data, WORK)})")
        store = start_store(kind, {"restaurant": "plateau", "retail": "sage-poppy", "gas": "pronghorn"}[kind],
                            port, data, extra)
        procs.append(store)
        base = f"http://127.0.0.1:{port}"
        if kind == "restaurant":
            scn = Restaurant(base)
        elif kind == "retail":
            scn = Retail(base, max(levels))
        else:
            scn = Gas(base, max(levels), f"http://127.0.0.1:{PORTS['fdc']}")
        result = {"kind": kind, "startup_s": store.extra["startup_s"], "levels": []}
        pins = staff_pins(base, max(levels))
        if warmup:
            log(f"{kind}: warm-up {warmup:.0f}s (JIT, caches; not counted)")
            run_level(scn, min(5, max(levels)), warmup, store.pid, seed=99, pins=pins)
        for n in levels:
            log(f"{kind}: {n} device(s) for {seconds:.0f}s …")
            r = run_level(scn, n, seconds, store.pid, seed=n, pins=pins)
            e2e = r["all"]
            log(f"{kind}: {n} devices → {r['sales_per_min']} sales/min, {r['requests_per_s']} req/s, "
                f"p50 {e2e['p50']} ms, p95 {e2e['p95']} ms, p99 {e2e['p99']} ms, errors {r['errors']}, failed sales {r['failed_sales']}, "
                f"CPU avg {r['cpu_avg_pct']}%, RSS max {r['rss_max_mb']} MB, "
                f"sign-in during load p50 {r['login_during_load'].get('p50')} ms")
            for sample in r["error_samples"][:3]:
                log(f"    error: {sample}")
            result["levels"].append(r)
        store.stop()
        procs.remove(store)
        result["db"] = db_stats(os.path.join(data, "pos.db"))
        s = result["db"]
        result["db"]["bytes_per_sale"] = round(s["db_bytes"] / max(1, s["closed_sales"]))
        log(f"{kind}: DB {s['db_bytes'] / 1e6:.1f} MB for {s['closed_sales']} sales "
            f"(~{result['db']['bytes_per_sale']} bytes/sale)")
        save_result(f"store-{kind}", result)
        return result
    finally:
        for p in procs:
            p.stop()
