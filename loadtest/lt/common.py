"""Shared pieces of the load driver: a keep-alive HTTP client that times every
call, a thread-safe recorder, percentiles, a CPU/memory sampler, and helpers
to start and stop the processes under test on their own ports and data dirs.

Standard library only, so `scripts/load-test.sh` runs on a bare python3.
"""
from __future__ import annotations

import http.client
import json
import os
import shutil
import signal
import socket
import subprocess
import threading
import time
import urllib.parse
from dataclasses import dataclass, field

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORK = os.environ.get("LOADTEST_DIR") or os.path.join(REPO, ".loadtest")
RESULTS = os.path.join(WORK, "results")
STORE_JAR = os.path.join(REPO, "server", "build", "libs", "pos-server-all.jar")
API_JAR = os.path.join(REPO, "cloud", "api", "build", "libs", "api-all.jar")

# Ports the load test may use. The live demo stores (:8080, :8082), the gas
# demo (:8084-8086) and the local cloud (:8081, :3000) are never touched.
FORBIDDEN_PORTS = {8080, 8081, 8082, 8084, 8085, 8086, 3000, 5432}
PORTS = {
    "restaurant": 18480, "retail": 18482, "gas": 18484, "fdc": 18486,
    "soak": 18488, "backlog": 18490, "proxy": 18491, "api": 18181, "pg": 55433,
}


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


# ---------------------------------------------------------------- statistics

def pct(values: list[float], p: float) -> float:
    """Nearest-rank percentile (p in 0..100) of a list; 0 when empty."""
    if not values:
        return 0.0
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(round(p / 100.0 * len(s) + 0.5)) - 1))
    return s[k]


def summary(values: list[float]) -> dict:
    return {
        "n": len(values),
        "p50": round(pct(values, 50), 2),
        "p95": round(pct(values, 95), 2),
        "p99": round(pct(values, 99), 2),
        "max": round(max(values), 2) if values else 0.0,
        "mean": round(sum(values) / len(values), 2) if values else 0.0,
    }


