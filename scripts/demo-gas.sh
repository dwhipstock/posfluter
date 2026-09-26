#!/usr/bin/env bash
# The gas station on this Mac: Pronghorn Fuel & Market's forecourt simulator,
# its store, and the counter in Chrome. Everything local, no internet needed.
#
#   scripts/demo-gas.sh            # start (idempotent) + seed demo sales once + open Chrome
#   scripts/demo-gas.sh --no-seed  # start without demo sales
#   scripts/demo-gas.sh status
#   scripts/demo-gas.sh down [--reset]   # stop; --reset also deletes the store's data
#
#   http://localhost:8086/   the pump panel (the customers at the pumps)
#   http://localhost:8085/   the counter (the Flutter web build), PINs 1234 / 9999 / 5555
#   http://localhost:8084    the store (POS_VENUE=pronghorn, FORECOURT_URL → the simulator)
#
# The store syncs to a portal only when asked: PRONGHORN_SYNC_URL=… and
# PRONGHORN_API_KEY_FILE=<clients/pronghorn/stores/pronghorn.env> (what
# cloud/infra/new-client.sh writes), or the local cloud of scripts/demo-up.sh
# with PRONGHORN_SYNC_URL=http://localhost:8081 and STORE_API_KEY_PRONGHORN in
# .env.local. Never touches the other demo stores, the tablet or AWS.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
DIR="$REPO_ROOT/.demo/pronghorn"
SIM_PORT="${FDC_PORT:-8086}"
STORE_PORT="${PRONGHORN_PORT:-8084}"
WEB_PORT="${PRONGHORN_WEB_PORT:-8085}"
JAR="$REPO_ROOT/server/build/libs/pos-server-all.jar"
WEB_DIR="$REPO_ROOT/client/build/web-pronghorn"

die() { echo "ERROR: $*" >&2; exit 1; }
listening() { lsof -nP -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1 || true; }
wait_http() { # url tries
  for ((i = 0; i < ${2:-60}; i++)); do curl -fsS -m 2 "$1" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
stop_pid() { # file
  if [[ -f "$1" ]] && kill -0 "$(cat "$1")" 2>/dev/null; then kill "$(cat "$1")" 2>/dev/null || true; fi
  rm -f "$1"
}

cmd="${1:-up}"
case "$cmd" in
  down)
    stop_pid "$DIR/web.pid"; stop_pid "$DIR/store.pid"; stop_pid "$DIR/sim.pid"
    if [[ "${2:-}" == "--reset" ]]; then rm -rf "$DIR"; echo "Stopped; store data deleted."; else echo "Stopped (data kept in .demo/pronghorn)."; fi
    exit 0 ;;
  status)
    for pair in "simulator:$SIM_PORT" "store:$STORE_PORT" "counter:$WEB_PORT"; do
      name="${pair%%:*}"; port="${pair##*:}"
      if [[ -n "$(listening "$port")" ]]; then echo "  $name: up on :$port"; else echo "  $name: down"; fi
    done
    exit 0 ;;
  up|--no-seed) ;;
  *) die "unknown command $cmd (up | --no-seed | status | down [--reset])" ;;
esac
SEED=1; [[ "$cmd" == "--no-seed" || "${2:-}" == "--no-seed" ]] && SEED=0

command -v node >/dev/null || die "node (22+) runs the forecourt simulator"
command -v java >/dev/null || die "java (17+) runs the store"
command -v flutter >/dev/null || die "flutter builds the counter for the web"
mkdir -p "$DIR"

# 1. the forecourt simulator: the FDC and eight pumps
if [[ -n "$(listening "$SIM_PORT")" ]]; then
  echo "Forecourt simulator already up on :$SIM_PORT"
else
  echo "Starting the forecourt simulator on :${SIM_PORT}…"
  (cd forecourt/simulator && FDC_PORT="$SIM_PORT" nohup node server.js >> "$DIR/sim.log" 2>&1 & echo $! > "$DIR/sim.pid")
  wait_http "http://localhost:$SIM_PORT/healthz" 20 || die "simulator did not start — tail $DIR/sim.log"
fi

# 2. the store
if [[ -n "$(listening "$STORE_PORT")" ]]; then
  echo "Store already up on :$STORE_PORT"
