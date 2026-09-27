#!/usr/bin/env python3
"""Load-test driver. Use scripts/load-test.sh (it builds the jars first).

  python3 loadtest/run.py store [restaurant|retail|gas ...]   N devices against one store
  python3 loadtest/run.py bigdb                                a year of sales in one store
  python3 loadtest/run.py sync                                 many stores → the portal
  python3 loadtest/run.py soak                                 one store for hours
  python3 loadtest/run.py report                               docs/load-test-report.md

Everything runs locally on its own ports and data dirs (.loadtest/); the live
demo stores, the tablet and the hosted box are never touched.
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import atexit  # noqa: E402
import signal  # noqa: E402

from lt.common import clean, log, stop_all  # noqa: E402


def _terminate(signum, _frame):
    raise SystemExit(f"stopped by signal {signum}")


def main() -> None:
    atexit.register(stop_all)
    signal.signal(signal.SIGTERM, _terminate)
    signal.signal(signal.SIGHUP, _terminate)
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario", choices=["store", "bigdb", "sync", "soak", "report", "clean"])
    ap.add_argument("kinds", nargs="*", default=[])
    ap.add_argument("--quick", action="store_true", help="short runs (a smoke test of the suite)")
    ap.add_argument("--levels", default=os.environ.get("LT_LEVELS", "1,5,10,25"))
    ap.add_argument("--seconds", type=float, default=float(os.environ.get("LT_SECONDS", "60")))
    ap.add_argument("--soak-minutes", type=float, default=float(os.environ.get("LT_SOAK_MINUTES", "120")))
    ap.add_argument("--sales", type=int, default=int(os.environ.get("LT_BIGDB_SALES", "100000")))
    a = ap.parse_args()
    levels = [int(x) for x in a.levels.split(",")]
    seconds = 15 if a.quick else a.seconds

    if a.scenario == "clean":
        clean()
        return
    clean()
    if a.scenario == "store":
        from lt import store
        for kind in a.kinds or ["restaurant", "retail", "gas"]:
            store.run(kind, levels, seconds, warmup=5 if a.quick else 20)
    elif a.scenario == "bigdb":
        from lt import bigdb
        bigdb.run(a.kinds or ["restaurant", "retail"], 10_000 if a.quick else a.sales, seconds)
    elif a.scenario == "sync":
        from lt import sync
        sync.run(quick=a.quick)
    elif a.scenario == "soak":
        from lt import soak
        soak.run(5 if a.quick else a.soak_minutes)
    elif a.scenario == "report":
        from lt import report
        report.run()
    log("done")


if __name__ == "__main__":
    main()
