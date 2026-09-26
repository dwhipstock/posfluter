"""Sync, store → portal: many stores pushing sales at once, a store coming
back after a day offline, nothing lost or counted twice, and the portal's
dashboard and reports over a year of 200 stores.

  steady   10 / 50 / 200 stores on the store's own 10 s sync tick, each at a
           busy 6 sales a minute (every event of every sale)
  max      the same stores pushing back to back: what the cloud can ingest.
           3% of batches are sent twice (a lost acknowledgement) and 2% twice
           at the same moment (a retry racing the first attempt)
  backlog  one store with a busy day of sales held back, then sent; and a real
           store server, offline behind a switchable proxy, coming back online
           with acknowledgements lost on the way
  verify   the cloud's events, sales and totals against what the stores sent
  portal   see lt/portal.py
"""
from __future__ import annotations

import multiprocessing as mp
import os
import secrets
import socket
import sqlite3
import subprocess
import threading
import time
import uuid

from . import store as store_lt
from .cloud import PG_NAME, Cloud
from .common import PORTS, WORK, Sampler, fresh_dir, log, save_result, start_store, summary
from .syncsim import cpu_count, worker

KINDS = ["restaurant", "retail", "gas"]


class DockerSampler(threading.Thread):
    """CPU % and memory of the Postgres container every few seconds."""

    def __init__(self, name: str = PG_NAME, every: float = 3):
        super().__init__(daemon=True)
        self.name, self.every = name, every
        self.samples: list[tuple[float, float]] = []
        self._stop = threading.Event()

    def run(self):
        while not self._stop.is_set():
            try:
                out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}} {{.MemUsage}}",
                                      self.name], capture_output=True, text=True, timeout=20).stdout.split()
                cpu = float(out[0].rstrip("%"))
                mem = out[1]
                mb = float(mem[:-3]) * (1024 if mem.endswith("GiB") else 1) if mem[-3:] in ("GiB", "MiB") else 0
                self.samples.append((cpu, mb))
            except Exception:
                pass
            self._stop.wait(self.every)

    def stop(self) -> dict:
        self._stop.set()
        self.join(30)
        cpu = [s[0] for s in self.samples]
        mem = [s[1] for s in self.samples]
        return {"pg_cpu_avg_pct": round(sum(cpu) / len(cpu), 1) if cpu else 0,
                "pg_cpu_max_pct": round(max(cpu), 1) if cpu else 0,
                "pg_mem_max_mb": round(max(mem), 1) if mem else 0}


def template_dbs() -> dict:
    dbs = {}
    for k in KINDS:
        p = os.path.join(store_lt.store_dir(k), "pos.db")
        if not os.path.exists(p):
            log(f"sync: no store-{k} sales to copy yet; running a short store load first")
            store_lt.run(k, [5], 20, warmup=5)
        dbs[k] = p
    return dbs


def run_phase(cloud: Cloud, specs: list[dict], phase: str, seconds: float, **kw) -> dict:
    procs = min(cpu_count(), len(specs))
    chunks = [specs[i::procs] for i in range(procs)]
    dbs = kw.pop("dbs")
    args = [{"stores": c, "phase": phase, "seconds": seconds, "port": cloud.api_port,
             "template_dbs": dbs, **kw} for c in chunks]
    api = Sampler(cloud.api.pid, every=1)
    pg = DockerSampler()
    api.start()
    pg.start()
    t0 = time.time()
    with mp.get_context("spawn").Pool(procs) as pool:
        parts = pool.map(worker, args)
    wall = time.time() - t0
    lat = [x for p in parts for x in p["latencies"]]
    out = {k: sum(p[k] for p in parts) for k in ("pushes", "errors", "accepted", "duplicates", "resent_events")}
    out["error_samples"] = [e for p in parts for e in p["error_samples"]][:5]
    per_store = {v: s for p in parts for v, s in p["stores"].items()}
    sales = sum(s["sales"] for s in per_store.values())
    events = sum(s["events"] for s in per_store.values())
    busy = max(p["wall_s"] for p in parts)
    out |= {
        "stores": len(specs), "seconds": round(busy, 1), "sales": sales, "events": events,
        "sales_per_s": round(sales / busy, 1), "events_per_s": round(events / busy, 1),
        "latency_ms": summary(lat), "per_store": per_store,
    } | api.stop() | pg.stop()
    if any("drain_s" in p for p in parts):
        out["drain_s"] = max(p.get("drain_s", 0) for p in parts)
    # the next phase continues each store's seq and ids
    for s in specs:
        st = per_store[s["venue"]]
        s["seq"], s["start"] = st["seq"], st["next"]
        s["sent_events"] = s.get("sent_events", 0) + st["events"]
        s["sent_sales"] = s.get("sent_sales", 0) + st["sales"]
        s["sent_cents"] = s.get("sent_cents", 0) + st["total_cents"]
    return out