else
  echo "Building the store server…"
  (cd server && ./gradlew -q --no-daemon buildFatJar)
  SYNC_URL="${PRONGHORN_SYNC_URL:-}"; SYNC_KEY=""
  if [[ -n "$SYNC_URL" ]]; then
    if [[ -n "${PRONGHORN_API_KEY_FILE:-}" ]]; then
      SYNC_KEY="$(grep '^CLOUD_SYNC_API_KEY=' "$PRONGHORN_API_KEY_FILE" | tail -1 | cut -d= -f2-)"
    elif [[ -f .env.local ]]; then
      SYNC_KEY="$(grep '^STORE_API_KEY_PRONGHORN=' .env.local | tail -1 | cut -d= -f2-)"
    fi
    [[ -n "$SYNC_KEY" && "$SYNC_KEY" != replace-with-* ]] || die "PRONGHORN_SYNC_URL needs a store key (PRONGHORN_API_KEY_FILE or STORE_API_KEY_PRONGHORN in .env.local)"
    echo "  syncing to $SYNC_URL"
  fi
  echo "Starting Pronghorn Fuel & Market on :${STORE_PORT}…"
  (
    cd "$DIR"
    env POS_VENUE=pronghorn POS_PORT="$STORE_PORT" POS_DB="$DIR/pos.db" \
      POS_RECEIPTS_DIR="$DIR/receipts" POS_BILLS_DIR="$DIR/bills" POS_PHOTOS_DIR="$DIR/photos" \
      POS_PRINT_RECEIPTS=digital POS_STAFF_APP_MFA=off \
      FORECOURT_URL="http://127.0.0.1:$SIM_PORT" \
      ${SYNC_URL:+CLOUD_SYNC_URL="$SYNC_URL" CLOUD_SYNC_API_KEY="$SYNC_KEY" CLOUD_SYNC_INTERVAL_SECONDS=10} \
      nohup java -jar "$JAR" >> "$DIR/store.log" 2>&1 &
    echo $! > "$DIR/store.pid"
  )
  wait_http "http://localhost:$STORE_PORT/health" 60 || die "store did not start — tail $DIR/store.log"
fi

# 3. demo sales, once per store database
if [[ "$SEED" == 1 && ! -f "$DIR/.seeded" ]]; then
  echo "Seeding demo sales (the simulator plays the customers)…"
  STORE_URL="http://localhost:$STORE_PORT" FDC_URL="http://localhost:$SIM_PORT" python3 scripts/demo-seed-fuel.py
  touch "$DIR/.seeded"
fi

# 4. the counter, built for the web and served locally
if [[ -n "$(listening "$WEB_PORT")" ]]; then
  echo "Counter already served on :$WEB_PORT"
else
  if [[ ! -f "$WEB_DIR/index.html" || -n "${REBUILD:-}" ]]; then
    # --no-web-resources-cdn: the renderer ships with the build, so the
    # counter loads with no internet (a store must work offline)
    echo "Building the counter for the web (SERVER_URL=http://localhost:${STORE_PORT})…"
    (cd client && flutter build web --release --no-web-resources-cdn --dart-define=SERVER_URL="http://localhost:$STORE_PORT" -o "$WEB_DIR" >/dev/null)
  fi
  (cd "$WEB_DIR" && nohup python3 -m http.server "$WEB_PORT" --bind 127.0.0.1 >> "$DIR/web.log" 2>&1 & echo $! > "$DIR/web.pid")
  wait_http "http://localhost:$WEB_PORT/" 20 || die "the counter did not start — tail $DIR/web.log"
fi

cat <<EOF

Pronghorn Fuel & Market is up:
  pump panel   http://localhost:$SIM_PORT/
  the counter  http://localhost:$WEB_PORT/   (PINs: manager 1234, cashier 9999, cajera 5555)
  store        http://localhost:$STORE_PORT   (logs: .demo/pronghorn/store.log)
Stop: scripts/demo-gas.sh down   (--reset deletes the store's data)
EOF
if [[ "$(uname)" == Darwin && -z "${NO_BROWSER:-}" ]]; then
  open -a "Google Chrome" "http://localhost:$SIM_PORT/" "http://localhost:$WEB_PORT/" 2>/dev/null || true
fi
