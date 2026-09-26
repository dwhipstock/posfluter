"""A store with a year of sales: how big the SQLite file gets, and whether the
store still answers as fast.

The history is grown from the real sales the store load test just rang up
(`.loadtest/store-<kind>/pos.db`): each of 365 days gets a slice of those sales
copied back in time, with fresh ids and every timestamp moved to that day, one
closed shift per day. Rows are the store's own (checks, lines, tenders, splits,
kitchen tickets, ID checks, fuel sales and the sync outbox), so the size per sale
is what the app writes. Then the store starts on the big file and takes the same
10-device load again, and the history screens are timed.
"""
from __future__ import annotations

import os
import shutil
import sqlite3
import time
import urllib.parse

from . import store as store_lt
from .common import PORTS, WORK, Client, fresh_dir, log, save_result, start_simulator, start_store, summary

# table → primary key, foreign keys (column → table), unique text columns, timestamps
TABLES = {
    "checks": ("id", {}, [], ["opened_at", "closed_at"]),
    "check_lines": ("id", {"check_id": "checks", "fuel_sale_id": "fuel_sales"}, [], ["created_at"]),
    "tenders": ("id", {"transaction_id": "checks", "bill_group_id": "bill_groups"}, [], ["created_at"]),
    "bill_groups": ("id", {"check_id": "checks"}, [], ["created_at"]),
    "bill_group_allocations": ("id", {"group_id": "bill_groups", "line_id": "check_lines"}, [], []),
    "age_checks": ("id", {"check_id": "checks"}, [], ["checked_at"]),
    "kitchen_tickets": ("id", {"check_id": "checks"}, ["ticket_id", "bump_id"], ["created_at", "bumped_at"]),
    "kitchen_ticket_items": ("id", {"ticket_id": "kitchen_tickets", "line_id": "check_lines"}, [], []),
    "kitchen_sent_lines": ("line_id", {"check_id": "checks", "line_id": "check_lines"}, [], ["updated_at"]),
    "kitchen_print_jobs": ("seq", {"ticket_id": "kitchen_tickets"}, ["job_id"], ["created_at", "printed_at"]),
    "fuel_sales": ("id", {"check_id": "checks", "line_id": "check_lines"}, ["fdc_trx_id", "fdc_auth_id"],
                   ["created_at", "completed_at", "settled_at"]),
    "sync_outbox": ("id", {}, ["event_id"], ["created_at"]),
}
# how a row belongs to a slice of checks: direct check column, or through a parent
OWNER = {
    "checks": "id", "check_lines": "check_id", "tenders": "transaction_id", "bill_groups": "check_id",
    "age_checks": "check_id", "kitchen_tickets": "check_id", "kitchen_sent_lines": "check_id",
    "fuel_sales": "check_id",
    "bill_group_allocations": ("group_id", "bill_groups"),
    "kitchen_ticket_items": ("ticket_id", "kitchen_tickets"),
    "kitchen_print_jobs": ("ticket_id", "kitchen_tickets"),
}


def columns(con, table):
    return [r[1] for r in con.execute(f'PRAGMA table_info("{table}")')]