def verify(cloud: Cloud, specs: list[dict], tallies: list[dict]) -> dict:
    """Nothing lost, nothing counted twice: per store, the cloud's raw events,
    closed sales and their total against what that store sent."""
    ev = {r[0]: int(r[1]) for r in cloud.sql("select venue_id, count(*) from events group by 1")}
    ck = {r[0]: (int(r[1]), int(r[2] or 0)) for r in cloud.sql(
        "select venue_id, count(*), sum(grand_total_cents) from checks where status='CLOSED' group by 1")}
    bad = []
    lost = dup = 0
    for s in specs:
        if not s.get("sent_events"):
            continue
        e = ev.get(s["venue"], 0)
        n, cents = ck.get(s["venue"], (0, 0))
        if e != s["sent_events"] or n != s["sent_sales"] or cents != s["sent_cents"]:
            bad.append({"venue": s["venue"], "events": [e, s["sent_events"]], "sales": [n, s["sent_sales"]],
                        "cents": [cents, s["sent_cents"]]})
        lost += max(0, s["sent_events"] - e)
        dup += max(0, e - s["sent_events"])
    sent = sum(s.get("sent_events", 0) for s in specs)
    accepted = sum(t["accepted"] for t in tallies)
    dups = sum(t["duplicates"] for t in tallies)
    resent = sum(t["resent_events"] for t in tallies)
    return {
        "stores_checked": sum(1 for s in specs if s.get("sent_events")),
        "events_sent": sent, "events_in_cloud": sum(ev.get(s["venue"], 0) for s in specs),
        "sales_sent": sum(s.get("sent_sales", 0) for s in specs),
        "sales_in_cloud": sum(ck.get(s["venue"], (0, 0))[0] for s in specs),
        "lost_events": lost, "duplicated_events": dup,
        "accepted_reported": accepted, "duplicates_reported": dups, "events_resent": resent,
        "mismatched_stores": bad[:10], "ok": not bad and accepted == sent and dups == resent,
    }


# ------------------------------------------------------------ a real store, offline then back

class SwitchProxy(threading.Thread):
    """TCP proxy in front of the cloud API: offline (connections refused), or
    online; [drop_acks] ingest responses are thrown away after the cloud has
    applied the batch, as a dropped connection would."""

    def __init__(self, listen: int, upstream: int):
        super().__init__(daemon=True)
        self.listen, self.upstream = listen, upstream
        self.online = False
        self.drop_acks = 0
        self.dropped = 0
        self.sock = None
        self._stop = threading.Event()

    def run(self):
        while not self._stop.is_set():
            if not self.online:
                if self.sock:
                    self.sock.close()
                    self.sock = None
                time.sleep(0.1)
                continue
            if self.sock is None:
                self.sock = socket.socket()
                self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                self.sock.bind(("127.0.0.1", self.listen))
                self.sock.listen(64)
                self.sock.settimeout(0.2)
            try:
                c, _ = self.sock.accept()
            except (socket.timeout, OSError):
                continue
            threading.Thread(target=self.pipe, args=(c,), daemon=True).start()

    def pipe(self, c: socket.socket):
        try:
            u = socket.create_connection(("127.0.0.1", self.upstream), timeout=60)
        except OSError:
            c.close()
            return
        doomed = {"v": False}

        def up():
            try:
                while True:
                    d = c.recv(65536)
                    if not d:
                        break
                    if d.startswith(b"POST /v1/ingest") and self.drop_acks > 0:
                        self.drop_acks -= 1
                        doomed["v"] = True
                    u.sendall(d)
            except OSError:
                pass
            finally:
                try:
                    u.shutdown(socket.SHUT_WR)
                except OSError:
                    pass

        t = threading.Thread(target=up, daemon=True)
        t.start()
        try:
            while True:
                d = u.recv(65536)
                if not d:
                    break
                if doomed["v"]:
                    self.dropped += 1
                    break  # the cloud applied it; the store never hears back
                c.sendall(d)
        except OSError:
            pass
        finally:
            c.close()
            u.close()

    def stop(self):
        self._stop.set()
        self.online = False


