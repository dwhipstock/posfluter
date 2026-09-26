"""Simulated stores for the sync test: each one pushes contract-shaped event
batches to POST /v1/ingest exactly as a store's outbox pusher does (≤ 200
events per batch, in seq order, with its own key and install id).

The events are the real ones: every sale is a copy of a sale the store load
test rang up (read from its sync_outbox), with fresh event ids, the store's
own check / line / tender ids and the current time. Runs in worker processes
(the JSON work would otherwise be Python-bound, not cloud-bound).
"""
from __future__ import annotations

import http.client
import json
import os
import random
import re
import sqlite3
import threading
import time
import uuid

REMAP_KEYS = ["checkId", "lineId", "tenderId", "groupId", "fuelSaleId", "refundId"]
AGG_KEY = {"check": "checkId", "fuel_sale": "fuelSaleId", "refund": "refundId"}
ISO = re.compile(r'"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2}))"')
MAGIC = re.compile(r"(?<!\d)77\d{8}(?!\d)")
BATCH_LIMIT = 200


def load_templates(db: str, n: int = 120) -> list[dict]:
    """[n] closed sales from a store database, each as its ordered events with
    magic numbers where the ids go and __TS__ where the timestamps go."""
    con = sqlite3.connect(db)
    closed = [r[0] for r in con.execute(
        "select id from checks where status='CLOSED' order by id desc limit ?", (n,))]
    fuel = {}
    for (eid, payload, agg_type, etype, agg_id) in con.execute(
            "select id, payload, aggregate_type, event_type, aggregate_id from sync_outbox "
            "where aggregate_type in ('fuel_sale','refund')"):
        cid = json.loads(payload).get("checkId")
        if cid is not None:
            fuel.setdefault(int(cid), []).append((eid, etype, agg_type, agg_id, payload))
    out = []
    for cid in closed:
        rows = [(r[0], r[1], r[2], r[3], r[4]) for r in con.execute(
            "select id, event_type, aggregate_type, aggregate_id, payload from sync_outbox "
            "where aggregate_type='check' and aggregate_id=? order by id", (str(cid),))]
        rows += fuel.get(cid, [])
        rows.sort()
        magic: dict[tuple[str, int], str] = {}

        def mark(key: str, value: int) -> str:
            k = (key, int(value))
            if k not in magic:
                magic[k] = f"77{REMAP_KEYS.index(key):02d}{len(magic):06d}"
            return magic[k]

        def walk(o):
            if isinstance(o, dict):
                return {k: (int(mark(k, v)) if k in REMAP_KEYS and isinstance(v, int) and not isinstance(v, bool)
                            else walk(v)) for k, v in o.items()}
            if isinstance(o, list):
                return [walk(v) for v in o]
            return o
        events, total = [], 0
        for (_, etype, agg_type, agg_id, payload) in rows:
            p = walk(json.loads(payload))
            if etype == "check.closed":
                total = int(p.get("grandTotalCents") or 0)
            key = AGG_KEY.get(agg_type)
            agg = mark(key, int(agg_id)) if key and agg_id.isdigit() else agg_id
            body = ISO.sub('"__TS__"', json.dumps(p, separators=(",", ":")))
            events.append((etype, agg_type, agg, body))
        if any(e[0] == "check.closed" for e in events):
            out.append({"events": events, "total": total, "keys": sorted({k for k, _ in magic})})
    con.close()
    if not out:
        raise SystemExit(f"no sales with events in {db}")
    return out


class SimStore:
    """One store's identity and counters; makes sales and pushes batches."""

    def __init__(self, spec: dict, templates: list[dict], host: str, port: int, seed: int):
        self.venue, self.key, self.kind = spec["venue"], spec["key"], spec["kind"]
        self.install = spec["install"]
        self.templates = templates
        self.rng = random.Random(seed)
        self.seq = spec.get("seq", 0)
        self.next = {k: spec.get("start", 1) for k in REMAP_KEYS}
        self.host, self.port = host, port
        self.conn: http.client.HTTPConnection | None = None
        self.pending: list[str] = []
        # what the cloud must end up with
        self.events_made = 0
        self.sales_made = 0
        self.total_cents = 0

    def make_sales(self, n: int) -> None:
        now = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + f".{int(time.time() * 1000) % 1000:03d}Z"
        for _ in range(n):
            t = self.rng.choice(self.templates)
            ids: dict[str, str] = {}

            def sub(m):
                v = ids.get(m.group())
                if v is None:
                    key = REMAP_KEYS[int(m.group()[2:4])]
                    v = ids[m.group()] = str(self.next[key])
                    self.next[key] += 1
                return v
            for etype, agg_type, agg, body in t["events"]:
                self.seq += 1
                a = MAGIC.sub(sub, agg)
                b = MAGIC.sub(sub, body).replace("__TS__", now)
                self.pending.append(
                    f'{{"eventId":"{uuid.uuid4()}","seq":{self.seq},"eventType":"{etype}",'
                    f'"aggregateType":"{agg_type}","aggregateId":"{a}","createdAt":"{now}","payload":{b}}}')
            self.events_made += len(t["events"])
            self.sales_made += 1
            self.total_cents += t["total"]

    def take_batch(self) -> list[str]:
        batch, self.pending = self.pending[:BATCH_LIMIT], self.pending[BATCH_LIMIT:]
        return batch

    def body(self, batch: list[str]) -> bytes:
        return f'{{"installId":"{self.install}","events":[{",".join(batch)}]}}'.encode()

    def push(self, body: bytes, conn: http.client.HTTPConnection | None = None) -> tuple[int, float, dict]:
        own = conn is None
        if own:
            if self.conn is None:
                self.conn = http.client.HTTPConnection(self.host, self.port, timeout=120)
            conn = self.conn
        t0 = time.perf_counter()
        try:
            conn.request("POST", "/v1/ingest", body=body, headers={
                "Content-Type": "application/json", "Authorization": "Bearer " + self.key})
            r = conn.getresponse()
            data = r.read()
            ms = (time.perf_counter() - t0) * 1000
            return r.status, ms, (json.loads(data) if r.status == 200 else {"error": data[:200].decode(errors="replace")})
        except Exception as e:
            if own:
                self.conn = None
            return 0, (time.perf_counter() - t0) * 1000, {"error": repr(e)[:200]}