def grow(db: str, target: int, days: int = 365) -> dict:
    """Copy slices of the template's sales back over [days] days until the store holds [target] closed sales."""
    con = sqlite3.connect(db)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=OFF")
    tables = {t: v for t, v in TABLES.items()
              if con.execute("select 1 from sqlite_master where type='table' and name=?", (t,)).fetchone()}
    tmax = {t: con.execute(f'select coalesce(max("{pk}"), 0) from "{t}"').fetchone()[0] for t, (pk, *_) in tables.items()}
    closed = [r[0] for r in con.execute("select id from checks where status='CLOSED' order by id")]
    if not closed:
        raise SystemExit(f"{db} has no sales to copy; run the store load test first")
    have = len(closed)
    need = max(0, target - have)
    per_day = max(1, -(-need // days))
    # the outbox has no check column: copy the sales' share of it, never the
    # catalog and staff snapshots written before the first sale
    first_sale = con.execute("select min(opened_at) from checks").fetchone()[0]
    sale_events = [r[0] for r in con.execute("select id from sync_outbox where created_at >= ? order by id", (first_sale,))]
    events_per_check = len(sale_events) / max(1, len(closed))
    shift_cols = columns(con, "shifts")
    t0 = time.time()
    copied = 0
    for day in range(1, days + 1):
        if copied >= need:
            break
        # the slice: per_day consecutive template checks (wrapping around)
        start = closed[(day * per_day) % have]
        ids = [c for c in closed if c >= start][:per_day]
        if len(ids) < per_day:
            ids += closed[: per_day - len(ids)]
        lo_hi = ",".join(map(str, ids))
        r = day  # copy number: every id moves up by r × the template's largest id
        secs = day * 86400
        shift_id = con.execute(
            "insert into shifts (status, opened_at, opened_by, opening_float_cents, closed_at, closed_by, "
            "closing_count_cents, expected_cash_cents, over_short_cents) values "
            "('CLOSED', strftime('%Y-%m-%dT%H:%M:%fZ','now',?), 'manager', 20000, strftime('%Y-%m-%dT%H:%M:%fZ','now',?), "
            "'manager', 20000, 20000, 0)", (f"-{secs + 36000} seconds", f"-{secs - 36000} seconds")).lastrowid
        for t, (pk, fks, uniq, times) in tables.items():
            cols = columns(con, t)
            exprs = []
            for c in cols:
                if c == pk:
                    exprs.append(f'"{c}" + {r * (tmax[t] + 1)}')
                elif c in fks and fks[c] in tmax:
                    exprs.append(f'CASE WHEN "{c}" IS NULL THEN NULL ELSE "{c}" + {r * (tmax[fks[c]] + 1)} END')
                elif c in uniq:
                    exprs.append(f"CASE WHEN \"{c}\" IS NULL THEN NULL ELSE \"{c}\" || '-h{r}' END")
                elif c in times:
                    exprs.append(f"CASE WHEN \"{c}\" IS NULL THEN NULL ELSE strftime('%Y-%m-%dT%H:%M:%fZ', \"{c}\", '-{secs} seconds') END")
                elif t == "checks" and c == "shift_id":
                    exprs.append(str(shift_id))
                else:
                    exprs.append(f'"{c}"')
            if t == "sync_outbox":
                k = max(1, int(round(len(ids) * events_per_check)))
                at = (closed.index(ids[0]) * len(sale_events)) // len(closed)
                window = sale_events[at:at + k] or sale_events[:k]
                where = f"id >= {window[0]} and id <= {window[-1]}"
            elif isinstance(OWNER.get(t), tuple):
                col, parent = OWNER[t]
                ppk = tables[parent][0]
                where = f'"{col}" in (select "{ppk}" from "{parent}" where "{OWNER[parent]}" in ({lo_hi}))'
            else:
                where = f'"{OWNER[t]}" in ({lo_hi})'
            where += f' and "{pk}" <= {tmax[t]}'  # only template rows, never earlier copies
            collist = ",".join(f'"{c}"' for c in cols)
            con.execute(f'insert into "{t}" ({collist}) select {",".join(exprs)} from "{t}" where {where}')
        copied += len(ids)
        if day % 60 == 0:
            con.commit()
            log(f"  … {day} days, {have + copied} sales")
    if "fuel_sales" in tables:
        # the simulator starts again from T-000001; a real controller never reuses
        # its transaction ids, so neither may the history
        con.execute("update fuel_sales set fdc_trx_id = fdc_trx_id || '-old' "
                    "where fdc_trx_id is not null and fdc_trx_id not like '%-h%'")
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    return {"template_sales": have, "copied": copied, "seconds": round(time.time() - t0, 1)}


def time_calls(c: Client, calls: list[tuple[str, str]], repeat: int = 5) -> dict:
    out = {}
    for name, path in calls:
        ms = []
        for _ in range(repeat):
            t0 = time.perf_counter()
            try:
                c.call("GET", path, name=name)
                ms.append((time.perf_counter() - t0) * 1000)
            except Exception as e:
                out[name] = {"error": str(e)[:200]}
                break
        else:
            out[name] = summary(ms)
    return out


def history_calls(kind: str) -> list[tuple[str, str]]:
    today = time.strftime("%Y-%m-%d")
    year_ago = time.strftime("%Y-%m-%d", time.localtime(time.time() - 365 * 86400))
    month_ago = time.strftime("%Y-%m-%d", time.localtime(time.time() - 30 * 86400))
    calls = [
        ("GET /checks/recent (refund picker)", "/checks/recent"),
        ("GET /shifts/current/report (X report)", "/shifts/current/report"),
        ("GET /reports/range today", f"/reports/range?from={today}&to={today}"),
        ("GET /reports/range 30 days", f"/reports/range?from={month_ago}&to={today}"),
        ("GET /reports/range a year", f"/reports/range?from={year_ago}&to={today}"),
        ("GET /zones", "/zones"),
        ("GET /items", "/items"),
    ]
    if kind != "restaurant":
        calls += [("GET /retail/top-sellers", "/retail/top-sellers"), ("GET /stock/expected", "/stock/expected"),
                  ("GET /items?q= (search)", "/items?q=" + urllib.parse.quote("ipa 6") + "&limit=20")]
    if kind == "gas":
        calls.append(("GET /forecourt", "/forecourt"))
    return calls


def run(kinds: list[str], target: int, seconds: float) -> dict:
    results = {}
    for kind in kinds:
        src = os.path.join(store_lt.store_dir(kind), "pos.db")
        if not os.path.exists(src):
            log(f"bigdb {kind}: no store-{kind} database yet; running a short store load first")
            store_lt.run(kind, [5], 30, warmup=5)
        data = fresh_dir(os.path.join(WORK, f"bigdb-{kind}"))
        db = os.path.join(data, "pos.db")
        shutil.copy(src, db)
        log(f"bigdb {kind}: growing a year of sales to {target:,} …")
        g = grow(db, target)
        s = store_lt.db_stats(db)
        size_mb = s["db_bytes"] / 1e6
        log(f"bigdb {kind}: {s['closed_sales']:,} sales, {size_mb:.0f} MB "
            f"({s['db_bytes'] / max(1, s['closed_sales']):.0f} bytes/sale, outbox {s.get('outbox_bytes', 0) / 1e6:.0f} MB) "
            f"in {g['seconds']}s")
        procs = []
        extra = {"POS_KITCHEN_PRINTING": "on"} if kind == "restaurant" else {}
        if kind == "gas":
            procs.append(start_simulator(PORTS["fdc"], 16, data))
            extra |= {"FORECOURT_URL": f"http://127.0.0.1:{PORTS['fdc']}", "FORECOURT_PUMPS": "16"}
        venue = {"restaurant": "plateau", "retail": "sage-poppy", "gas": "pronghorn"}[kind]
        st = start_store(f"bigdb-{kind}", venue, PORTS[kind], data, extra, wait=600)
        procs.append(st)
        try:
            base = f"http://127.0.0.1:{PORTS[kind]}"
            m = Client(base)
            m.login(store_lt.MANAGER)
            history = time_calls(m, history_calls(kind))
            m.close()
            for k, v in history.items():
                log(f"  {k}: p50 {v.get('p50')} ms, max {v.get('max')} ms {v.get('error', '')}")
            scn = {"restaurant": lambda: store_lt.Restaurant(base), "retail": lambda: store_lt.Retail(base, 10),
                   "gas": lambda: store_lt.Gas(base, 10, f"http://127.0.0.1:{PORTS['fdc']}")}[kind]()
            pins = store_lt.staff_pins(base, 10)
            store_lt.run_level(scn, 5, 10, st.pid, seed=98, pins=pins)  # warm-up
            lv = store_lt.run_level(scn, 10, seconds, st.pid, seed=10, pins=pins)
            log(f"bigdb {kind}: 10 devices on a year of sales → {lv['sales_per_min']} sales/min, "
                f"p50 {lv['all']['p50']} ms, p95 {lv['all']['p95']} ms, p99 {lv['all']['p99']} ms, errors {lv['errors']}")
            results[kind] = {"grow": g, "db": s, "startup_s": st.extra["startup_s"], "history": history, "load10": lv}
        finally:
            for p in procs:
                p.stop()
    save_result("bigdb", results)
    return results
