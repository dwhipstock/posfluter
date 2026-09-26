"""The local cloud for the sync tests: its own Postgres container and its own
cloud API process, on their own ports. Never the demo cloud (:8081) and never
the hosted box.

Postgres runs in Docker (the same pinned image as production) with 2 CPUs and
2 GB; the API runs from its jar with the production heap (-Xmx512m).
"""
from __future__ import annotations

import os
import secrets
import subprocess
import time

from .common import API_JAR, PORTS, REPO, WORK, Proc, check_port, log, register, wait_http

PG_IMAGE = "postgres:17-alpine@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193"
PG_NAME = "pos-loadtest-pg"


def docker(*args: str, check: bool = True, input: str | None = None, timeout: float = 3600) -> str:
    r = subprocess.run(["docker", *args], capture_output=True, text=True, input=input, timeout=timeout)
    if check and r.returncode != 0:
        raise SystemExit(f"docker {' '.join(args[:3])} failed: {r.stderr.strip()[:500]}")
    return r.stdout


class Cloud:
    def __init__(self, cpus: str = os.environ.get("LT_PG_CPUS", "2"), memory: str = os.environ.get("LT_PG_MEMORY", "2g")):
        self.cpus, self.memory = cpus, memory
        self.password = secrets.token_hex(16)  # this run only; never printed
        self.admin_email = "owner@loadtest.example"
        self.admin_password = secrets.token_hex(12)
        self.api: Proc | None = None
        self.api_port = PORTS["api"]
        self.base = f"http://127.0.0.1:{self.api_port}"

    # ------------------------------------------------------------ Postgres

    def start_pg(self) -> None:
        check_port(PORTS["pg"])
        docker("rm", "-f", PG_NAME, check=False)
        log(f"cloud: Postgres in Docker ({self.cpus} CPUs, {self.memory}) on :{PORTS['pg']}")
        docker("run", "-d", "--name", PG_NAME, "--cpus", self.cpus, "--memory", self.memory,
               "-p", f"127.0.0.1:{PORTS['pg']}:5432", "-e", f"POSTGRES_PASSWORD={self.password}",
               "-e", "POSTGRES_DB=pos_cloud", "--shm-size", "256m", PG_IMAGE)
        for _ in range(120):
            r = subprocess.run(["docker", "exec", PG_NAME, "pg_isready", "-U", "postgres", "-d", "pos_cloud"],
                               capture_output=True)
            if r.returncode == 0:
                time.sleep(1)
                return
            time.sleep(1)
        raise SystemExit("the load-test Postgres did not start")

    def sql(self, query: str, timeout: float = 3600) -> list[list[str]]:
        out = docker("exec", "-i", PG_NAME, "psql", "-U", "postgres", "-d", "pos_cloud", "-v", "ON_ERROR_STOP=1",
                     "-At", "-F", "\t", input=query, timeout=timeout)
        return [line.split("\t") for line in out.splitlines() if line.strip()]

    def one(self, query: str):
        rows = self.sql(query)
        return rows[0][0] if rows and rows[0] else None

    def stop_pg(self) -> None:
        docker("rm", "-f", PG_NAME, check=False)

    # ------------------------------------------------------------ the API

    def start_api(self, stores: list[dict]) -> None:
        """[stores]: dicts with venue, name, key, kind (restaurant | retail | gas)."""
        check_port(self.api_port)
        zone = {"restaurant": "America/New_York", "retail": "America/Los_Angeles", "gas": "America/Chicago"}
        cur = {"restaurant": "CAD", "retail": "USD", "gas": "USD"}
        country = {"restaurant": "CA", "retail": "US", "gas": "US"}
        env = {k: v for k, v in os.environ.items() if not k.startswith(("STORE", "DATABASE", "DB_", "ADMIN", "TOTP", "FX_"))}
        env |= {
            "DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{PORTS['pg']}/pos_cloud",
            "DB_USER": "postgres", "DB_PASSWORD": self.password, "PORT": str(self.api_port),
            "MIGRATIONS_DIR": os.path.join(REPO, "cloud", "migrations"),
            "ADMIN_EMAIL": self.admin_email, "ADMIN_PASSWORD": self.admin_password,
            "TOTP_REQUIRED": "false", "COOKIE_SECURE": "false",
            "VENUE_NAME": "Load Test Group", "VENUE_TZ": "America/New_York",
            "STORES": ",".join(f"{s['venue']}={s['name']}" for s in stores),
            "STORE_API_KEYS": ",".join(f"{s['venue']}={s['key']}" for s in stores),
            "STORE_ZONES": ",".join(f"{s['venue']}={zone[s['kind']]}" for s in stores),
            "STORE_CURRENCIES": ",".join(f"{s['venue']}={cur[s['kind']]}" for s in stores),
            "STORE_COUNTRIES": ",".join(f"{s['venue']}={country[s['kind']]}" for s in stores),
            "RETAIL_STORES": ",".join(s["venue"] for s in stores if s["kind"] != "restaurant"),
            "REPORTING_CURRENCY": "USD", "FX_CAD_USD": "0.73",
            "PORTAL_SESSION_IDLE_MINUTES": "600", "PORTAL_SESSION_MAX_HOURS": "24",
        }
        d = os.path.join(WORK, "cloud")
        os.makedirs(d, exist_ok=True)
        log_path = os.path.join(d, "api.log")
        heap = os.environ.get("LT_API_HEAP", "512m")
        jvm = [f"-Xmx{heap}", "-XX:+ExitOnOutOfMemoryError", f"-Xlog:gc:file={os.path.join(d, 'api-gc.log')}"]
        if os.environ.get("LT_JFR"):
            jvm.append(f"-XX:StartFlightRecording=filename={os.path.join(d, 'api.jfr')},settings=profile")
        p = subprocess.Popen(["java", *jvm, "-jar", API_JAR], cwd=d, env=env,
                             stdout=open(log_path, "ab"), stderr=subprocess.STDOUT, start_new_session=True)
        self.api = register(Proc("cloud-api", p, log_path, self.api_port))
        if not wait_http(self.base + "/health", 180):
            raise SystemExit(f"the load-test cloud API did not start; see {log_path}")
        log(f"cloud: API on :{self.api_port} ({len(stores)} stores, heap {heap})")

    def api_alive(self) -> bool:
        return self.api is not None and self.api.popen.poll() is None

    def restart_api(self, stores: list[dict]) -> None:
        if self.api:
            self.api.stop()
        self.start_api(stores)

    def stop(self) -> None:
        if self.api:
            self.api.stop()
        self.stop_pg()

    def sizes(self) -> dict:
        rows = self.sql("select relname, pg_total_relation_size(c.oid) from pg_class c join pg_namespace n "
                        "on n.oid = c.relnamespace where n.nspname='public' and c.relkind='r' "
                        "order by 2 desc limit 12")
        out = {r[0]: int(r[1]) for r in rows}
        out["(database)"] = int(self.one("select pg_database_size('pos_cloud')"))
        return out
