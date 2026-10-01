#!/usr/bin/env python3
"""Red-team: two-way menu sync, attacked end to end on this machine.

Each scenario is a REPRO of a suspected bug: it prints CONFIRMED when the bug
shows (the observed state differs from what a correct sync must end with) and
HELD when the system behaved correctly. Exit code = number of CONFIRMED.

Wiring (all local, nothing hosted):
  * a throwaway cloud API on a PRIVATE Postgres database (created + dropped),
  * one store server (temp SQLite) syncing every 2 s,
  * a small HTTP proxy between the store and the cloud, so a test can take
    the store "offline" (network down) or lose the cloud's replies while both
    processes keep running.

Build first:  (cd cloud/api && ./gradlew installDist) && (cd server && ./gradlew installDist)
Run:          python3 scripts/e2e/redteam_sync.py [scenario ...]     (default: all)
              RT_TMP=<dir> puts the temp store/logs there; RT_KEEP_TMP=1 keeps them.
              Ports: RT_CLOUD_PORT / RT_PROXY_PORT / RT_STORE_PORT (18124/18125/18123); the
              script refuses to start if one is already taken (another store would answer).

Scenarios (CONFIRMED on main 97e0d94 unless marked held):
  category_delete_vs_create     tablet deletes empty category offline + portal adds a dish to it
  revive_into_deleted_category  dish revived by a later portal edit, its category stays gone
  clock_back_offline            tablet clock back >10 min while offline -> permanent divergence
  counter_saturation            >9,999 stamps while the clock lags -> stamps tie, edit lost
  cloud_restore                 cloud DB restored from backup -> new portal edits never reach the store
  ingest_race                   store push vs portal edit of the same item -> lost update on the cloud
  reorder_race                  tablet + portal category drags merge into neither order
  held: lost_replies, last_size_race, portal_cat_delete_vs_tablet_add, price_open_check,
        feed_replayed, translations, big_menu (5,000 items, 400-create burst, store kill -9)
"""
import http.server as httpserver
import json
import os
import shutil
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CLOUD_BIN = os.path.join(ROOT, "cloud/api/build/install/pos-cloud-api/bin/pos-cloud-api")
STORE_BIN = os.path.join(ROOT, "server/build/install/pos-server/bin/pos-server")
DB = os.environ.get("RT_DB", "pos_cloud_redteam_sync")
PG_USER = os.environ.get("RT_PG_USER", os.environ.get("USER", "postgres"))
CLOUD_PORT = int(os.environ.get("RT_CLOUD_PORT", "18124"))
PROXY_PORT = int(os.environ.get("RT_PROXY_PORT", "18125"))
STORE_PORT = int(os.environ.get("RT_STORE_PORT", "18123"))
CLOUD = f"http://127.0.0.1:{CLOUD_PORT}"
STORE = f"http://127.0.0.1:{STORE_PORT}"
KEY = "redteam-menu-sync-store-key"
VENUE = "vieux-port"
TICK = 2


