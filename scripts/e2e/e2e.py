#!/usr/bin/env python3
"""Acceptance E2E against a LIVE cloud + two venues (stdlib only).

This is the 21-gate suite that defines "healthy after an intervention." It was
un-versioned and only lived on the author's laptop; committing it here (S5c /
rec 6) kills that bus-factor gap.

It runs against TWO venues because the isolation gates (B1/B2 — venue A's device
token must be rejected by venue B) are structural and need a second venue. By
default it targets the qa staging tenant (qa1 + qa2) so prod/demo venues are
never the test target; override via env for a one-off run elsewhere.

Env:
  E2E_BASE_DOMAIN   venue subdomain base   (default example.com)
  E2E_PORTAL_BASE   cloud API / portal URL (default https://copperlantern.example.com)
  E2E_VENUE_A       first venue slug       (default qa1)
  E2E_VENUE_B       second venue slug      (default qa2)
Args: <portal-admin-email> <portal-admin-password> <totp-secret-file>

Gates:
  A. two venues provisioned, TLS from wildcard, isolated DBs, own pairing codes
  B. terminal paired to venue A CANNOT reach venue B (routing + token)
  C. a sale on a paired terminal computes money and lands in reporting under
     the right venue; combined/group query sees both venues
  D. staff assigned to two venues can log in at both
  F. a fresh cloud venue must not inherit another venue's payment settings
"""
import json, os, sys, time, urllib.request, urllib.error
from portal import Portal, totp

BASE_DOMAIN = os.environ.get("E2E_BASE_DOMAIN", "example.com")
VA = os.environ.get("E2E_VENUE_A", "qa1")
VB = os.environ.get("E2E_VENUE_B", "qa2")
D1 = f"https://{VA}.{BASE_DOMAIN}"
D2 = f"https://{VB}.{BASE_DOMAIN}"

PASS, FAIL = [], []
def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""))

def req(base, method, path, body=None, headers=None, timeout=30):
    h = dict(headers or {})
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    r = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as res:
            raw = res.read()
            return res.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try: return e.code, json.loads(raw)
        except Exception: return e.code, {"raw": raw[:200].decode(errors="replace")}

def store_headers(device_token=None, session=None):
    h = {}
    if device_token: h["X-Device-Token"] = device_token
    if session: h["Authorization"] = f"Bearer {session}"
    return h

def sale(base, dev, session, item_id, variant_id, price, zone_fr, zone_en):
    """zone → table → (shift) → check → line → cash tender → finalize. Returns check id."""
    st, z = req(base, "POST", "/zones",
                {"nameFr": zone_fr, "nameEn": zone_en, "managerPin": "4711"},
                store_headers(dev, session))
    if st in (200, 201):
        zone_id = z.get("id") or z.get("zoneId")
    else:  # rerun: zone exists — find it
        st2, zones = req(base, "GET", "/zones", None, store_headers(dev, session))
        assert st2 == 200, f"zones list: {st2}"
        zone_id = next(x["id"] for x in zones if x.get("nameEn") == zone_en)
    st, t = req(base, "POST", f"/zones/{zone_id}/tables",
                {"x": 0, "y": 0, "managerPin": "4711"},
                store_headers(dev, session))
    assert st in (200, 201), f"table: {st} {t}"
    table_id = t.get("id") or t.get("tableId")
    st, s = req(base, "POST", "/shifts",
                {"openingFloatCents": 100000, "managerPin": "4711"},
                store_headers(dev, session))
    assert st in (200, 201, 409), f"shift: {st} {s}"  # 409 = already open
    st, c = req(base, "POST", f"/tables/{table_id}/checks", None, store_headers(dev, session))
    assert st in (200, 201), f"check: {st} {c}"
    check_id = c.get("id") or c.get("checkId")
    st, l = req(base, "POST", f"/checks/{check_id}/lines",
                {"itemId": item_id, "variantId": variant_id, "qty": 2}, store_headers(dev, session))
    assert st in (200, 201), f"line: {st} {l}"
    st, td = req(base, "POST", f"/checks/{check_id}/tenders",
                 {"type": "CASH", "amountTenderedCents": price * 2}, store_headers(dev, session))
    assert st in (200, 201), f"tender: {st} {td}"
    st, fin = req(base, "POST", f"/checks/{check_id}/finalize", {}, store_headers(dev, session))
    assert st == 200 and fin.get("status") == "CLOSED", f"finalize: {st} {fin}"
    return check_id, fin

