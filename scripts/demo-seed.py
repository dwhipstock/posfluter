#!/usr/bin/env python3
"""
DEMO-ONLY seed: generate a handful of finalized sales through the store's HTTP API
so the owner portal has non-empty reports (payments, items, GST/QST tax, hourly) after sync
(~10s). Talks only to the store server — nothing here is used in production.

Idempotency is owned by the caller (scripts/demo-up.sh), which skips this when the
store volume already carries /data/.demo-seeded and re-runs it after --reset. Running
this file directly ALWAYS appends more demo sales.

  STORE_URL=http://localhost:8080 python3 scripts/demo-seed.py
"""
import json
import os
import sys
import urllib.request
import urllib.error

BASE = os.environ.get("STORE_URL", "http://localhost:8080").rstrip("/")
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
        raise SystemExit(f"Cannot reach store at {url}: {e.reason}\n"
                         f"Is the stack up? scripts/demo-up.sh")


def login():
    global TOKEN
    resp = call("POST", "/login", {"pin": MANAGER_PIN}, auth=False)
    TOKEN = resp["token"]
    print(f"  logged in as {resp.get('name', resp.get('userId'))} ({resp.get('role')})")


def ensure_shift():
    # Money movement needs an open shift. If one is already open, the store rejects a
    # second — tolerate that and carry on.
    try:
        call("POST", "/shifts", {"openingFloatCents": 100000, "managerPin": MANAGER_PIN})
        print("  opened a shift (float $1000)")
    except SystemExit as e:
        print(f"  shift: continuing (already open?) — {str(e).splitlines()[0]}")


def pick_tables(n):
    zones = call("GET", "/zones")
    picked = []
    for z in zones:                       # one free table per zone first, for spread
        for t in z.get("tables", []):
            if not t.get("openCheckId"):
                picked.append((t["id"], t.get("label", t["id"]), z.get("nameEn", z.get("id"))))
                break
        if len(picked) >= n:
            break
    if len(picked) < n:                   # top up from any free tables
        for z in zones:
            for t in z.get("tables", []):
                if not t.get("openCheckId") and all(t["id"] != p[0] for p in picked):
                    picked.append((t["id"], t.get("label", t["id"]), z.get("nameEn", z.get("id"))))
                    if len(picked) >= n:
                        break
            if len(picked) >= n:
                break
    return picked[:n]


def menu():
    items = call("GET", "/items")
    out = []
    for it in items:
        vs = it.get("variants", [])
        if vs:
            out.append((it["id"], vs[0]["id"], it.get("nameEn") or it.get("nameFr"), vs[0]["priceCents"]))
    return out


def cad(cents):
    return f"${cents/100:,.2f}"


# A few baskets (indices into the discovered menu list, wrapped to fit) + payment type.
BASKETS = [
    ([0, 3, 5], "CASH"),
    ([1, 4, 6, 2], "CASH"),
    ([7, 8, 9], "CARD"),
    ([2, 5, 10], "CASH"),
]


def make_sale(table, basket_idx, pay_type, menu_items):
    table_id, label, zone = table
    check = call("POST", f"/tables/{table_id}/checks", {})
    check_id = check["id"]
    added = []
    for idx in basket_idx:
        item_id, variant_id, name, price = menu_items[idx % len(menu_items)]
        check = call("POST", f"/checks/{check_id}/lines",
                     {"itemId": item_id, "variantId": variant_id, "qty": 1})
        added.append(name)
    total = check["grandTotalCents"]  # the store's total: pre-tax prices + GST + QST
    if pay_type == "CASH":
        tendered = ((total // 10000) + 1) * 10000        # round up to next $100 → shows change
        call("POST", f"/checks/{check_id}/tenders",
             {"type": "CASH", "amountTenderedCents": tendered})
    else:  # CARD: initiate, then confirm once the terminal approves
        call("POST", f"/checks/{check_id}/tenders/initiate",
             {"type": "CARD", "amountCents": total})
        call("POST", f"/checks/{check_id}/tenders/confirm",
             {"type": "CARD", "amountCents": total})
    final = call("POST", f"/checks/{check_id}/finalize", {})
    print(f"  {label} ({zone}): {len(added)} items, {cad(total)} {pay_type} → {final.get('status')}")
    return total


def main():
    print(f"Seeding demo sales against {BASE} …")
    login()
    ensure_shift()
    menu_items = menu()
    if len(menu_items) < 4:
        raise SystemExit("Menu looks unseeded (<4 items). Give the store a moment to seed, then retry.")
    tables = pick_tables(len(BASKETS))
    if len(tables) < len(BASKETS):
        print(f"  note: only {len(tables)} free tables found; seeding that many sales.")
    grand = 0
    for i, table in enumerate(tables):
        basket_idx, pay_type = BASKETS[i % len(BASKETS)]
        grand += make_sale(table, basket_idx, pay_type, menu_items)
    print(f"Done. {len(tables)} sales, gross {cad(grand)}. "
          f"They appear in the portal after the next sync tick (~10s).")


if __name__ == "__main__":
    main()
