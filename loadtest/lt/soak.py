"""Soak: one store for hours at five times demo-day pace, looking for leaks.

Demo-day pace is a busy lunch on three terminals: three sales a minute. Five
times that is 15 sales a minute, from five devices (one sale every 20 s each),
with the kitchen screen polling, every device refreshing the floor plan every
5 s, and cloud sync on to a local cloud. Every minute: latency, CPU, memory,
threads, open files, database size; every 10 minutes the JVM heap right after a
full GC (the honest leak signal: resident memory of a JVM mostly just grows to
its limit).
"""
from __future__ import annotations

import os
import random
import secrets
import threading
import time
import uuid

from . import store as store_lt
from .cloud import Cloud
from .common import (PORTS, WORK, Client, Recorder, Sampler, fresh_dir, jvm_heap_after_gc, jvm_threads, log,
                     open_files, pct, save_result, start_store)


def slope_per_hour(points: list[tuple[float, float]]) -> float:
    """Least-squares slope of (minutes, value) points, per hour."""
    if len(points) < 3:
        return 0.0
    n = len(points)
    mx = sum(p[0] for p in points) / n
    my = sum(p[1] for p in points) / n
    den = sum((p[0] - mx) ** 2 for p in points) or 1
    return round(sum((p[0] - mx) * (p[1] - my) for p in points) / den * 60, 2)


def run(minutes: float, devices: int = 5, sale_every_s: float = 20) -> dict:
    data = fresh_dir(os.path.join(WORK, "soak"))
    spec = {"venue": "soak-store", "name": "Soak Store", "key": secrets.token_hex(24), "kind": "restaurant",
            "install": str(uuid.uuid4())}
    cloud = Cloud(cpus="1", memory="1g")
    cloud.start_pg()
    cloud.start_api([spec])
    st = start_store("soak", "plateau", PORTS["soak"], data, {
        "POS_KITCHEN_PRINTING": "on", "CLOUD_SYNC_URL": cloud.base, "CLOUD_SYNC_API_KEY": spec["key"],
        "CLOUD_SYNC_INTERVAL_SECONDS": "10"})
    base = f"http://127.0.0.1:{PORTS['soak']}"
    stop = threading.Event()
    rec = Recorder()
    try:
        scn = store_lt.Restaurant(base)
        pins = store_lt.staff_pins(base, devices)
        clients = []
        for i in range(devices):
            c = Client(base, rec)
            c.login(pins[i])
            clients.append((c, scn.device_state(i, devices)))

        def device(i):
            rng = random.Random(i)
            c, dev = clients[i]
            next_sale = time.time() + rng.random() * sale_every_s
            while not stop.is_set():
                if time.time() >= next_sale:
                    next_sale += sale_every_s
                    t0 = time.perf_counter()
                    try:
                        scn.sale(c, rng, dev)
                        rec.sale((time.perf_counter() - t0) * 1000)
                    except Exception:
                        rec.count("(failed sales)")
                        store_lt.recover(c, dev, rec)
                    dev.pop("cid", None)
                else:
                    try:
                        c.call("GET", "/zones", name="GET /zones (floor refresh)")
                    except Exception:
                        pass
                    stop.wait(5)

        threads = [threading.Thread(target=device, args=(i,), daemon=True) for i in range(devices)]
        threads += scn.background(rec, stop)
        for t in threads:
            t.start()
        sampler = Sampler(st.pid, every=5)
        sampler.start()
        samples, heap = [], []
        t_start = time.time()

        log(f"soak: {minutes:.0f} min, {devices} devices, one sale every {sale_every_s:.0f}s each "
            f"({devices * 60 / sale_every_s:.0f} sales/min), sync on")
        minute = 0
        while time.time() - t_start < minutes * 60:
            stop.wait(60)
            minute += 1
            with rec.lock:  # this minute's latencies, then start a new window
                lat = [x for v in rec.lat.values() for x in v]
                sales = rec.sales
                rec.lat = {}
            s = sampler.samples[-1] if sampler.samples else (0, 0, 0)
            db = os.path.join(data, "pos.db")
            row = {"minute": minute, "sales_total": sales, "p50": round(pct(lat, 50), 1),
                   "p95": round(pct(lat, 95), 1), "p99": round(pct(lat, 99), 1),
                   "requests": len(lat), "cpu_pct": s[1], "rss_mb": round(s[2], 1),
                   "db_mb": round((os.path.getsize(db) + (os.path.getsize(db + "-wal") if os.path.exists(db + "-wal") else 0)) / 1e6, 2)}
            if minute % 10 == 0 or minute == 1:
                row["heap_after_gc_mb"] = jvm_heap_after_gc(st.pid)
                row["threads"] = jvm_threads(st.pid)
                row["open_files"] = open_files(st.pid)
                if row["heap_after_gc_mb"] is not None:
                    heap.append((minute, row["heap_after_gc_mb"]))
                log(f"soak {minute} min: {sales} sales, p95 {row['p95']} ms, RSS {row['rss_mb']} MB, "
                    f"heap after GC {row['heap_after_gc_mb']} MB, threads {row['threads']}, files {row['open_files']}, "
                    f"DB {row['db_mb']} MB")
            samples.append(row)
        stop.set()
        for t in threads:
            t.join(30)
        sampler.stop()
        in_cloud = int(cloud.one(f"select count(*) from checks where venue_id='soak-store' and status='CLOSED'") or 0)
        first = next((r for r in samples if r.get("threads")), {})
        last = next((r for r in reversed(samples) if r.get("threads")), {})
        result = {
            "minutes": minutes, "devices": devices, "sales": rec.sales,
            "failed_sales": rec.errors.get("(failed sales)", 0),
            "errors": sum(v for k, v in rec.errors.items() if not k.startswith("(")),
            "sales_in_cloud": in_cloud,
            "heap_slope_mb_per_hour": slope_per_hour(heap[1:] if len(heap) > 3 else heap),
            "rss_slope_mb_per_hour": slope_per_hour([(r["minute"], r["rss_mb"]) for r in samples[len(samples) // 4:]]),
            "p95_first_10min": round(sum(r["p95"] for r in samples[:10]) / max(1, len(samples[:10])), 1),
            "p95_last_10min": round(sum(r["p95"] for r in samples[-10:]) / max(1, len(samples[-10:])), 1),
            "threads_start_end": [first.get("threads"), last.get("threads")],
            "open_files_start_end": [first.get("open_files"), last.get("open_files")],
            "samples": samples,
        }
        log(f"soak: {result['sales']} sales, {result['failed_sales']} failed, {in_cloud} in the cloud; heap after GC "
            f"{result['heap_slope_mb_per_hour']} MB/hour, p95 {result['p95_first_10min']} → {result['p95_last_10min']} ms")
        save_result("soak", result)
        return result
    finally:
        stop.set()
        st.stop()
        cloud.stop()