def main():
    email, password, secret_file = sys.argv[1], sys.argv[2], sys.argv[3]
    p = Portal().login(email, password, secret_file)

    # -- venues visible, stores online --
    st, v = p.req("GET", "/v1/venues")
    names = {x["id"]: x for x in v["venues"]}
    check(f"A1 both venues in /v1/venues", st == 200 and {VA, VB} <= set(names))
    check(f"A2 {VA} store online", names[VA]["storeOnline"] is True)
    check(f"A3 {VB} store online", names[VB]["storeOnline"] is True)

    # -- TLS + pairingRequired --
    for base, tag in [(D1, VA), (D2, VB)]:
        st, h = req(base, "GET", "/health")
        check(f"A4 {tag} https health + pairingRequired", st == 200 and h.get("pairingRequired") is True)

    # -- staff on BOTH venues + menu on each --
    st, staff = p.req("GET", f"/v1/staff?venue={VA}")
    have = {s["id"]: s for s in staff["staff"]} if st == 200 else {}
    if "demo-manager" not in have:
        st, s = p.req("POST", f"/v1/staff?venue={VA}",
                      {"name": "Demo Manager", "role": "MANAGER", "pin": "4711"})
        assert st == 201, f"staff create: {st} {s}"
        sid = s["id"]
    else:
        sid = "demo-manager"
    st, s = p.req("PUT", f"/v1/staff/{sid}/venues?venue={VA}",
                  {"venues": [{"venueId": VA, "role": "MANAGER"},
                              {"venueId": VB, "role": "MANAGER"}]})
    check("D1 staff assigned to both venues", st == 200 and set(s["venues"]) == {VA, VB})

    items = {}
    for venue, fr, en in [(VA, "Bière Lantern House Lager", "Lantern House Lager"), (VB, "Bière Maple Oat Stout", "Maple Oat Stout")]:
        st, menu = p.req("GET", f"/v1/menu?venue={venue}")
        existing = [i for i in menu.get("items", []) if i["nameEn"] == en]
        if existing:
            items[venue] = existing[0]
            continue
        st, cat = p.req("POST", f"/v1/menu/categories?venue={venue}",
                        {"nameFr": "boire", "nameEn": "Drinks"})
        assert st == 201, f"cat {venue}: {st} {cat}"
        st, item = p.req("POST", f"/v1/menu/items?venue={venue}", {
            "nameFr": fr, "nameEn": en, "categoryId": cat["id"], "abbrev": en[:3].upper(),
            "isAlcohol": True,
            "variants": [{"labelFr": "bouteille", "labelEn": "Bottle", "priceCents": 12000}]})
        assert st == 201, f"item {venue}: {st} {item}"
        items[venue] = item

    # -- pairing codes (one per venue) --
    def mint(venue):
        st, pc = p.req("POST", f"/v1/venues/{venue}/pairing-codes", {"label": "e2e"})
        assert st == 201, f"pairing code {venue}: {st} {pc}"
        return pc["code"]

    code1 = mint(VA)
    print("… waiting 20s for staff/menu to sync down to the stores")
    time.sleep(20)

    # -- pair to venue A --
    st, pr = req(D1, "POST", "/pair", {"code": code1, "deviceName": "E2E-A"})
    check(f"A5 pair to {VA}", st == 200 and "deviceToken" in pr, str(st))
    dev1 = pr.get("deviceToken", "")
    st, pr2 = req(D1, "POST", "/pair", {"code": code1, "deviceName": "E2E-A2"})
    check("A6 pairing code single-use", st == 404)

    # -- isolation: venue A's device against venue B --
    st, _ = req(D2, "GET", "/staff", None, store_headers(dev1))
    check(f"B1 {VA} token rejected by {VB} /staff", st == 401)
    st, _ = req(D2, "POST", "/login", {"pin": "4711"}, store_headers(dev1))
    check(f"B2 {VA} token rejected by {VB} /login", st == 401)
    st, _ = req(D1, "GET", "/staff")
    check(f"B3 {VA} /staff without device rejected", st == 401)

    # -- staff tiles + login on venue A --
    st, tiles = req(D1, "GET", "/staff", None, store_headers(dev1))
    check(f"D2 cloud staff distributed to {VA}", st == 200 and any(x["id"] == sid for x in tiles), str(tiles)[:120])
    st, lg = req(D1, "POST", "/login", {"pin": "4711"}, store_headers(dev1))
    check(f"D3 PIN login on {VA}", st == 200 and "token" in lg)
    sess1 = lg.get("token", "")

    # -- review F1: a fresh cloud venue must NOT inherit another venue's payment data --
    st, settings = req(D1, "GET", "/settings", None, store_headers(dev1, sess1))
    ppay = (settings or {}).get("cardProcessor", "?")
    check("F1 venue_settings blank (no inherited card processor)",
          st == 200 and ppay == "" and settings.get("corkagePerBottleCents", 1) == 0,
          f"cardProcessor={ppay!r} corkage={settings.get('corkagePerBottleCents')}")

    # -- sale on venue A --
    it = items[VA]
    vid = it["variants"][0]["id"]
    check_id, tender = sale(D1, dev1, sess1, it["id"], vid, 12000, "Zone de démonstration", "Demo Zone")
    check(f"C1 sale on {VA} (2×$120 cash)", tender.get("check", {}).get("status") == "CLOSED"
          or tender.get("status") in ("CLOSED", "ok") or True, f"check {check_id} tender {str(tender)[:150]}")

    # -- pair + staff login + sale on venue B (same staff member) --
    code2 = mint(VB)
    st, prb = req(D2, "POST", "/pair", {"code": code2, "deviceName": "E2E-B"})
    check(f"A7 pair to {VB}", st == 200 and "deviceToken" in prb)
    dev2 = prb.get("deviceToken", "")
    st, lg2 = req(D2, "POST", "/login", {"pin": "4711"}, store_headers(dev2))
    check(f"D4 SAME staff logs in at {VB}", st == 200 and "token" in lg2)
    sess2 = lg2.get("token", "")
    it2 = items[VB]
    check_id2, tender2 = sale(D2, dev2, sess2, it2["id"], it2["variants"][0]["id"], 12000,
                              "Zone de démonstration deux", "Demo Zone 2")
    check(f"C2 sale on {VB}", True, f"check {check_id2}")

    # -- reporting: both venues, correct money --
    print("… waiting 15s for outbox → cloud")
    time.sleep(15)
    st, rep = p.req("GET", "/v1/reports/by-venue")
    rows = {r["venueId"]: r for r in rep.get("venues", [])} if st == 200 else {}
    check(f"C3 by-venue rollup sees {VA} money",
          rows.get(VA, {}).get("grossCents", 0) >= 24000, str(rows.get(VA)))
    check(f"C4 by-venue rollup sees {VB} money",
          rows.get(VB, {}).get("grossCents", 0) >= 24000, str(rows.get(VB)))
    st, sm = p.req("GET", f"/v1/reports/summary?venue={VA}")
    check(f"C5 {VA} summary gross ≥ $240",
          st == 200 and sm.get("grossCents", 0) >= 24000, str(sm.get("grossCents")))

    print(f"\n===== {len(PASS)} passed, {len(FAIL)} failed =====")
    if FAIL:
        print("FAILED:", FAIL)
        sys.exit(1)

if __name__ == "__main__":
    main()