def real_store_backlog(cloud: Cloud, spec: dict, seconds: float) -> dict:
    """The store server itself: sells for [seconds] with the cloud unreachable,
    then the network comes back (first acknowledgements lost)."""
    data = fresh_dir(os.path.join(WORK, "backlog-store"))
    proxy = SwitchProxy(PORTS["proxy"], cloud.api_port)
    proxy.start()
    st = start_store("backlog-store", "plateau", PORTS["backlog"], data, {
        "CLOUD_SYNC_URL": f"http://127.0.0.1:{PORTS['proxy']}", "CLOUD_SYNC_API_KEY": spec["key"],
        "CLOUD_SYNC_INTERVAL_SECONDS": "10", "POS_KITCHEN_PRINTING": "on"})
    try:
        base = f"http://127.0.0.1:{PORTS['backlog']}"
        scn = store_lt.Restaurant(base)
        pins = store_lt.staff_pins(base, 5)
        log(f"sync: real store offline, selling for {seconds:.0f}s …")
        lv = store_lt.run_level(scn, 5, seconds, st.pid, seed=5, pins=pins)
        db = os.path.join(data, "pos.db")
        con = sqlite3.connect(db)
        q = lambda s: con.execute(s).fetchone()
        outbox = q("select count(*) from sync_outbox")[0]
        closed, cents = q("select count(*), coalesce(sum(locked_grand_total_cents),0) from checks where status='CLOSED'")
        log(f"sync: {closed} sales, {outbox} events waiting; network back (first 3 acknowledgements lost)")
        proxy.drop_acks = 3
        proxy.online = True
        t0 = time.time()
        in_cloud = 0
        while time.time() - t0 < 900:
            in_cloud = int(cloud.one(f"select count(*) from events where venue_id='{spec['venue']}'") or 0)
            outbox = q("select count(*) from sync_outbox")[0]
            if in_cloud >= outbox:
                break
            time.sleep(0.5)
        drain = time.time() - t0
        time.sleep(2)
        n, total = cloud.sql(f"select count(*), coalesce(sum(grand_total_cents),0) from checks "
                             f"where venue_id='{spec['venue']}' and status='CLOSED'")[0]
        hwm = q("select value from sync_state where key='push_hwm'")
        ids_store = {r[0] for r in con.execute("select event_id from sync_outbox")}
        ids_cloud = {r[0] for r in cloud.sql(f"select event_id from events where venue_id='{spec['venue']}'")}
        con.close()
        out = {
            "sales": closed, "events": outbox, "sales_per_min_while_offline": lv["sales_per_min"],
            "errors_while_offline": lv["errors"], "drain_s": round(drain, 1),
            "events_per_s": round(outbox / drain, 1) if drain else 0,
            "acks_dropped": proxy.dropped, "missing_in_cloud": len(ids_store - ids_cloud),
            "extra_in_cloud": len(ids_cloud - ids_store), "cloud_sales": int(n), "cloud_cents": int(total),
            "store_cents": int(cents), "push_hwm": int(hwm[0]) if hwm else None,
        }
        out["ok"] = (out["missing_in_cloud"] == 0 and out["extra_in_cloud"] == 0
                     and out["cloud_sales"] == closed and out["cloud_cents"] == int(cents))
        log(f"sync: real store drained {outbox} events in {out['drain_s']}s, ok={out['ok']} "
            f"(missing {out['missing_in_cloud']}, extra {out['extra_in_cloud']}, "
            f"sales {out['cloud_sales']}/{closed})")
        return out
    finally:
        st.stop()
        proxy.stop()


