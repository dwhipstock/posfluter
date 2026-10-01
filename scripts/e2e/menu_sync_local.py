#!/usr/bin/env python3
"""Two-way menu sync, end to end on this machine (no hosted server touched).

Starts a throwaway cloud API (local Postgres, its own database) and one store
server (its own temp SQLite) that syncs to it every 2 s, then checks, through
the real HTTP APIs of both:

  1. a manager-portal edit reaches the store;
  2. a tablet edit reaches the portal;
  3. both edit the same field: the later write wins in both places;
  4. the store goes offline (process stopped), the portal edits meanwhile,
     the store comes back and catches up; the cloud goes down, the tablet
     edits, the cloud comes back and gets it;
  5. a portal delete reaches the store and the item stays gone in both places
     (delete-vs-edit races are covered by the unit suites on both sides);
  6. no echo: applying portal edits never queues anything new for the store;
  7. idempotent replay: a retried portal request (same Idempotency-Key)
     changes nothing.

Build first:  (cd cloud/api && ./gradlew installDist) && (cd server && ./gradlew installDist)
Run:          python3 scripts/e2e/menu_sync_local.py
Needs psql/createdb/dropdb for a local Postgres (Homebrew postgresql@17 is fine).
Everything it starts is stopped and its temp files are deleted at the end.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CLOUD_BIN = os.path.join(ROOT, "cloud/api/build/install/pos-cloud-api/bin/pos-cloud-api")
STORE_BIN = os.path.join(ROOT, "server/build/install/pos-server/bin/pos-server")
DB = os.environ.get("E2E_DB", "pos_cloud_e2e_menusync")
PG_USER = os.environ.get("E2E_PG_USER", os.environ.get("USER", "postgres"))
CLOUD_PORT = int(os.environ.get("E2E_CLOUD_PORT", "18081"))
STORE_PORT = int(os.environ.get("E2E_STORE_PORT", "18080"))
CLOUD = f"http://127.0.0.1:{CLOUD_PORT}"
STORE = f"http://127.0.0.1:{STORE_PORT}"
KEY = "e2e-menu-sync-store-key"
VENUE = "vieux-port"

results = []


def http(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None), r.headers
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw), e.headers
        except ValueError:
            return e.code, raw, e.headers


def wait_for(what, fn, timeout=40):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = fn()
            if last:
                return last
        except Exception as e:  # noqa: BLE001 — a server still starting
            last = e
        time.sleep(0.5)
    raise AssertionError(f"timed out waiting for {what} (last: {last})")


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail and not ok else ""))


class Proc:
    def __init__(self, name, cmd, env, log_dir):
        self.name, self.cmd, self.env = name, cmd, env
        self.log = open(os.path.join(log_dir, f"{name}.log"), "ab")
        self.p = None

    def start(self):
        self.p = subprocess.Popen(self.cmd, env={**os.environ, **self.env}, stdout=self.log, stderr=subprocess.STDOUT,
                                  cwd=self.env.get("_CWD"))

    def stop(self):
        if self.p and self.p.poll() is None:
            self.p.terminate()
            try:
                self.p.wait(15)
            except subprocess.TimeoutExpired:
                self.p.kill()
        self.p = None


def main():
    for b in (CLOUD_BIN, STORE_BIN):
        if not os.path.exists(b):
            sys.exit(f"missing {b}: run installDist first (see the docstring)")
    tmp = tempfile.mkdtemp(prefix="pos-e2e-menusync-")
    subprocess.run(["dropdb", "--if-exists", "-U", PG_USER, DB], check=False, capture_output=True)
    subprocess.run(["createdb", "-U", PG_USER, DB], check=True)
    cloud = Proc("cloud", [CLOUD_BIN], {
        "DATABASE_URL": f"jdbc:postgresql://localhost:5432/{DB}", "DB_USER": PG_USER, "PORT": str(CLOUD_PORT),
        "MIGRATIONS_DIR": os.path.join(ROOT, "cloud/migrations"), "TENANT_ID": "copperlantern",
        "STORES": f"{VENUE}=Glenwood South", "STORE_API_KEY": KEY, "ADMIN_EMAIL": "owner@e2e.local",
        "ADMIN_PASSWORD": "e2e-owner-password", "TOTP_REQUIRED": "false", "JAVA_OPTS": "-Xmx384m",
    }, tmp)
    store = Proc("store", [STORE_BIN], {
        "POS_DB": os.path.join(tmp, "pos.db"), "POS_PORT": str(STORE_PORT), "POS_VENUE": VENUE,
        "POS_RECEIPTS_DIR": os.path.join(tmp, "receipts"), "POS_BILLS_DIR": os.path.join(tmp, "bills"),
        "POS_PHOTOS_DIR": os.path.join(tmp, "photos"), "CLOUD_SYNC_URL": CLOUD, "CLOUD_SYNC_API_KEY": KEY,
        "CLOUD_SYNC_INTERVAL_SECONDS": "2", "JAVA_OPTS": "-Xmx384m", "_CWD": tmp,
    }, tmp)
    try:
        cloud.start()
        wait_for("cloud", lambda: http("GET", f"{CLOUD}/health")[0] == 200, 90)
        store.start()
        wait_for("store", lambda: http("GET", f"{STORE}/health")[0] == 200, 90)

        # portal session (TOTP off on this throwaway cloud)
        req = urllib.request.Request(f"{CLOUD}/v1/auth/login", method="POST", headers={"Content-Type": "application/json"},
                                     data=json.dumps({"email": "owner@e2e.local", "password": "e2e-owner-password"}).encode())
        with urllib.request.urlopen(req) as r:
            cookie = [h.split(";")[0] for h in r.headers.get_all("Set-Cookie") if h.startswith("pos_portal_session=")][0]
        P = {"Cookie": cookie}

        def portal(method, path, body=None, key=None):
            h = dict(P)
            if key:
                h["Idempotency-Key"] = key
            sep = "&" if "?" in path else "?"
            return http(method, f"{CLOUD}{path}{sep}venue={VENUE}", body, h)

        _, login, _ = http("POST", f"{STORE}/login", {"pin": "1234"})
        T = {"Authorization": f"Bearer {login['token']}"}

        def store_item(item_id):
            _, items, _ = http("GET", f"{STORE}/items?all=true", headers=T)
            return next((i for i in items if i["id"] == item_id), None)

        def cloud_item(item_id):
            _, menu, _ = portal("GET", "/v1/menu")
            return next((i for i in menu["items"] if i["id"] == item_id), None)

        # the store has synced its menu and speaks two-way sync
        wait_for("first sync", lambda: cloud_item("lantern-lager") is not None, 60)
        wait_for("store pulling the feed", lambda: portal("GET", "/v1/menu/sync-status")[1]["stores"][0]["editable"], 30)

        def feed_count():
            out = subprocess.run(["psql", "-U", PG_USER, "-d", DB, "-tAc", "SELECT count(*) FROM menu_feed"],
                                 capture_output=True, text=True, check=True)
            return int(out.stdout.strip())

        # 1. portal edit → store
        st, _, _ = portal("PATCH", "/v1/menu/items/lantern-lager/variants/lantern-lager:pint", {"priceCents": 911})
        ok = st == 200 and wait_for("price on the store", lambda: next(
            (v for v in store_item("lantern-lager")["variants"] if v["id"] == "lantern-lager:pint"))["priceCents"] == 911)
        check("1. portal price edit reaches the store", bool(ok))

        # 2. tablet edit → portal
        http("PATCH", f"{STORE}/items/amber-ale", {"nameEn": "Amber From The Tablet"}, T)
        check("2. tablet rename reaches the portal",
              bool(wait_for("rename in the portal", lambda: cloud_item("amber-ale")["nameEn"] == "Amber From The Tablet")))

        # 3. both edit the same field — the later one wins everywhere
        portal("PATCH", "/v1/menu/items/lantern-lager", {"descriptionEn": "Portal text (earlier)"})
        time.sleep(0.3)
        http("PATCH", f"{STORE}/items/lantern-lager", {"descriptionEn": "Tablet text (later)"}, T)
        same = wait_for("convergence", lambda: store_item("lantern-lager")["descriptionEn"] == "Tablet text (later)"
                        and cloud_item("lantern-lager")["descriptionEn"] == "Tablet text (later)")
        http("PATCH", f"{STORE}/items/lantern-lager", {"descriptionEn": "Tablet text (earlier)"}, T)
        time.sleep(0.3)
        portal("PATCH", "/v1/menu/items/lantern-lager", {"descriptionEn": "Portal text (later)"})
        same2 = wait_for("convergence 2", lambda: store_item("lantern-lager")["descriptionEn"] == "Portal text (later)"
                         and cloud_item("lantern-lager")["descriptionEn"] == "Portal text (later)")
        check("3. same field edited on both sides: the later write wins in both places", bool(same and same2))

        # 4a. store offline, portal edits, store back
        store.stop()
        portal("PATCH", "/v1/menu/items/lantern-lager", {"nameFr": "Lager hors ligne"})
        store.start()
        wait_for("store back", lambda: http("GET", f"{STORE}/health")[0] == 200, 90)
        _, login, _ = http("POST", f"{STORE}/login", {"pin": "1234"})
        T["Authorization"] = f"Bearer {login['token']}"
        check("4a. an offline store catches up on the portal's edits",
              bool(wait_for("catch-up", lambda: store_item("lantern-lager")["nameFr"] == "Lager hors ligne")))
        # 4b. cloud down, tablet edits, cloud back
        cloud.stop()
        http("PATCH", f"{STORE}/items/lantern-lager", {"descriptionFr": "Modifié hors ligne"}, T)
        time.sleep(3)
        cloud.start()
        wait_for("cloud back", lambda: http("GET", f"{CLOUD}/health")[0] == 200, 90)
        check("4b. a tablet edit made while the cloud was down reaches it",
              bool(wait_for("up-sync", lambda: cloud_item("lantern-lager")["descriptionFr"] == "Modifié hors ligne", 60)))

        # 5. delete vs edit
        portal("DELETE", "/v1/menu/items/late-fries")
        gone = wait_for("delete on the store", lambda: store_item("late-fries") is None)
        check("5a. a portal delete reaches the store", bool(gone))
        check("5b. the deleted item stays gone in both places", cloud_item("late-fries") is None and store_item("late-fries") is None)

        # 6. no echo: the store applying portal edits queues nothing new for it
        before = feed_count()
        time.sleep(6)  # three sync ticks
        check("6. no echo loop (the feed only grows with portal edits)", feed_count() == before, f"{before} → {feed_count()}")

        # 7. idempotent replay
        k = str(uuid.uuid4())
        a = portal("PATCH", "/v1/menu/items/lantern-lager", {"active": False}, key=k)
        n1 = feed_count()
        b = portal("PATCH", "/v1/menu/items/lantern-lager", {"active": False}, key=k)
        check("7. a retried portal edit (same key) changes nothing",
              a[0] == 200 and b[0] == 200 and b[1].get("duplicate") is True and feed_count() == n1)
        wait_for("86 on the store", lambda: store_item("lantern-lager")["active"] is False)
    finally:
        store.stop()
        cloud.stop()
        subprocess.run(["dropdb", "--if-exists", "-U", PG_USER, DB], check=False, capture_output=True)
        shutil.rmtree(tmp, ignore_errors=True)
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
