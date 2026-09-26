#!/usr/bin/env bash
# Load tests, all local: the store server jar, the forecourt simulator and a
# throwaway cloud (Postgres in Docker + the cloud API jar) on their own ports.
# Never the demo stores (:8080 :8082 :8084), the demo cloud (:8081), the tablet
# or the hosted box. Results in .loadtest/results/, report in
# docs/load-test-report.md (+ docs/load-test/*.png), stamped with the commit.
#
#   scripts/load-test.sh all                  every scenario, then the report (~3.5 h; the soak is 2 h)
#   scripts/load-test.sh quick                a short smoke run of every scenario, then the report
#   scripts/load-test.sh store [restaurant|retail|gas ...]
#   scripts/load-test.sh bigdb [restaurant|retail|gas ...]
#   scripts/load-test.sh sync
#   scripts/load-test.sh soak
#   scripts/load-test.sh report               the report from the results already there
#   scripts/load-test.sh clean                stop anything a crashed run left behind
#
# Knobs (env): LT_LEVELS=1,5,10,25 LT_SECONDS=60 LT_BIGDB_SALES=100000 LT_SYNC_SECONDS=60
# LT_PORTAL_DAYS=365 LT_PORTAL_SALES_PER_DAY=100 LT_SOAK_MINUTES=120 LT_JFR=1 (CPU profiles)
set -euo pipefail

# A Mac left alone goes to sleep and freezes every process for minutes, which
# looks exactly like a server stall: keep it awake for the whole run.
if [[ "$(uname)" == Darwin && -z "${LT_CAFFEINATED:-}" ]] && command -v caffeinate >/dev/null; then
  LT_CAFFEINATED=1 exec caffeinate -dimsu "$0" "$@"
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
WORK="${LOADTEST_DIR:-$REPO_ROOT/.loadtest}"
mkdir -p "$WORK"

die() { echo "ERROR: $*" >&2; exit 1; }
cmd="${1:-}"
[[ -n "$cmd" ]] || { sed -n '2,20p' "$0"; exit 1; }
shift || true

command -v java >/dev/null || die "java 17+ is needed"
command -v python3 >/dev/null || die "python3 is needed"

PY=python3
charts_python() {
  # matplotlib for the charts, in a private venv (the report works without it)
  if [[ ! -x "$WORK/venv/bin/python" ]]; then
    python3 -m venv "$WORK/venv" >/dev/null 2>&1 || return 0
  fi
  if ! "$WORK/venv/bin/python" -c "import matplotlib" 2>/dev/null; then
    "$WORK/venv/bin/pip" install -q matplotlib >/dev/null 2>&1 || { echo "(no matplotlib: the report will have no charts)"; return 0; }
  fi
  PY="$WORK/venv/bin/python"
}

build() {
  echo "Building the store server and the cloud API…"
  (cd server && ./gradlew -q --no-daemon buildFatJar)
  (cd cloud/api && ./gradlew -q --no-daemon buildFatJar)
}

needs_docker() { command -v docker >/dev/null && docker info >/dev/null 2>&1 || die "Docker must be running (the cloud's Postgres)"; }
needs_node() { command -v node >/dev/null || die "node 22+ runs the forecourt simulator"; }

run() { "$PY" loadtest/run.py "$@"; }

case "$cmd" in
  clean) run clean ;;
  report) charts_python; run report ;;
  store) needs_node; build; run store "$@" ;;
  bigdb) needs_node; build; run bigdb "$@" ;;
  sync) needs_docker; build; run sync ;;
  soak) needs_docker; build; run soak ;;
  all|quick)
    needs_node; needs_docker; build; charts_python
    q=(); [[ "$cmd" == quick ]] && q=(--quick)
    rm -rf "$WORK/results"
    run store "${q[@]}"
    run bigdb restaurant retail gas "${q[@]}"
    run sync "${q[@]}"
    run soak "${q[@]}"
    LT_QUICK=$([[ "$cmd" == quick ]] && echo 1 || echo "") run report
    ;;
  *) die "unknown scenario '$cmd' (all | quick | store | bigdb | sync | soak | report | clean)" ;;
esac
