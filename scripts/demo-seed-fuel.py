#!/usr/bin/env python3
"""
DEMO-ONLY seed for the gas station (Pronghorn Fuel & Market): a handful of
real forecourt sales through the store's HTTP API, with the forecourt
simulator playing the customers at the pumps —

  - postpay: the counter authorises a pump, the "customer" fills up at the
    simulator, and the fuel goes on a sale with a few shop items;
  - prepay with change: $40 on a pump, the tank fills at $31-ish, the store
    refunds the rest on the sale;
  - prepay to the limit: $25, the pump stops at exactly $25;
  - shop-only sales (snacks, drinks, a beer with an ID check).

The portal then shows fuel by grade (gallons, $) next to in-store sales after
sync (~10s). Talks to the store and, for the fuel, to the simulator; with no
simulator it rings up the shop sales only.

  STORE_URL=http://localhost:8084 FDC_URL=http://localhost:8086 python3 scripts/demo-seed-fuel.py

Idempotency is the caller's (the demo scripts skip it once seeded). Running
this directly always appends.
"""
import datetime
import json
import os
import time
import urllib.error
import urllib.request

BASE = os.environ.get("STORE_URL", "http://localhost:8084").rstrip("/")
FDC = os.environ.get("FDC_URL", "http://localhost:8086").rstrip("/")
MANAGER_PIN = os.environ.get("DEMO_MANAGER_PIN", "1234")
TOKEN = None


def _req(url, method, body=None, token=None, timeout=15):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw else {}


def call(method, path, body=None, auth=True):
    try:
        return _req(BASE + path, method, body, TOKEN if auth else None)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} on {method} {path}: {e.read().decode(errors='replace')}")
    except urllib.error.URLError as e:
        raise SystemExit(f"Cannot reach the store at {BASE}: {e.reason}")


def sim(method, path, body=None):
    """The simulator's own API: the customer at the pump."""
    return _req(FDC + path, method, body if body is not None else {}, timeout=5)


def fdc_up():
    try:
        return _req(FDC + "/healthz", "GET", timeout=2).get("ok", False)
    except Exception:
        return False


def login(pin=MANAGER_PIN):
    global TOKEN
    TOKEN = call("POST", "/login", {"pin": pin}, auth=False)["token"]


def usd(cents):
    return f"${cents / 100:,.2f}"


def ensure_shift():
    try:
        call("POST", "/shifts", {"openingFloatCents": 20000, "managerPin": MANAGER_PIN})
    except SystemExit as e:
        if "409" not in str(e):
            raise


def items_by_id():
    items = call("GET", "/items")
    return {i["id"]: i for i in items}


def pick(items, test, n=1):
    """The n best-selling shelf products passing test (by the catalog's weight)."""
    pool = [i for i in items.values() if i.get("barcode") and i.get("active", True) and test(i)]
    pool.sort(key=lambda i: -(i.get("salesWeight") or 0))
    return [i["id"] for i in pool[:n]]


def forecourt():
    return call("GET", "/forecourt")


def wait_for(pred, timeout=20.0, what="the forecourt"):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(0.25)
    raise SystemExit(f"timed out waiting for {what}")


def pump_view(n):
    return next(p for p in forecourt()["pumps"] if p["pump"] == n)


def fill(n, grade, gallons, hang_up=True):
    """The customer: lift the grade's nozzle, pump until [gallons] (or the
    prepay limit stops the pump), hang up."""
    sim("POST", f"/sim/v1/pumps/{n}/lift", {"grade": grade})
    sim("POST", f"/sim/v1/pumps/{n}/trigger", {"on": True})
    target = int(gallons * 1000)

    def done():
        p = sim("GET", f"/fdc/v1/pumps/{n}")["pump"]
        cur = p.get("current") or {}
        return cur.get("limitReached") or cur.get("volumeMilli", 0) >= target
    wait_for(done, timeout=60, what=f"pump {n} to pump {gallons} gal")
    sim("POST", f"/sim/v1/pumps/{n}/trigger", {"on": False})
    if hang_up:
        sim("POST", f"/sim/v1/pumps/{n}/hangup")


def scan_all(sale_id, items, ids):
    """Ring up shop items: a barcode is scanned; the counter's own (coffee, a
    hot dog) are added by size, as "id" or "id|size|flavour"."""
    sale = None
    for entry in ids:
        pid, _, rest = entry.partition("|")
        size, _, flavour = rest.partition("|")
        item = items[pid]
        if item.get("barcode"):
            sale = call("POST", f"/retail/sales/{sale_id}/scan", {"barcode": item["barcode"]})
        else:
            variant = next((v for v in item["variants"] if not size or v["labelEn"] == size), item["variants"][0])
            body = {"itemId": pid, "variantId": variant["id"], "qty": 1}
            if flavour:
                body["note"] = flavour
            sale = call("POST", f"/checks/{sale_id}/lines", body)
    return sale