def http(method, url, body=None, headers=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def wait_for(what, fn, timeout=40, quiet=False):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = fn()
            if last:
                return last
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(0.5)
    if quiet:
        return None
    raise AssertionError(f"timed out waiting for {what} (last: {last})")


# --- the store <-> cloud proxy -------------------------------------------------

class Net:
    """mode: up | down (store can't reach the cloud) | lose_replies (cloud does the work, the store sees 502)."""
    mode = "up"
    log = []


class ProxyHandler(httpserver.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _do(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        Net.log.append((time.time(), self.command, self.path, Net.mode))
        if Net.mode == "down":
            self.send_response(503)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        path = self.path
        if Net.mode == "replay_feed" and path.startswith("/v1/store/menu/changes"):
            path = "/v1/store/menu/changes?since=0"  # every pull gets the whole feed again, oldest first
        hdrs = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection")}
        req = urllib.request.Request(CLOUD + path, data=body, method=self.command, headers=hdrs)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, raw, ctype = r.status, r.read(), r.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as e:
            status, raw, ctype = e.code, e.read(), e.headers.get("Content-Type", "application/json")
        except Exception:  # noqa: BLE001
            status, raw, ctype = 502, b"", "text/plain"
        if Net.mode == "lose_replies":
            status, raw = 502, b""
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _do


class ThreadingServer(socketserver.ThreadingMixIn, httpserver.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# --- processes -------------------------------------------------------------------

class Proc:
    def __init__(self, name, cmd, env, log_dir):
        self.name, self.cmd, self.env = name, cmd, env
        self.log_path = os.path.join(log_dir, f"{name}.log")
        self.log = open(self.log_path, "ab")
        self.p = None

    def start(self):
        self.p = subprocess.Popen(self.cmd, env={**os.environ, **self.env}, stdout=self.log,
                                  stderr=subprocess.STDOUT, cwd=self.env.get("_CWD"))

    def stop(self, kill=False):
        if self.p and self.p.poll() is None:
            if kill:
                self.p.kill()
            else:
                self.p.terminate()
            try:
                self.p.wait(15)
            except subprocess.TimeoutExpired:
                self.p.kill()
        self.p = None


class Env:
    def __init__(self):
        self.tmp = tempfile.mkdtemp(prefix="pos-redteam-sync-", dir=os.environ.get("RT_TMP"))
        self.db_path = os.path.join(self.tmp, "pos.db")
        subprocess.run(["dropdb", "--if-exists", "-U", PG_USER, DB], check=False, capture_output=True)
        subprocess.run(["createdb", "-U", PG_USER, DB], check=True)
        self.cloud = Proc("cloud", [CLOUD_BIN], {
            "DATABASE_URL": f"jdbc:postgresql://localhost:5432/{DB}", "DB_USER": PG_USER, "PORT": str(CLOUD_PORT),
            "MIGRATIONS_DIR": os.path.join(ROOT, "cloud/migrations"), "TENANT_ID": "copperlantern",
            "STORES": f"{VENUE}=Glenwood South", "STORE_API_KEY": KEY, "ADMIN_EMAIL": "owner@rt.local",
            "ADMIN_PASSWORD": "rt-owner-password", "TOTP_REQUIRED": "false", "JAVA_OPTS": "-Xmx384m",
        }, self.tmp)
        self.store = Proc("store", [STORE_BIN], {
            "POS_DB": self.db_path, "POS_PORT": str(STORE_PORT), "POS_VENUE": VENUE,
            "POS_RECEIPTS_DIR": os.path.join(self.tmp, "receipts"), "POS_BILLS_DIR": os.path.join(self.tmp, "bills"),
            "POS_PHOTOS_DIR": os.path.join(self.tmp, "photos"),
            "CLOUD_SYNC_URL": f"http://127.0.0.1:{PROXY_PORT}", "CLOUD_SYNC_API_KEY": KEY,
            "CLOUD_SYNC_INTERVAL_SECONDS": str(TICK), "JAVA_OPTS": "-Xmx384m", "_CWD": self.tmp,
        }, self.tmp)
        self.proxy = ThreadingServer(("127.0.0.1", PROXY_PORT), ProxyHandler)
        threading.Thread(target=self.proxy.serve_forever, daemon=True).start()
        self.P = None
        self.T = None

    # lifecycle
    def up(self):
        self.cloud.start()
        wait_for("cloud", lambda: http("GET", f"{CLOUD}/health")[0] == 200, 120)
        self.login_portal()
        self.start_store()
        wait_for("first sync", lambda: self.cloud_item("lantern-lager") is not None, 90)
        wait_for("store pulling the feed",
                 lambda: self.portal("GET", "/v1/menu/sync-status")[1]["stores"][0]["editable"], 40)

    def start_store(self):
        self.store.start()
        wait_for("store", lambda: http("GET", f"{STORE}/health")[0] == 200, 120)
        _, login = http("POST", f"{STORE}/login", {"pin": "1234"})
        self.T = {"Authorization": f"Bearer {login['token']}"}

    def down(self):
        self.store.stop()
        self.cloud.stop()
        self.proxy.shutdown()
        subprocess.run(["dropdb", "--if-exists", "-U", PG_USER, DB], check=False, capture_output=True)
        if os.environ.get("RT_KEEP_TMP"):
            print("kept", self.tmp)
        else:
            shutil.rmtree(self.tmp, ignore_errors=True)

    def login_portal(self):
        req = urllib.request.Request(f"{CLOUD}/v1/auth/login", method="POST", headers={"Content-Type": "application/json"},
                                     data=json.dumps({"email": "owner@rt.local", "password": "rt-owner-password"}).encode())
        with urllib.request.urlopen(req) as r:
            cookie = [h.split(";")[0] for h in r.headers.get_all("Set-Cookie") if h.startswith("pos_portal_session=")][0]
        self.P = {"Cookie": cookie}

    # APIs
    def portal(self, method, path, body=None, key=None, venue=VENUE):
        h = dict(self.P)
        if key:
            h["Idempotency-Key"] = key
        if venue:
            path += ("&" if "?" in path else "?") + f"venue={venue}"
        return http(method, f"{CLOUD}{path}", body, h)

    def tablet(self, method, path, body=None):
        return http(method, f"{STORE}{path}", body, self.T)

    def store_items(self):
        return self.tablet("GET", "/items?all=true")[1]

    def store_item(self, item_id):
        return next((i for i in self.store_items() if i["id"] == item_id), None)

    def store_categories(self):
        return self.tablet("GET", "/categories")[1]

    def cloud_menu(self):
        return self.portal("GET", "/v1/menu")[1]

    def cloud_item(self, item_id):
        return next((i for i in self.cloud_menu()["items"] if i["id"] == item_id), None)

    def cloud_category(self, cid):
        return next((c for c in self.cloud_menu()["categories"] if c["id"] == cid), None)

    def sql(self, q):
        out = subprocess.run(["psql", "-U", PG_USER, "-d", DB, "-tAc", q], capture_output=True, text=True, check=True)
        return out.stdout.strip()

    def sqlite(self, q):
        out = subprocess.run(["sqlite3", self.db_path, q], capture_output=True, text=True, check=True)
        return out.stdout.strip()

    def settle(self, ticks=4):
        time.sleep(TICK * ticks + 1)

    def pending(self):
        return self.portal("GET", "/v1/menu/sync-status")[1]["stores"][0]["pending"]

    def outbox_drained(self):
        hwm = self.sqlite("SELECT value FROM sync_state WHERE key='push_hwm'") or "0"
        top = self.sqlite("SELECT COALESCE(MAX(id),0) FROM sync_outbox")
        return int(hwm) >= int(top)

    def quiesce(self, timeout=60):
        """Both directions caught up: the outbox is drained and the feed fully pulled."""
        wait_for("quiet sync", lambda: self.outbox_drained() and self.pending() == 0, timeout)
        time.sleep(TICK + 1)


RESULTS = []


def report(name, bug, observed, expected):
    RESULTS.append((name, bug))
    print(("CONFIRMED " if bug else "HELD      ") + name)
    print("   observed:", observed)
    print("   expected:", expected)
    sys.stdout.flush()


def first_live_category(env, exclude=()):
    return next(c["id"] for c in env.store_categories() if c["id"] not in exclude)


# --- scenarios ---------------------------------------------------------------------

def sc_category_delete_vs_portal_create(env):
    """Tablet deletes an (empty) category while offline; portal adds a new dish to it meanwhile."""
    st, cat = env.tablet("POST", "/categories", {"nameFr": "RT Vide", "nameEn": "RT Empty"})
    cid = cat["id"]
    wait_for("category on the cloud", lambda: env.cloud_category(cid) is not None)
    env.quiesce()
    Net.mode = "down"
    st, _ = env.tablet("DELETE", f"/categories/{cid}")
    assert st == 200, st
    st, res = env.portal("POST", "/v1/menu/items", {
        "nameEn": "RT Orphan Dish", "categoryId": cid, "variants": [{"labelEn": "Regular", "priceCents": 1234}]})
    assert st == 201, (st, res)
    item_id = res["id"]
    Net.mode = "up"
    env.quiesce()
    s_item = env.store_item(item_id)
    c_item = env.cloud_item(item_id)
    c_cat = env.cloud_category(cid)
    bug = (s_item is None) != (c_item is None or c_item.get("deleted"))
    report("category deleted on tablet vs dish added to it in the portal",
           bug,
           f"store item={'absent' if s_item is None else 'present'}; cloud item="
           f"{'absent' if c_item is None else ('deleted' if c_item.get('deleted') else 'LIVE')}; "
           f"cloud category={'absent/deleted' if c_cat is None else 'live'}; portal said applied={res['applied']}",
           "both sides agree: the dish is on both menus (category revived) or on neither")


def sc_clock_back_offline(env):
    """The tablet's clock goes back > 10 min while it is offline; its later edit must not be silently lost."""
    item = "amber-ale"
    env.portal("PATCH", f"/v1/menu/items/{item}", {"descriptionEn": "Portal text P1"})
    wait_for("P1 on the store", lambda: env.store_item(item)["descriptionEn"] == "Portal text P1")
    env.quiesce()
    Net.mode = "down"
    # the tablet's wall clock is now 1 hour behind (physicalNow = currentTimeMillis + offset, and the
    # offset is only re-measured by a successful menu pull, so this is exactly a clock change while offline)
    env.sqlite("UPDATE sync_state SET value='-3600000' WHERE key='menu_clock_offset_ms'")
    time.sleep(1)
    st, _ = env.tablet("PATCH", f"/items/{item}", {"descriptionEn": "Tablet text T2 (made later)"})
    assert st == 200, st
    Net.mode = "up"
    env.quiesce()
    s = env.store_item(item)["descriptionEn"]
    c = env.cloud_item(item)["descriptionEn"]
    report("tablet clock set back >10 min while offline, then edits a field the portal set earlier",
           s != c, f"store='{s}' cloud='{c}'",
           "both sides show the same text (ideally the tablet's later edit)")


def sc_ingest_vs_portal_race(env, seconds=60):
    """The store pushes an edit of an item while the portal is writing another field of the same item.

    Every portal write adds one NEW name (language rtaa, rtab, ...), so a write the cloud loses
    shows as a missing name. The tablet keeps editing the description, so each sync tick pushes
    a snapshot of the same item while the portal writes. A watcher reads the cloud's database
    and records every accepted portal write that later went missing there (even if a later
    tablet push put it back).
    """
    item = "lantern-lager"
    env.quiesce()
    stop = threading.Event()
    written = {}
    vanished = {}

    def portal_writer():
        n = 0
        while not stop.is_set():
            code = "rt" + "abcdefghijklmnopqrstuvwxyz"[n // 26 % 26] + "abcdefghijklmnopqrstuvwxyz"[n % 26]
            st, _ = env.portal("PATCH", f"/v1/menu/items/{item}", {"names": {code: f"name {n}"}})
            if st == 200:
                written[code] = time.time()
            n += 1
            time.sleep(0.01)

    def tablet_writer():
        i = 0
        while not stop.is_set():
            env.tablet("PATCH", f"/items/{item}", {"descriptionFr": f"tablette {i}"})
            i += 1
            time.sleep(0.25)

    def watcher():
        while not stop.is_set():
            have = set(env.sql(f"SELECT lang FROM catalog_names WHERE entity='item' AND entity_id='{item}' "
                               f"AND lang LIKE 'rt%'").split())
            now = time.time()
            for code, t in list(written.items()):
                if code not in have and t < now - 0.2 and code not in vanished:
                    vanished[code] = now
            time.sleep(0.05)

    threads = [threading.Thread(target=f) for f in (portal_writer, tablet_writer, watcher)]
    for t in threads:
        t.start()
    time.sleep(seconds)
    stop.set()
    for t in threads:
        t.join()
    env.quiesce()
    c_names = env.cloud_item(item).get("names") or {}
    s_names = env.store_item(item).get("names") or {}
    lost_cloud = [c for c in written if c not in c_names]
    lost_store = [c for c in written if c not in s_names]
    report("store push racing portal edits of the same item (lost update on the cloud)",
           bool(vanished or lost_cloud or lost_store),
           f"{len(written)} portal writes accepted (200); vanished from the cloud after being accepted: "
           f"{len(vanished)} {sorted(vanished)[:8]}; still missing at the end: cloud {len(lost_cloud)}, store {len(lost_store)}",
           "an accepted portal write never disappears from the cloud")


def sc_lost_push_replies(env):
    """The cloud ingests a push but the reply is lost: the retries must land each sale event exactly once."""
    env.quiesce()
    Net.mode = "lose_replies"
    ok = sell_one(env)
    for i in range(5):  # menu edits ride the same outbox
        env.tablet("PATCH", "/items/amber-ale", {"descriptionFr": f"perdu {i}"})
    time.sleep(TICK * 4)
    Net.mode = "up"
    env.quiesce()
    store_n = int(env.sqlite("SELECT count(*) FROM sync_outbox"))
    cloud_n = int(env.sql("SELECT count(*) FROM events"))
    dup = env.sql("SELECT count(*) - count(DISTINCT event_id) FROM events")
    tend = env.sql("SELECT count(*) FROM events WHERE event_type='check.tendered'")
    lost_ticks = len([x for x in Net.log if x[3] == "lose_replies" and "ingest" in x[2]])
    report("sales outbox: cloud replies lost, pushes retried",
           not (ok and store_n == cloud_n and dup == "0" and tend == "1"),
           f"sale ok={ok}; pushes whose reply was lost={lost_ticks}; store outbox={store_n} cloud events={cloud_n}; "
           f"duplicate ids={dup}; check.tendered in cloud={tend}",
           "every outbox event exactly once in the cloud")


def sell_one(env, table=None):
    """Open a check on a free table, ring one pint, pay cash. True when the check closed."""
    table = table or env.sqlite("SELECT id FROM dining_tables WHERE id NOT IN "
                                "(SELECT table_id FROM checks WHERE status IN ('OPEN','TOTAL_LOCKED') AND table_id IS NOT NULL) "
                                "ORDER BY id LIMIT 1")
    env.tablet("POST", "/shifts", {"openingFloatCents": 10000, "managerPin": "1234"})
    st, check = env.tablet("POST", f"/tables/{table}/checks")
    if st not in (200, 201):
        print("   (could not open a check:", st, check, ")")
        return False
    cid = check["id"]
    st, r = env.tablet("POST", f"/checks/{cid}/lines", {"itemId": "lantern-lager", "variantId": "lantern-lager:pint", "qty": 1})
    if st not in (200, 201):
        print("   (could not add a line:", st, r, ")")
        return False
    st, chk = env.tablet("GET", f"/checks/{cid}")
    total = chk.get("totalCents") or chk.get("grandTotalCents") or 100000
    st, r = env.tablet("POST", f"/checks/{cid}/tenders", {"type": "CASH", "amountTenderedCents": total + 10000})
    if st not in (200, 201):
        print("   (could not pay:", st, r, ")")
        return False
    return True


def sc_last_size_race(env):
    """Portal deletes size A of a two-size item while the offline tablet deletes size B."""
    st, res = env.portal("POST", "/v1/menu/items", {
        "nameEn": "RT Two Sizes", "categoryId": first_live_category(env),
        "variants": [{"labelEn": "Small", "priceCents": 500}, {"labelEn": "Large", "priceCents": 700}]})
    item = res["id"]
    wait_for("item on the store", lambda: env.store_item(item) is not None)
    env.quiesce()
    va, vb = [v["id"] for v in env.store_item(item)["variants"]]
    Net.mode = "down"
    st1, _ = env.tablet("DELETE", f"/items/{item}/variants/{vb}")
    st2, _ = env.portal("DELETE", f"/v1/menu/items/{item}/variants/{va}")
    Net.mode = "up"
    env.quiesce()
    sv = sorted(v["id"] for v in (env.store_item(item) or {}).get("variants", []))
    cv = sorted(v["id"] for v in (env.cloud_item(item) or {}).get("variants", []))
    report("last-size race: portal deletes one size, offline tablet deletes the other",
           sv != cv or not sv, f"tablet={st1} portal={st2}; store sizes={sv} cloud sizes={cv}",
           "both sides agree and the dish keeps at least one size")


def sc_portal_category_delete_vs_tablet_add(env):
    """Portal deletes an empty category while the offline tablet adds a dish to it."""
    st, res = env.portal("POST", "/v1/menu/categories", {"nameEn": "RT Short Lived"})
    cid = res["id"]
    wait_for("category on the store", lambda: any(c["id"] == cid for c in env.store_categories()))
    env.quiesce()
    Net.mode = "down"
    st, made = env.tablet("POST", "/items", {"nameFr": "Plat RT", "nameEn": "RT Tablet Dish", "descriptionFr": "",
                                             "descriptionEn": "", "categoryId": cid, "abbrev": "RT", "isAlcohol": False,
                                             "variants": [{"labelFr": "Rég", "labelEn": "Reg", "priceCents": 900}]})
    item = made["id"]
    st2, _ = env.portal("DELETE", f"/v1/menu/categories/{cid}")
    Net.mode = "up"
    env.quiesce()
    s_cat = any(c["id"] == cid for c in env.store_categories())
    c_cat = env.cloud_category(cid) is not None
    s_item, c_item = env.store_item(item) is not None, env.cloud_item(item) is not None
    report("portal deletes a category while the offline tablet adds a dish to it",
           not (s_cat == c_cat and s_item == c_item and (not s_item or s_cat)),
           f"portal delete={st2}; store: category={s_cat} dish={s_item}; cloud: category={c_cat} dish={c_item}",
           "both agree; a live dish is never left in a deleted category")


def sc_price_under_open_check(env):
    """A portal price change must not reprice a line already rung on an open check."""
    env.quiesce()
    env.tablet("POST", "/shifts", {"openingFloatCents": 10000, "managerPin": "1234"})
    table = env.sqlite("SELECT id FROM dining_tables WHERE id NOT IN (SELECT table_id FROM checks WHERE status IN "
                       "('OPEN','TOTAL_LOCKED') AND table_id IS NOT NULL) ORDER BY id LIMIT 1")
    _, check = env.tablet("POST", f"/tables/{table}/checks")
    cid = check["id"]
    var = next(v for v in env.store_item("amber-ale")["variants"])
    env.tablet("POST", f"/checks/{cid}/lines", {"itemId": "amber-ale", "variantId": var["id"], "qty": 1})
    before = env.tablet("GET", f"/checks/{cid}")[1]
    env.portal("PATCH", f"/v1/menu/items/amber-ale/variants/{var['id']}", {"priceCents": var["priceCents"] + 5000})
    wait_for("new price on the store", lambda: next(v for v in env.store_item("amber-ale")["variants"]
                                                    if v["id"] == var["id"])["priceCents"] == var["priceCents"] + 5000)
    after = env.tablet("GET", f"/checks/{cid}")[1]
    tb = before.get("totalCents", before.get("subtotalCents"))
    ta = after.get("totalCents", after.get("subtotalCents"))
    report("portal price change under an open check", tb != ta,
           f"check total before={tb} after={ta}", "the rung line keeps its price")


def sc_feed_replayed(env):
    """Every menu pull gets the WHOLE feed again (duplicate, stale delivery)."""
    item = "amber-ale"
    env.portal("PATCH", f"/v1/menu/items/{item}", {"nameFr": "Replay A"})
    env.portal("PATCH", f"/v1/menu/items/{item}", {"nameFr": "Replay B"})
    wait_for("B on the store", lambda: env.store_item(item)["nameFr"] == "Replay B")
    Net.mode = "replay_feed"
    env.settle(4)
    s1 = env.store_item(item)["nameFr"]
    env.tablet("PATCH", f"/items/{item}", {"nameFr": "Replay C (tablet)"})
    env.settle(4)
    Net.mode = "up"
    env.quiesce()
    s, c = env.store_item(item)["nameFr"], env.cloud_item(item)["nameFr"]
    report("menu feed delivered again and again (stale replays)", not (s1 == "Replay B" and s == c == "Replay C (tablet)"),
           f"store during replay='{s1}'; final store='{s}' cloud='{c}'", "replays change nothing; the tablet edit wins")


def sc_counter_saturation(env, edits=10100):
    """HLC counter saturates at 9999 when the wall clock lags the last stamp (clock stepped back < 10 min)."""
    item = "belgian-blonde"
    env.quiesce()
    Net.mode = "down"
    # one stamp now, then the wall clock steps back 5 minutes (e.g. an NTP correction) while offline
    env.tablet("PATCH", f"/items/{item}", {"descriptionEn": "seed"})
    env.sqlite("UPDATE sync_state SET value='-300000' WHERE key='menu_clock_offset_ms'")
    t0 = time.time()
    lock = threading.Lock()
    counter = {"n": 0}

    def worker():
        while True:
            with lock:
                n = counter["n"]
                counter["n"] += 1
            if n >= edits:
                return
            env.tablet("PATCH", "/items/brownie", {"descriptionFr": f"n{n}"})

    ts = [threading.Thread(target=worker) for _ in range(8)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    env.tablet("PATCH", f"/items/{item}", {"descriptionEn": "first after saturation"})
    env.tablet("PATCH", f"/items/{item}", {"descriptionEn": "SECOND after saturation"})
    elapsed = time.time() - t0
    stamps = env.sqlite(f"SELECT hlc FROM menu_sync_clocks WHERE entity='item' AND entity_id='{item}' AND field='descriptionEn'")
    Net.mode = "up"
    env.quiesce(240)
    s, c = env.store_item(item)["descriptionEn"], env.cloud_item(item)["descriptionEn"]
    report(f"HLC counter saturation ({edits} edits in {elapsed:.0f}s after a 5-min clock step back)",
           s != c, f"store='{s}' cloud='{c}'; final stamp={stamps}", "both show 'SECOND after saturation'")


def sc_cloud_restore(env):
    """The cloud database is restored from an earlier backup (or the demo cloud is reset): the feed seq goes back."""
    env.quiesce()
    dump = os.path.join(env.tmp, "cloud.dump")
    subprocess.run(["pg_dump", "-U", PG_USER, "-Fc", "-f", dump, DB], check=True)
    for i in range(6):
        env.portal("PATCH", "/v1/menu/items/amber-ale", {"descriptionFr": f"avant restauration {i}"})
    wait_for("edits on the store", lambda: env.store_item("amber-ale")["descriptionFr"] == "avant restauration 5")
    env.quiesce()
    store_cursor = env.sqlite("SELECT value FROM sync_state WHERE key='menu_cursor'")
    env.cloud.stop()
    subprocess.run(["dropdb", "-U", PG_USER, DB], check=True)
    subprocess.run(["createdb", "-U", PG_USER, DB], check=True)
    subprocess.run(["pg_restore", "-U", PG_USER, "-d", DB, dump], check=True)
    env.cloud.start()
    wait_for("cloud", lambda: http("GET", f"{CLOUD}/health")[0] == 200, 120)
    env.login_portal()
    env.settle(2)
    st, res = env.portal("PATCH", "/v1/menu/items/amber-ale", {"nameFr": "Apres restauration"})
    seq = env.sql("SELECT max(seq) FROM menu_feed")
    env.settle(6)
    s = env.store_item("amber-ale")["nameFr"]
    pending = env.pending()
    report("cloud restored from a backup: new portal edits vs the store's feed cursor",
           s != "Apres restauration",
           f"portal edit {st} applied={res.get('applied') if isinstance(res, dict) else res}; new feed seq={seq}; "
           f"store cursor={store_cursor}; store nameFr='{s}'; portal 'waiting for the store' count={pending}",
           "the store gets the edit (or the portal at least shows it as waiting)")


def sc_big_menu(env, n=int(os.environ.get("RT_BIG", "5000")), burst=400):
    """5,000-item menu: bootstrap ingest vs portal latency, GET /v1/menu, burst of portal creates + store crash."""
    env.quiesce()
    env.store.stop()
    cat = env.sqlite("SELECT id FROM categories ORDER BY sort_order LIMIT 1")
    rows = []
    for i in range(n):
        iid = f"bulk-{i:05d}"
        rows.append(f"INSERT INTO items (id,name_fr,name_en,category_id,abbrev) VALUES "
                    f"('{iid}','Produit {i}','Product {i}','{cat}','P{i % 100}');")
        rows.append(f"INSERT INTO item_variants (id,item_id,label_fr,label_en,price_cents) VALUES "
                    f"('{iid}:reg','{iid}','Reg','Reg',{100 + i});")
    sqlf = os.path.join(env.tmp, "bulk.sql")
    with open(sqlf, "w") as f:
        f.write("BEGIN;\n" + "\n".join(rows) + "\nDELETE FROM sync_state WHERE key='catalog_snapshot_seq';\nCOMMIT;\n")
    with open(sqlf) as f:
        subprocess.run(["sqlite3", env.db_path], stdin=f, check=True)
    t0 = time.time()
    lat = []
    stop = threading.Event()

    def sampler():
        k = 0
        while not stop.is_set():
            a = time.time()
            env.portal("PATCH", "/v1/menu/items/amber-ale", {"descriptionEn": f"sample {k}"})
            lat.append(time.time() - a)
            k += 1
            time.sleep(0.2)

    env.start_store()
    th = threading.Thread(target=sampler)
    th.start()
    count = lambda: int(env.sql("SELECT count(*) FROM catalog_items WHERE id LIKE 'bulk-%'"))
    got = wait_for("bulk on the cloud", lambda: count() >= n, 600, quiet=True)
    ingest_s = time.time() - t0
    stop.set()
    th.join()
    a = time.time()
    st, menu = env.portal("GET", "/v1/menu")
    menu_s = time.time() - a
    print(f"   bootstrap of {n} items reached the cloud in {ingest_s:.1f}s; portal PATCH latency max {max(lat):.2f}s "
          f"p50 {sorted(lat)[len(lat)//2]:.2f}s over {len(lat)} samples; GET /v1/menu {menu_s:.2f}s, {len(menu['items'])} items")
    # burst of portal creates while the store is offline, then the store crashes mid-catch-up
    env.quiesce(240)
    Net.mode = "down"
    created, cl = [], []
    for i in range(burst):
        a = time.time()
        st, r = env.portal("POST", "/v1/menu/items", {"nameEn": f"Burst {i}", "categoryId": cat,
                                                      "variants": [{"labelEn": "Reg", "priceCents": 1000 + i}]})
        cl.append(time.time() - a)
        if st == 201:
            created.append(r["id"])
    Net.mode = "up"
    time.sleep(TICK + 0.5)
    env.store.stop(kill=True)
    env.start_store()
    have = lambda: set(i["id"] for i in env.store_items())
    wait_for("burst on the store", lambda: set(created) <= have(), 300, quiet=True)
    missing = set(created) - have()
    dups = env.sqlite("SELECT count(*) FROM (SELECT name_en FROM items WHERE name_en LIKE 'Burst %' "
                      "GROUP BY name_en HAVING count(*) > 1)")
    feed_rows = env.sql("SELECT count(*) FROM menu_feed")
    feed_bytes = env.sql("SELECT pg_size_pretty(pg_total_relation_size('menu_feed'))")
    echo = env.sql("SELECT count(*) FROM events WHERE payload::jsonb->>'origin' = 'cloud'")
    print(f"   {burst} portal creates: avg {sum(cl)/len(cl):.3f}s max {max(cl):.2f}s; feed rows={feed_rows} ({feed_bytes}); "
          f"origin=cloud echo events stored in the cloud={echo}")
    report(f"{n}-item menu + burst of {burst} portal creates + store killed mid catch-up",
           bool(not got or missing or dups != "0" or max(lat) > 5),
           f"bootstrap ok={bool(got)} in {ingest_s:.0f}s, portal edit max latency {max(lat):.1f}s; "
           f"burst created={len(created)} missing on store={len(missing)}; duplicated dishes={dups}",
           "everything arrives once; portal edits stay responsive (< 5 s) during a big store push")


def sc_revive_into_deleted_category(env):
    """Offline tablet deletes a dish and then its (now empty) category; the portal edits the dish later."""
    st, cat = env.tablet("POST", "/categories", {"nameFr": "RT Saison", "nameEn": "RT Seasonal"})
    cid = cat["id"]
    st, made = env.tablet("POST", "/items", {"nameFr": "Plat saison", "nameEn": "RT Seasonal Dish", "descriptionFr": "",
                                             "descriptionEn": "", "categoryId": cid, "abbrev": "SD", "isAlcohol": False,
                                             "variants": [{"labelFr": "Reg", "labelEn": "Reg", "priceCents": 1500}]})
    item = made["id"]
    wait_for("dish on the cloud", lambda: env.cloud_item(item) is not None)
    env.quiesce()
    Net.mode = "down"
    st1, _ = env.tablet("DELETE", f"/items/{item}")
    st2, _ = env.tablet("DELETE", f"/categories/{cid}")
    time.sleep(1)
    st3, _ = env.portal("PATCH", f"/v1/menu/items/{item}", {"nameEn": "RT Seasonal Dish (portal fix)"})
    Net.mode = "up"
    env.quiesce()
    s_item, c_item = env.store_item(item), env.cloud_item(item)
    s_cat = any(c["id"] == cid for c in env.store_categories())
    c_cat = env.cloud_category(cid) is not None
    s_live = bool(s_item) and not s_item.get("deleted") and s_item.get("active", True)
    orphan = (s_live and not s_cat) or (c_item is not None and not c_cat)
    report("dish revived by a later portal edit after the tablet deleted it and its category",
           orphan or (s_item is None) != (c_item is None),
           f"tablet deletes {st1}/{st2}, portal edit {st3}; store: dish live={s_live} category exists={s_cat}; "
           f"cloud: dish listed={c_item is not None} category live={c_cat}",
           "a live dish always has a live category on both sides (category revived), or the dish stays deleted")


def sc_translations(env):
    """Portal sets, changes and removes names in other languages (item and size); a tablet edit meanwhile."""
    item = "amber-ale"
    var = env.store_item(item)["variants"][0]["id"]
    env.portal("PATCH", f"/v1/menu/items/{item}", {"names": {"es": "Ambar RT", "it": "Ambra RT", "pt-br": "Ambar BR"}})
    env.portal("PATCH", f"/v1/menu/items/{item}/variants/{var}", {"names": {"es": "Pinta RT"}})
    wait_for("names on the store", lambda: (env.store_item(item).get("names") or {}).get("pt-br") == "Ambar BR", 30, quiet=True)
    s1 = env.store_item(item).get("names")
    env.quiesce()
    Net.mode = "down"
    env.tablet("PATCH", f"/items/{item}", {"descriptionEn": "tablet edit while names change"})
    env.portal("PATCH", f"/v1/menu/items/{item}", {"names": {"it": "", "es": "Ambar RT 2"}})
    Net.mode = "up"
    env.quiesce()
    sn, cn = env.store_item(item).get("names") or {}, env.cloud_item(item).get("names") or {}
    svn = next(v for v in env.store_item(item)["variants"] if v["id"] == var).get("names")
    cvn = next(v for v in env.cloud_item(item)["variants"] if v["id"] == var).get("names")
    want = {"es": "Ambar RT 2", "pt-br": "Ambar BR"}
    ok = all(sn.get(k) == v and cn.get(k) == v for k, v in want.items()) and "it" not in sn and "it" not in cn
    report("names in other languages: set, change, remove (item + size)", not ok,
           f"after set store={s1}; final store={sn} cloud={cn}; size names store={svn} cloud={cvn}",
           f"both have {want}, no 'it'")


def sc_reorder_race(env):
    """Offline tablet drags the last category to the top; the portal then drags the second one to the top."""
    env.quiesce()
    ids = [c["id"] for c in env.store_categories()]
    Net.mode = "down"
    tablet_order = [ids[-1]] + ids[:-1]
    st1, _ = env.tablet("PATCH", "/categories/order", {"orderedIds": tablet_order})
    time.sleep(0.5)
    portal_order = [ids[1], ids[0]] + ids[2:]
    st2, _ = env.portal("PUT", "/v1/menu/categories/order", {"orderedIds": portal_order})
    Net.mode = "up"
    env.quiesce()
    s_order = [c["id"] for c in env.store_categories()]
    c_order = [c["id"] for c in env.cloud_menu()["categories"]]
    s_sorts = [c["sortOrder"] for c in env.store_categories()]
    report("category reorder on the tablet (offline) and in the portal",
           s_order != c_order or len(set(s_sorts)) != len(s_sorts),
           f"tablet {st1} order={tablet_order}; portal {st2} order={portal_order}; final store={s_order} "
           f"(sortOrders {s_sorts}); final portal={c_order}",
           "one consistent order on both sides (the later drag's), no duplicate positions")


SCENARIOS = {
    "category_delete_vs_create": sc_category_delete_vs_portal_create,
    "clock_back_offline": sc_clock_back_offline,
    "ingest_race": sc_ingest_vs_portal_race,
    "lost_replies": sc_lost_push_replies,
    "last_size_race": sc_last_size_race,
    "portal_cat_delete_vs_tablet_add": sc_portal_category_delete_vs_tablet_add,
    "price_open_check": sc_price_under_open_check,
    "feed_replayed": sc_feed_replayed,
    "counter_saturation": sc_counter_saturation,
    "cloud_restore": sc_cloud_restore,
    "big_menu": sc_big_menu,
    "revive_into_deleted_category": sc_revive_into_deleted_category,
    "translations": sc_translations,
    "reorder_race": sc_reorder_race,
}


def main():
    for b in (CLOUD_BIN, STORE_BIN):
        if not os.path.exists(b):
            sys.exit(f"missing {b}: run installDist first (see the docstring)")
    names = sys.argv[1:] or list(SCENARIOS)
    import socket
    for port in (CLOUD_PORT, PROXY_PORT, STORE_PORT):
        with socket.socket() as sock:
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                sys.exit(f"port {port} is already in use (another server would answer our requests): pick RT_*_PORT")
    env = Env()
    try:
        env.up()
        for n in names:
            print(f"\n== {n}")
            try:
                Net.mode = "up"
                SCENARIOS[n](env)
            except Exception as e:  # noqa: BLE001
                import traceback
                traceback.print_exc()
                RESULTS.append((n, None))
                print("ERROR", n, e)
    finally:
        Net.mode = "up"
        env.down()
    bugs = [r for r in RESULTS if r[1]]
    print(f"\n{len(bugs)} confirmed, {len([r for r in RESULTS if r[1] is False])} held, "
          f"{len([r for r in RESULTS if r[1] is None])} errored")
    sys.exit(len(bugs))


if __name__ == "__main__":
    main()
