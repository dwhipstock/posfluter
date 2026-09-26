#!/usr/bin/env python3
"""
DEMO-ONLY stock history for the retail store (Sage & Poppy Bottle Shop), so
the portal's Stock page looks alive: two deliveries received at the back
door, a few more sales, a by-line refund (it puts a six-pack back), and a
shelf count with a couple of variances, submitted by the cashier and approved
with the manager's PIN. Everything goes through the store's own HTTP API —
the same calls the stock app makes — and reaches the portal on the next sync.

Run after demo-seed-retail.py (it opens the register). Idempotency is the
caller's (scripts/demo-up.sh skips it once .demo/sage-poppy/.demo-stock-seeded
exists); the delivery and count ids are fixed, so a re-run re-sends the same
ones (the store answers them as they are) but rings up new sales.

  STORE_URL=http://localhost:8082 python3 scripts/demo-seed-stock.py
"""
import datetime
import json
import os
import urllib.error
import urllib.request

BASE = os.environ.get("STORE_URL", "http://localhost:8082").rstrip("/")
MANAGER_PIN = os.environ.get("DEMO_MANAGER_PIN", "1234")
CASHIER_PIN = os.environ.get("DEMO_CASHIER_PIN", "9999")
TOKEN = None
COUNTER = "demo-seed-counter-1"


def call(method, path, body=None, auth=True):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if auth and TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        raise SystemExit(f"HTTP {e.code} on {method} {path}: {detail}")
    except urllib.error.URLError as e:
        raise SystemExit(f"Cannot reach the store at {url}: {e.reason}")


def login(pin):
    global TOKEN
    TOKEN = call("POST", "/login", {"pin": pin}, auth=False)["token"]


DELIVERIES = [
    ("0d6b3c1e-5eed-4000-8000-00000000d001", "Valley Beverage Co.", "INV-2041", {
        "golden-lager-6": 24, "coastal-cab": 12, "valley-chard": 12, "club-soda": 24,
        "tonic": 24, "ice-7": 40, "chips-sea-salt": 20,
    }),
    ("0d6b3c1e-5eed-4000-8000-00000000d002", "Sunset Spirits", "SS-7713", {
        "agave-blanco": 6, "marg-mix": 6, "wave-variety-12": 10, "tortilla-chips": 12,
        "ice-20": 10, "lime-juice": 12,
    }),
]

MORE_SALES = [
    ["golden-lager-6", "golden-lager-6", "ice-7"],
    ["club-soda", "tonic", "tonic", "lime-juice"],
    ["coastal-cab", "chips-sea-salt"],
]

# the shelf count: most products match, two don't (one six-pack and two ice bags short)
COUNT_ID = "0d6b3c1e-5eed-4000-8000-00000000c001"
COUNT_SHORT = {"golden-lager-6": 1, "ice-7": 2}


def retail_seed_baskets():
    """The five sales demo-seed-retail.py rang up (it runs first)."""
    try:
        import importlib.util
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "demo-seed-retail.py")
        spec = importlib.util.spec_from_file_location("demo_seed_retail", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return [basket for basket, _ in mod.BASKETS]
    except Exception:
        return []


def sell(items, basket):
    sale = call("POST", "/retail/sales")
    for pid in basket:
        sale = call("POST", f"/retail/sales/{sale['id']}/scan", {"barcode": items[pid]["barcode"]})
    if sale.get("ageCheckRequired") and not sale.get("ageCleared"):
        dob = (datetime.date.today() - datetime.timedelta(days=365 * 41)).isoformat()
        call("POST", f"/retail/sales/{sale['id']}/age-check",
             {"method": "MANUAL", "dateOfBirth": dob, "cashierSawId": True})
    tendered = ((sale["grandTotalCents"] // 2000) + 1) * 2000
    call("POST", f"/checks/{sale['id']}/tenders", {"type": "CASH", "amountTenderedCents": tendered})
    return call("POST", f"/checks/{sale['id']}/finalize", {})


def main():
    print(f"Seeding retail stock history against {BASE} …")
    login(MANAGER_PIN)
    try:
        call("POST", "/shifts", {"openingFloatCents": 20000, "managerPin": MANAGER_PIN})
    except SystemExit:
        pass  # already open
    items = {i["id"]: i for i in call("GET", "/items")}

    login(CASHIER_PIN)
    for rid, supplier, ref, lines in DELIVERIES:
        r = call("POST", "/stock/receipts", {
            "id": rid, "supplier": supplier, "reference": ref,
            "lines": [{"itemId": k, "qty": v} for k, v in lines.items() if k in items],
        })
        print(f"  delivery {ref} from {supplier}: {r['units']} units")

    # what demo-seed-retail.py sold before the deliveries counts too
    sold = {}
    for basket in retail_seed_baskets():
        for pid in basket:
            sold[pid] = sold.get(pid, 0) + 1
    for basket in MORE_SALES:
        final = sell(items, basket)
        for pid in basket:
            sold[pid] = sold.get(pid, 0) + 1
        print(f"  sale #{final['id']}: {len(basket)} products")

    # a returned, unopened six-pack: a by-line refund puts it back on the shelf
    login(MANAGER_PIN)
    sale = sell(items, ["golden-lager-6", "golden-lager-6"])
    line = next(l for l in sale["lines"] if l["itemId"] == "golden-lager-6")
    call("POST", f"/checks/{sale['id']}/refund", {
        "lines": [{"lineId": line["id"], "qty": 1}], "tenderType": "CASH",
        "reason": "Returned unopened", "managerPin": MANAGER_PIN,
    })
    sold["golden-lager-6"] = sold.get("golden-lager-6", 0) + 1  # 2 sold, 1 back
    print(f"  sale #{sale['id']} and a by-line refund of one six-pack")

    # the shelf count, by the cashier; the variances need the manager's PIN
    login(CASHIER_PIN)
    delivered = {}
    for _, _, _, lines in DELIVERIES:
        for k, v in lines.items():
            delivered[k] = delivered.get(k, 0) + v
    call("POST", "/stock/counts", {"id": COUNT_ID, "name": "Friday shelf count"})
    counted = {pid: max(0, qty - sold.get(pid, 0) - COUNT_SHORT.get(pid, 0))
               for pid, qty in delivered.items() if pid in items}
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    call("PUT", f"/stock/counts/{COUNT_ID}/lines", {
        "counterId": COUNTER,
        "lines": [{"itemId": k, "qty": v, "countedAt": now} for k, v in counted.items()],
    })
    view = call("POST", f"/stock/counts/{COUNT_ID}/submit", {"managerPin": MANAGER_PIN})
    print(f"  count '{view['name']}': {len(view['lines'])} products, {view['status'].lower()}")
    print("Done. On the portal's Stock page after the next sync tick "
          "(Counts: two products short; Deliveries: two; Reorder: from the sales).")


if __name__ == "__main__":
    main()
