#!/usr/bin/env python3
"""
DEMO-ONLY seed for the retail store (Sage & Poppy Bottle Shop): ring up a few
counter sales through the store's HTTP API — scan barcodes, check an ID where
the basket holds alcohol, pay cash or card — so the portal shows USD sales,
CRV and sales tax after sync (~10s). Talks only to the store server.

Idempotency is the caller's (scripts/demo-up.sh skips it once
.demo/sage-poppy/.demo-seeded exists). Running this directly always appends.

  STORE_URL=http://localhost:8082 python3 scripts/demo-seed-retail.py
"""
import datetime
import json
import os
import urllib.error
import urllib.request

BASE = os.environ.get("STORE_URL", "http://localhost:8082").rstrip("/")
MANAGER_PIN = os.environ.get("DEMO_MANAGER_PIN", "1234")
TOKEN = None


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
    resp = call("POST", "/login", {"pin": pin}, auth=False)
    TOKEN = resp["token"]
    return resp


def usd(cents):
    return f"${cents / 100:,.2f}"


# baskets of product ids (resolved to barcodes from /items) + payment type
BASKETS = [
    (["golden-lager-6", "chips-sea-salt", "ice-7"], "CASH"),
    (["coastal-cab", "valley-chard"], "CARD"),
    (["club-soda", "tonic", "lime-juice", "ice-20"], "CASH"),
    (["wave-variety-12", "tortilla-chips"], "CARD"),
    (["agave-blanco", "marg-mix", "ice-7"], "CASH"),
]


def weighted_basket(items, rng, n):
    """n distinct scannable products drawn by the catalog's sales weight (a
    1/rank long tail: about 20% of the ~5,000 products make about 80% of the
    sales). Stores without weights (an older build) fall back to uniform.
    Reused by scripts/demo-reset-helper.py."""
    pool = [i for i in items.values() if i.get("barcode") and i.get("active", True)]
    if not pool:
        return []
    weights = [max(int(i.get("salesWeight") or 0), 0) for i in pool]
    if not any(weights):
        weights = [1] * len(pool)
    picked = []
    for _ in range(n * 4):
        if len(picked) == min(n, len(pool)):
            break
        pid = rng.choices(pool, weights=weights, k=1)[0]["id"]
        if pid not in picked:
            picked.append(pid)
    return picked


def ring_sale(items, basket, pay, dob=None):
    """Ring up one counter sale: scan each product id's barcode, check an ID when
    the basket needs it, take cash (next $20 up) or card, finalize. Returns
    (sale id, grand total cents, status). Reused by scripts/demo-reset-helper.py."""
    sale = call("POST", "/retail/sales")
    for pid in basket:
        code = items[pid].get("barcode")
        sale = call("POST", f"/retail/sales/{sale['id']}/scan", {"barcode": code})
    if sale.get("ageCheckRequired") and not sale.get("ageCleared"):
        dob = dob or (datetime.date.today() - datetime.timedelta(days=365 * 34)).isoformat()
        call("POST", f"/retail/sales/{sale['id']}/age-check",
             {"method": "MANUAL", "dateOfBirth": dob, "cashierSawId": True})
    total = sale["grandTotalCents"]
    if pay == "CASH":
        tendered = ((total // 2000) + 1) * 2000  # next $20 up → shows change
        call("POST", f"/checks/{sale['id']}/tenders", {"type": "CASH", "amountTenderedCents": tendered})
    else:
        call("POST", f"/checks/{sale['id']}/tenders/initiate", {"type": "CARD", "amountCents": total})
        call("POST", f"/checks/{sale['id']}/tenders/confirm", {"type": "CARD", "amountCents": total})
    final = call("POST", f"/checks/{sale['id']}/finalize", {})
    return sale["id"], total, final.get("status")


def main():
    print(f"Seeding retail demo sales against {BASE} …")
    login(MANAGER_PIN)
    try:
        call("POST", "/shifts", {"openingFloatCents": 20000, "managerPin": MANAGER_PIN})
        print("  opened the register ($200 float)")
    except SystemExit as e:
        print(f"  register: continuing (already open?) — {str(e).splitlines()[0]}")
    items = {i["id"]: i for i in call("GET", "/items")}
    grand = 0
    for n, (basket, pay) in enumerate(BASKETS):
        # the third sale is rung up by the Spanish-speaking cashier: a Spanish receipt
        if n == 2:
            login("5555")
        elif n == 3:
            login(MANAGER_PIN)
        sale_id, total, status = ring_sale(items, basket, pay)
        grand += total
        print(f"  sale #{sale_id}: {len(basket)} products, {usd(total)} {pay} → {status}")
    print(f"Done. {len(BASKETS)} sales, gross {usd(grand)} USD. In the portal after the next sync tick.")


if __name__ == "__main__":
    main()