def pay(sale, method="CASH"):
    if sale.get("ageCheckRequired") and not sale.get("ageCleared"):
        dob = (datetime.date.today() - datetime.timedelta(days=365 * 34)).isoformat()
        sale = call("POST", f"/retail/sales/{sale['id']}/age-check",
                    {"method": "MANUAL", "dateOfBirth": dob, "cashierSawId": True})["check"]
    total = sale["grandTotalCents"]
    if method == "CASH":
        tendered = ((total // 2000) + 1) * 2000
        call("POST", f"/checks/{sale['id']}/tenders", {"type": "CASH", "amountTenderedCents": tendered})
    else:
        call("POST", f"/checks/{sale['id']}/tenders/initiate", {"type": "CARD", "amountCents": total})
        call("POST", f"/checks/{sale['id']}/tenders/confirm", {"type": "CARD", "amountCents": total})
    call("POST", f"/checks/{sale['id']}/finalize")
    return total


def postpay(n, grade, gallons, items, extras, method="CASH"):
    """Authorise from the counter, fill up, pay inside with some shop items."""
    call("POST", f"/forecourt/pumps/{n}/authorise")
    fill(n, grade, gallons)
    trx = wait_for(lambda: next(iter(pump_view(n)["payable"]), None), what=f"pump {n}'s sale")
    sale = call("POST", "/retail/sales")
    sale = call("POST", f"/retail/sales/{sale['id']}/fuel", {"trxId": trx["trxId"]})
    if extras:
        sale = scan_all(sale["id"], items, extras)
    total = pay(sale, method)
    print(f"  postpay pump {n}: {trx['volumeMilli'] / 1000:.3f} gal {grade} {usd(trx['amountCents'])}"
          f" + {len(extras)} shop items = {usd(total)}")


def prepay(n, grade, cents, gallons, items, extras, method="CASH"):
    """Pay first, then pump; the store refunds whatever isn't pumped."""
    sale = call("POST", "/retail/sales")
    sale = call("POST", f"/retail/sales/{sale['id']}/prepay", {"pump": n, "amountCents": cents})
    if extras:
        sale = scan_all(sale["id"], items, extras)
    pay(sale, method)
    wait_for(lambda: (pump_view(n).get("prepay") or {}).get("status") == "AUTHORISED", what=f"pump {n} authorised")
    fill(n, grade, gallons)
    ch = wait_for(lambda: pump_view(n).get("change") or (pump_view(n).get("prepay") is None and {"refundCents": 0}),
                  what=f"pump {n} to settle")
    if ch.get("fuelSaleId"):
        call("POST", f"/forecourt/prepays/{ch['fuelSaleId']}/change-given")
    print(f"  prepay pump {n}: {usd(cents)} → change {usd(ch.get('refundCents', 0))}")


def main():
    login()
    ensure_shift()
    items = items_by_id()
    soda = pick(items, lambda i: i.get("subcategory") == "Soda", 3)
    energy = pick(items, lambda i: i.get("subcategory") == "Energy" and i.get("size") == "16 oz can", 4)
    chips = pick(items, lambda i: i.get("subcategory") == "Chips", 2)
    candy = pick(items, lambda i: i.get("category") == "candy", 2)
    water = pick(items, lambda i: i.get("subcategory") == "Water", 1)
    beer = pick(items, lambda i: i.get("category") == "beer" and i.get("ageRestricted"), 2)
    cigs = pick(items, lambda i: i.get("subcategory") == "Cigarettes" and i.get("size") == "Pack", 2)
    oil = pick(items, lambda i: i.get("subcategory") == "Motor Oil", 1)
    jerky = pick(items, lambda i: i.get("subcategory") == "Jerky & Meat Snacks", 1)
    ice = pick(items, lambda i: i.get("category") == "ice", 1)
    coffee = "ph-coffee|Medium 16 oz|House Blend"
    fountain = "ph-fountain-drink|Large 44 oz|Cola"
    slush = "ph-frozen-slush|Medium 32 oz|Blue Raspberry"

    # a gas station's day: most sales have fuel on them, and plenty of those
    # also buy from the shop (where the margin is) — coffee with the fuel
    # deal, the hot dog combo, 2 for $5 energy drinks, a pack of cigarettes
    if fdc_up():
        sim("POST", "/sim/v1/speed", {"multiplier": 20})
        print("Forecourt sales (simulator at " + FDC + "):")
        postpay(2, "REG", 11.2, items, [coffee, "ph-kolache-sausage-cheese"])
        postpay(5, "PRE", 13.5, items, energy[:2], method="CARD")
        prepay(3, "REG", 4000, 11.0, items, ["ph-hot-dog", fountain])
        postpay(1, "MID", 9.3, items, cigs[:1] + candy[:1])
        prepay(7, "MID", 2500, 20.0, items, [])
        postpay(8, "DSL", 18.4, items, oil + water + jerky + [coffee])
        postpay(4, "REG", 8.7, items, [slush] + chips[:1], method="CARD")
        postpay(6, "REG", 12.1, items, [])
        prepay(2, "PRE", 3000, 9.0, items, energy[2:4] + [coffee], method="CARD")
        postpay(3, "MID", 10.4, items, beer[:1] + chips[1:] + ice)
        sim("POST", "/sim/v1/speed", {"multiplier": 1})
    else:
        print(f"No forecourt simulator at {FDC}: shop sales only.")
    print("Shop sales:")
    for basket, method in [(["ph-hot-dog", "ph-fountain-drink|Medium 32 oz|Root Beer"] + chips[:1], "CASH"),
                           (beer + ice, "CARD"),
                           (cigs[1:] + energy[:2], "CASH"),
                           ([coffee, "ph-breakfast-sandwich-sausage-egg-cheese"] + water, "CARD")]:
        sale = call("POST", "/retail/sales")
        sale = scan_all(sale["id"], items, basket)
        print(f"  {usd(pay(sale, method))}")


if __name__ == "__main__":
    main()