def run(quick: bool = False) -> dict:
    dbs = template_dbs()
    sizes = [5, 20] if quick else [10, 50, 200]
    seconds = 20 if quick else float(os.environ.get("LT_SYNC_SECONDS", "60"))
    specs = [{"venue": f"s{i:03d}", "name": f"Load Store {i:03d}", "key": secrets.token_hex(24),
              "kind": KINDS[i % 3], "install": str(uuid.uuid4())} for i in range(1, max(sizes) + 1)]
    real = {"venue": "real-store", "name": "Real Store (backlog)", "key": secrets.token_hex(24), "kind": "restaurant"}
    cloud = Cloud()
    results: dict = {"phases": []}
    tallies = []
    try:
        cloud.start_pg()
        cloud.start_api(specs + [real])
        for n in sizes:
            log(f"sync steady: {n} stores, 6 sales/min each, {seconds:.0f}s …")
            r = run_phase(cloud, specs[:n], "steady", seconds, dbs=dbs, sales_per_min=6)
            log(f"  → {r['sales_per_s']} sales/s, push p50 {r['latency_ms']['p50']} ms p95 {r['latency_ms']['p95']} ms "
                f"p99 {r['latency_ms']['p99']} ms, errors {r['errors']}, API CPU {r['cpu_avg_pct']}%, PG CPU {r['pg_cpu_avg_pct']}%")
            tallies.append(r)
            results["phases"].append({"phase": "steady"} | {k: v for k, v in r.items() if k != "per_store"})
        for n in sizes:
            log(f"sync max: {n} stores pushing back to back, {seconds:.0f}s …")
            r = run_phase(cloud, specs[:n], "max", seconds, dbs=dbs, resend=0.03, race=0.02)
            log(f"  → {r['seconds']}s: {r['sales_per_s']} sales/s ({r['events_per_s']} events/s), push p50 {r['latency_ms']['p50']} ms "
                f"p95 {r['latency_ms']['p95']} ms, errors {r['errors']}, API CPU {r['cpu_avg_pct']}%, PG CPU {r['pg_cpu_avg_pct']}%")
            tallies.append(r)
            results["phases"].append({"phase": "max"} | {k: v for k, v in r.items() if k != "per_store"})
        day = 300 if quick else 1500
        log(f"sync backlog: one store sends a busy day ({day} sales) held back offline …")
        r = run_phase(cloud, specs[:1], "backlog", 0, dbs=dbs, backlog_sales=day)
        log(f"  → {r['events']} events in {r.get('drain_s')}s")
        tallies.append(r)
        results["backlog_sim"] = {k: v for k, v in r.items() if k != "per_store"}
        results["verify"] = verify(cloud, specs, tallies)
        v = results["verify"]
        log(f"sync verify: {v['events_in_cloud']}/{v['events_sent']} events, {v['sales_in_cloud']}/{v['sales_sent']} sales, "
            f"lost {v['lost_events']}, duplicated {v['duplicated_events']}, ok={v['ok']} "
            f"(cloud said accepted {v['accepted_reported']} of {v['events_sent']}, "
            f"duplicates {v['duplicates_reported']} of {v['events_resent']} resent)")
        results["backlog_real"] = real_store_backlog(cloud, real, 15 if quick else 30)
        results["cloud_sizes_after_ingest"] = cloud.sizes()
        from . import portal
        results["portal"] = portal.run(cloud, specs + [real], quick=quick)
        save_result("sync", results)
        return results
    finally:
        if not os.environ.get("LT_KEEP_CLOUD"):
            cloud.stop()