def worker(args: dict) -> dict:
    """One process: a thread per store, for one phase. Returns what it measured."""
    templates = {k: load_templates(p) for k, p in args["template_dbs"].items()}
    host, port = "127.0.0.1", args["port"]
    phase, seconds = args["phase"], args["seconds"]
    lat: list[float] = []
    lock = threading.Lock()
    tally = {"pushes": 0, "errors": 0, "accepted": 0, "duplicates": 0, "resent_events": 0, "error_samples": []}
    stores = [SimStore(s, templates[s["kind"]], host, port, seed=hash(s["venue"]) & 0xFFFF) for s in args["stores"]]
    t_start = time.time()

    def record(status, ms, resp, resent=0):
        with lock:
            tally["pushes"] += 1
            if status == 200:
                lat.append(ms)
                tally["accepted"] += resp.get("accepted", 0)
                tally["duplicates"] += resp.get("duplicates", 0)
            else:
                tally["errors"] += 1
                if len(tally["error_samples"]) < 5:
                    tally["error_samples"].append(f"{status} {resp.get('error')}")
            tally["resent_events"] += resent

    def send(st: SimStore, batch: list[str]) -> bool:
        body = st.body(batch)
        status, ms, resp = st.push(body)
        record(status, ms, resp)
        if status != 200:
            st.pending = batch + st.pending  # the store keeps it and retries next tick
            return False
        r = st.rng.random()
        if r < args.get("resend", 0):
            # a lost acknowledgement: the store sends the same batch again
            s2, ms2, resp2 = st.push(body)
            record(s2, ms2, resp2, resent=len(batch))
        elif r < args.get("resend", 0) + args.get("race", 0):
            # the retry overlaps the first attempt: two copies at once
            c = http.client.HTTPConnection(host, port, timeout=120)
            out = {}
            th = threading.Thread(target=lambda: out.update(zip(("s", "ms", "r"), st.push(body, c))))
            th.start()
            s2, ms2, resp2 = st.push(body)
            th.join()
            c.close()
            record(s2, ms2, resp2, resent=len(batch))
            record(out["s"], out["ms"], out["r"], resent=len(batch))
        return True

    def run_store(st: SimStore):
        if phase == "steady":
            # every 10 s (the store's sync tick), the sales made since the last tick
            per_tick = args["sales_per_min"] / 6.0
            owed = st.rng.random() * per_tick
            next_tick = time.time() + st.rng.random() * 10
            while time.time() < t_start + seconds:
                time.sleep(max(0, next_tick - time.time()))
                next_tick += 10
                owed += per_tick
                n, owed = int(owed), owed - int(owed)
                st.make_sales(n)
                while st.pending and send(st, st.take_batch()):
                    pass
        elif phase == "max":
            while time.time() < t_start + seconds:
                if len(st.pending) < BATCH_LIMIT:
                    st.make_sales(16)
                send(st, st.take_batch())
        elif phase == "backlog":
            st.make_sales(args["backlog_sales"])
            t0 = time.time()
            while st.pending:
                if not send(st, st.take_batch()):
                    time.sleep(1)
            with lock:
                tally["drain_s"] = round(time.time() - t0, 2)
        # a store never leaves unsent events behind at the end of a phase
        while st.pending:
            if not send(st, st.take_batch()):
                time.sleep(1)

    threads = [threading.Thread(target=run_store, args=(s,), daemon=True) for s in stores]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return tally | {
        "latencies": lat, "wall_s": time.time() - t_start,
        "stores": {s.venue: {"events": s.events_made, "sales": s.sales_made, "total_cents": s.total_cents,
                             "seq": s.seq, "next": max(s.next.values())} for s in stores},
    }


def cpu_count() -> int:
    return max(2, min(8, (os.cpu_count() or 4) - 2))