class Recorder:
    """Latencies (ms) per endpoint name, errors per endpoint, completed sales."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.lat: dict[str, list[float]] = {}
        self.errors: dict[str, int] = {}
        self.error_samples: list[str] = []
        self.sales = 0
        self.sale_ms: list[float] = []
        self.started = time.time()

    def record(self, name: str, ms: float, ok: bool, detail: str = "") -> None:
        with self.lock:
            self.lat.setdefault(name, []).append(ms)
            if not ok:
                self.errors[name] = self.errors.get(name, 0) + 1
                if len(self.error_samples) < 20:
                    self.error_samples.append(f"{name}: {detail[:200]}")

    def count(self, name: str) -> None:
        with self.lock:
            self.errors[name] = self.errors.get(name, 0) + 1

    def sale(self, ms: float) -> None:
        with self.lock:
            self.sales += 1
            self.sale_ms.append(ms)

    def snapshot(self, duration_s: float) -> dict:
        with self.lock:
            endpoints = {k: summary(v) | {"errors": self.errors.get(k, 0)} for k, v in sorted(self.lat.items())}
            allv = [x for v in self.lat.values() for x in v]
            return {
                "duration_s": round(duration_s, 1),
                "sales": self.sales,
                "sales_per_min": round(self.sales / duration_s * 60, 1) if duration_s else 0,
                "requests": len(allv),
                "requests_per_s": round(len(allv) / duration_s, 1) if duration_s else 0,
                "errors": sum(v for k, v in self.errors.items() if not k.startswith("(")),
                "failed_sales": self.errors.get("(failed sales)", 0),
                "error_samples": list(self.error_samples),
                "all": summary(allv),
                "sale": summary(self.sale_ms),
                "endpoints": endpoints,
            }


# ---------------------------------------------------------------- HTTP

class HttpError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status
        self.body = body


class Client:
    """One device: a persistent connection, its own bearer token, every call timed."""

    def __init__(self, base: str, rec: Recorder | None = None, timeout: float = 30.0):
        u = urllib.parse.urlparse(base)
        self.host, self.port = u.hostname, u.port or 80
        self.rec = rec
        self.timeout = timeout
        self.token: str | None = None
        self.headers: dict[str, str] = {}
        self.conn: http.client.HTTPConnection | None = None

    def _conn(self) -> http.client.HTTPConnection:
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        return self.conn

    def close(self) -> None:
        if self.conn is not None:
            self.conn.close()
            self.conn = None

    def raw(self, method: str, path: str, body: bytes | None = None, headers: dict | None = None):
        h = {"Content-Type": "application/json", "Connection": "keep-alive"} | self.headers
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        if headers:
            h |= headers
        for attempt in (1, 2):
            try:
                c = self._conn()
                c.request(method, path, body=body, headers=h)
                r = c.getresponse()
                data = r.read()
                return r.status, data, r
            except (http.client.RemoteDisconnected, BrokenPipeError, ConnectionResetError, http.client.CannotSendRequest):
                # a keep-alive connection the server closed: reconnect once
                self.close()
                if attempt == 2:
                    raise
        raise RuntimeError("unreachable")

    def call(self, method: str, path: str, body=None, name: str | None = None, expect_json: bool = True):
        name = name or f"{method} {path}"
        payload = json.dumps(body).encode() if body is not None else (b"{}" if method == "POST" else None)
        t0 = time.perf_counter()
        try:
            status, data, _ = self.raw(method, path, payload)
        except Exception as e:  # timeouts, refused connections
            ms = (time.perf_counter() - t0) * 1000
            if self.rec:
                self.rec.record(name, ms, False, repr(e))
            self.close()
            raise
        ms = (time.perf_counter() - t0) * 1000
        ok = status < 400
        if self.rec:
            self.rec.record(name, ms, ok, f"{status} {data[:200]!r}")
        if not ok:
            raise HttpError(status, data.decode(errors="replace"))
        if not expect_json or not data:
            return data
        return json.loads(data)

    def login(self, pin: str = "1234") -> dict:
        r = self.call("POST", "/login", {"pin": pin}, name="POST /login")
        self.token = r["token"]
        return r


def wait_http(url: str, seconds: float = 60) -> bool:
    u = urllib.parse.urlparse(url)
    end = time.time() + seconds
    while time.time() < end:
        try:
            c = http.client.HTTPConnection(u.hostname, u.port, timeout=2)
            c.request("GET", u.path or "/")
            if c.getresponse().status < 500:
                return True
        except OSError:
            pass
        time.sleep(0.5)
    return False


def port_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("127.0.0.1", port)) != 0


def check_port(port: int) -> None:
    if port in FORBIDDEN_PORTS:
        raise SystemExit(f"refusing to use port {port}: it belongs to a live demo service")
    if not port_free(port):
        raise SystemExit(f"port {port} is already in use; stop whatever is on it (a previous load test?) "
                         f"or run `scripts/load-test.sh clean`")


# ---------------------------------------------------------------- processes

_LIVE: list["Proc"] = []


def stop_all() -> None:
    """Stop every process this run started (atexit, Ctrl-C, SIGTERM)."""
    for p in list(_LIVE):
        p.stop()


def _pidfile(name: str) -> str:
    d = os.path.join(WORK, "pids")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, f"{name}.pid")


def register(proc: "Proc") -> "Proc":
    _LIVE.append(proc)
    with open(_pidfile(proc.name), "w") as f:
        f.write(str(proc.pid))
    return proc


def clean() -> None:
    """Kill processes a crashed earlier run left behind (by their pid files)."""
    d = os.path.join(WORK, "pids")
    if not os.path.isdir(d):
        return
    for fn in os.listdir(d):
        try:
            pid = int(open(os.path.join(d, fn)).read().strip())
            os.kill(pid, signal.SIGTERM)
            log(f"stopped leftover {fn[:-4]} (pid {pid})")
        except (ValueError, ProcessLookupError, PermissionError):
            pass
        os.remove(os.path.join(d, fn))


@dataclass
class Proc:
    name: str
    popen: subprocess.Popen
    log_path: str
    port: int | None = None
    extra: dict = field(default_factory=dict)

    @property
    def pid(self) -> int:
        return self.popen.pid

    def stop(self, timeout: float = 20) -> None:
        if self in _LIVE:
            _LIVE.remove(self)
            try:
                os.remove(_pidfile(self.name))
            except OSError:
                pass
        if self.popen.poll() is not None:
            return
        self.popen.send_signal(signal.SIGTERM)
        try:
            self.popen.wait(timeout)
        except subprocess.TimeoutExpired:
            self.popen.kill()
            self.popen.wait(5)


def fresh_dir(path: str) -> str:
    if os.path.exists(path):
        shutil.rmtree(path)
    os.makedirs(path)
    return path


def start_store(name: str, venue: str, port: int, data_dir: str, extra_env: dict | None = None,
                heap: str = "512m", wait: float = 120) -> Proc:
    """The store server jar on [port] with its own database under [data_dir]."""
    check_port(port)
    os.makedirs(data_dir, exist_ok=True)
    env = os.environ.copy()
    for k in list(env):
        if k.startswith(("CLOUD_SYNC", "POS_", "FORECOURT", "STRIPE", "VENUE_TZ")):
            env.pop(k)
    env |= {
        "POS_VENUE": venue, "POS_PORT": str(port), "POS_DB": os.path.join(data_dir, "pos.db"),
        "POS_RECEIPTS_DIR": os.path.join(data_dir, "receipts"), "POS_BILLS_DIR": os.path.join(data_dir, "bills"),
        "POS_PHOTOS_DIR": os.path.join(data_dir, "photos"), "POS_PRINT_RECEIPTS": "digital",
        "POS_STAFF_APP_MFA": "off",
    } | (extra_env or {})
    # experiments: LT_STORE_ENV="KEY=value,KEY=value" reaches every store started
    for pair in filter(None, os.environ.get("LT_STORE_ENV", "").split(",")):
        k, _, v = pair.partition("=")
        env[k.strip()] = v.strip()
    log_path = os.path.join(data_dir, "store.log")
    lf = open(log_path, "ab")
    jvm = [f"-Xmx{heap}"]
    if os.environ.get("LT_JFR"):  # a CPU profile of the store, written when it stops
        jvm.append(f"-XX:StartFlightRecording=filename={os.path.join(data_dir, 'store.jfr')},settings=profile")
    p = subprocess.Popen(["java", *jvm, "-jar", STORE_JAR], cwd=data_dir, env=env,
                         stdout=lf, stderr=subprocess.STDOUT, start_new_session=True)
    proc = register(Proc(name, p, log_path, port))
    t0 = time.time()
    if not wait_http(f"http://127.0.0.1:{port}/health", wait):
        proc.stop()
        raise SystemExit(f"store {name} did not start; see {log_path}")
    proc.extra["startup_s"] = round(time.time() - t0, 2)
    return proc


def start_simulator(port: int, pumps: int, data_dir: str) -> Proc:
    check_port(port)
    os.makedirs(data_dir, exist_ok=True)
    env = os.environ.copy() | {"FDC_PORT": str(port), "FDC_PUMPS": str(pumps), "FDC_HOST": "127.0.0.1"}
    log_path = os.path.join(data_dir, "fdc.log")
    p = subprocess.Popen(["node", "server.js"], cwd=os.path.join(REPO, "forecourt", "simulator"), env=env,
                         stdout=open(log_path, "ab"), stderr=subprocess.STDOUT, start_new_session=True)
    proc = register(Proc("fdc", p, log_path, port))
    if not wait_http(f"http://127.0.0.1:{port}/healthz", 30):
        proc.stop()
        raise SystemExit(f"forecourt simulator did not start; see {log_path}")
    return proc


class Sampler(threading.Thread):
    """CPU (% of one core) and resident memory (MB) of a process, every [every] s."""

    def __init__(self, pid: int, every: float = 1.0):
        super().__init__(daemon=True)
        self.pid, self.every = pid, every
        self.samples: list[tuple[float, float, float]] = []  # (t, cpu%, rss MB)
        self._stop = threading.Event()

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                out = subprocess.run(["ps", "-o", "%cpu=,rss=", "-p", str(self.pid)],
                                     capture_output=True, text=True, timeout=5).stdout.split()
                if len(out) >= 2:
                    self.samples.append((time.time(), float(out[0]), float(out[1]) / 1024))
            except Exception:
                pass
            self._stop.wait(self.every)

    def stop(self) -> dict:
        self._stop.set()
        self.join(5)
        return self.result()

    def result(self) -> dict:
        cpu = [s[1] for s in self.samples]
        rss = [s[2] for s in self.samples]
        return {
            "cpu_avg_pct": round(sum(cpu) / len(cpu), 1) if cpu else 0,
            "cpu_max_pct": round(max(cpu), 1) if cpu else 0,
            "rss_max_mb": round(max(rss), 1) if rss else 0,
            "rss_end_mb": round(rss[-1], 1) if rss else 0,
        }


def jvm_heap_after_gc(pid: int) -> float | None:
    """Heap in use (MB) right after a forced full GC: the honest leak signal for a JVM."""
    if not shutil.which("jcmd"):
        return None
    try:
        subprocess.run(["jcmd", str(pid), "GC.run"], capture_output=True, timeout=30)
        out = subprocess.run(["jcmd", str(pid), "GC.heap_info"], capture_output=True, text=True, timeout=30).stdout
        # G1: " garbage-first heap   total 262144K, used 51234K [..."
        for line in out.splitlines():
            if "used" in line and "total" in line and "heap" in line:
                used = line.split("used")[1].split("K")[0].strip().replace(",", "")
                return round(int(used) / 1024, 1)
    except Exception:
        return None
    return None


def jvm_threads(pid: int) -> int | None:
    try:
        out = subprocess.run(["jcmd", str(pid), "Thread.print"], capture_output=True, text=True, timeout=30).stdout
        return sum(1 for line in out.splitlines() if line.startswith('"'))
    except Exception:
        return None


def open_files(pid: int) -> int | None:
    try:
        out = subprocess.run(["lsof", "-p", str(pid)], capture_output=True, text=True, timeout=30).stdout
        return max(0, len(out.splitlines()) - 1)
    except Exception:
        return None


def save_result(name: str, data: dict) -> str:
    os.makedirs(RESULTS, exist_ok=True)
    path = os.path.join(RESULTS, f"{name}.json")
    with open(path, "w") as f:
        json.dump(data, f, indent=1)
    log(f"results → {os.path.relpath(path, REPO)}")
    return path


def load_result(name: str) -> dict | None:
    path = os.path.join(RESULTS, f"{name}.json")
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return json.load(f)
