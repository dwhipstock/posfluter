"""The portal with a big database: 200 stores × a year of sales.

The year is grown inside Postgres from the sales the sync test just ingested
(real projections of real store events): each store's day is a copy of its
own sales with new ids and the dates moved back, 365 days deep. Then the
owner's dashboard and reports are timed as the portal calls them: today, the
last 7 / 30 / 365 days, for all stores and for one store. The dashboard makes
its calls at once, like the browser does.
"""
from __future__ import annotations

import datetime
import http.client
import json
import os
import threading
import time

from .common import log, summary

TABLES = {
    # table: {column: expression}; ids move up by the copy number, times back by its day
    "checks": {"check_id": "t.check_id + o.off * 100000"},
    "check_lines": {"id": None, "check_id": "t.check_id + o.off * 100000", "line_id": "t.line_id + o.off::bigint * 10000000"},
    "check_tenders": {"check_id": "t.check_id + o.off * 100000", "tender_id": "t.tender_id + o.off::bigint * 10000000"},
    "fuel_sales": {"check_id": "t.check_id + o.off * 100000", "fuel_sale_id": "t.fuel_sale_id + o.off::bigint * 10000000"},
}
TIMES = {"opened_at", "closed_at", "tendered_at", "completed_at"}


def seed_year(cloud, days: int, per_day: int, chunk: int = 30) -> dict:
    """Copy each store's ingested sales back over [days] days, [per_day] a day."""
    t0 = time.time()
    cloud.sql("""
        DROP TABLE IF EXISTS lt_tmpl;
        CREATE TABLE lt_tmpl AS
          SELECT tenant_id, venue_id, check_id,
                 row_number() OVER (PARTITION BY tenant_id, venue_id ORDER BY check_id) AS rn,
                 count(*) OVER (PARTITION BY tenant_id, venue_id) AS tcount
          FROM checks WHERE status = 'CLOSED' AND check_id < 100000;
        CREATE INDEX ON lt_tmpl (tenant_id, venue_id, check_id);
    """)
    tmin = int(cloud.one("select coalesce(min(tcount), 0) from lt_tmpl") or 0)
    if tmin == 0:
        raise SystemExit("no ingested sales to grow the year from")
    copies = min(16, -(-per_day // tmin))
    cols = {t: [r[0] for r in cloud.sql(
        f"select column_name from information_schema.columns where table_name='{t}' order by ordinal_position")]
        for t in TABLES}
    for start in range(1, days + 1, chunk):
        end = min(days, start + chunk - 1)
        stmts = [f"""CREATE TEMP TABLE o AS
              SELECT m.tenant_id, m.venue_id, m.check_id, d, c, d * 16 + c AS off
              FROM lt_tmpl m CROSS JOIN generate_series({start}, {end}) d CROSS JOIN generate_series(0, {copies - 1}) c
              WHERE m.rn + c * m.tcount <= {per_day};
            CREATE INDEX ON o (tenant_id, venue_id, check_id);"""]
        for t, special in TABLES.items():
            names, exprs = [], []
            for c in cols[t]:
                if c in special and special[c] is None:
                    continue
                names.append(c)
                if c in special:
                    exprs.append(special[c])
                elif c in TIMES:
                    exprs.append(f"t.{c} - make_interval(days => o.d, mins => o.c * 7)")
                else:
                    exprs.append(f"t.{c}")
            stmts.append(f"INSERT INTO {t} ({', '.join(names)}) SELECT {', '.join(exprs)} FROM {t} t "
                         f"JOIN o USING (tenant_id, venue_id, check_id);")
        stmts.append("DROP TABLE o;")
        cloud.sql("BEGIN;\n" + "\n".join(stmts) + "\nCOMMIT;", timeout=7200)
        n = cloud.one("select count(*) from checks")
        log(f"  portal seed: days {start}-{end} done, {int(n):,} sales in the cloud ({time.time() - t0:.0f}s)")
    cloud.sql("ANALYZE;", timeout=7200)
    return {"days": days, "per_day": per_day, "seconds": round(time.time() - t0, 1),
            "sales": int(cloud.one("select count(*) from checks where status='CLOSED'")),
            "lines": int(cloud.one("select count(*) from check_lines")),
            "stores": int(cloud.one("select count(distinct venue_id) from checks"))}


class Portal:
    def __init__(self, cloud):
        self.cloud = cloud
        self.cookie = None

    def login(self):
        c = http.client.HTTPConnection("127.0.0.1", self.cloud.api_port, timeout=60)
        c.request("POST", "/v1/auth/login", body=json.dumps(
            {"email": self.cloud.admin_email, "password": self.cloud.admin_password}),
            headers={"Content-Type": "application/json"})
        r = c.getresponse()
        r.read()
        for k, v in r.getheaders():
            if k.lower() == "set-cookie" and v.startswith("pos_portal_session="):
                self.cookie = v.split(";")[0]
        c.close()
        if r.status != 200 or not self.cookie:
            raise SystemExit(f"portal login failed: {r.status}")

    def get(self, path: str, timeout: float = 300) -> tuple[int, float, int]:
        c = http.client.HTTPConnection("127.0.0.1", self.cloud.api_port, timeout=timeout)
        t0 = time.perf_counter()
        try:
            c.request("GET", path, headers={"Cookie": self.cookie})
            r = c.getresponse()
            body = r.read()
            return r.status, (time.perf_counter() - t0) * 1000, len(body)
        except Exception:
            return 0, (time.perf_counter() - t0) * 1000, 0
        finally:
            c.close()


DASHBOARD = ["summary", "payments", "hourly", "items"]
REPORTS = ["by-venue", "tax", "categories", "fuel", "shifts"]


def time_portal(cloud, stores: list[dict], quick: bool) -> dict:
    p = Portal(cloud)
    p.login()
    today = datetime.date.today()
    ranges = {"today": 0, "7 days": 6, "30 days": 29, "a year": 364}
    one = next(s["venue"] for s in stores if s["kind"] == "gas")
    scopes = {"all stores": "", "one store": f"&venue={one}"}
    out = {}
    for scope, q in scopes.items():
        for rname, back in ranges.items():
            frm = (today - datetime.timedelta(days=back)).isoformat()
            rng = f"from={frm}&to={today.isoformat()}{q}"
            key = f"{scope}, {rname}"
            # the dashboard: its four calls at once, as the browser makes them
            res = {}

            def fetch(name):
                res[name] = p.get(f"/v1/reports/{name}?{rng}")
            t0 = time.perf_counter()
            ths = [threading.Thread(target=fetch, args=(n,)) for n in DASHBOARD]
            for t in ths:
                t.start()
            for t in ths:
                t.join()
            wall = (time.perf_counter() - t0) * 1000
            entry = {"dashboard_ms": round(wall, 1),
                     "dashboard_errors": [f"{n}: {s}" for n, (s, _, _) in res.items() if s != 200],
                     "calls": {n: {"ms": round(ms, 1), "status": s, "bytes": b} for n, (s, ms, b) in res.items()}}
            for name in REPORTS:
                s, ms, b = p.get(f"/v1/reports/{name}?{rng}")
                entry["calls"][name] = {"ms": round(ms, 1), "status": s, "bytes": b}
            out[key] = entry
            errs = entry["dashboard_errors"] + [f"{n}: {c['status']}" for n, c in entry["calls"].items()
                                                if n in REPORTS and c["status"] != 200]
            log(f"  portal {key}: dashboard {wall / 1000:.2f}s"
                + (f"  errors: {', '.join(errs)}" if errs else ""))
            if not cloud.api_alive():
                log("  portal: the API died (out of memory?); restarting it")
                cloud.restart_api(stores)
                p.login()
    return out


def run(cloud, stores: list[dict], quick: bool = False) -> dict:
    days = 30 if quick else int(os.environ.get("LT_PORTAL_DAYS", "365"))
    per_day = 20 if quick else int(os.environ.get("LT_PORTAL_SALES_PER_DAY", "100"))
    log(f"portal: growing {days} days × {per_day} sales a day for every store …")
    seed = seed_year(cloud, days, per_day)
    log(f"portal: {seed['sales']:,} sales, {seed['lines']:,} lines in {seed['seconds']}s")
    sizes = cloud.sizes()
    timings = time_portal(cloud, stores, quick)
    # a second pass, now that Postgres has the pages it needs in memory
    warm = time_portal(cloud, stores, quick)
    return {"seed": seed, "sizes": sizes, "cold": timings, "warm": warm}
