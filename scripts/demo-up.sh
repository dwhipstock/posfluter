#!/usr/bin/env bash
# Bring up the LOCAL three-store demo on this Mac, detached, and print the LAN
# URLs. Idempotent: safe to re-run; only rebuilds what changed.
# NOT for EC2 — that's cloud/infra/docker-compose.yml. Never touches AWS or the
# hosted portal.
#
#   1. cloud side in Docker (docker-compose.local.yml): Postgres + cloud API + portal,
#      tenant `copperlantern` with stores `vieux-port`, `plateau` and `sage-poppy`,
#      one API key each;
#   2. Copper Lantern — Plateau: the desktop store (DesktopMain.kt, POS_VENUE=plateau)
#      with its own SQLite DB under .demo/plateau/, syncing to the local API;
#   3. Sage & Poppy Bottle Shop: the US retail store, a second desktop store
#      (POS_VENUE=sage-poppy, port 8082, DB under .demo/sage-poppy/), USD, en/es;
#   4. Copper Lantern — Vieux-Port is the Android tablet: point it at this Mac with
#      scripts/tablet-cloud-config.sh (printed at the end).
#
#   scripts/demo-up.sh            # build + start + seed Plateau demo sales (once)
#   scripts/demo-up.sh --no-seed  # build + start, skip demo-sale generation
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

COMPOSE_FILE="docker-compose.local.yml"
# compose project (volume prefix); override to run a throwaway copy side by side
PROJECT="${POS_DEMO_PROJECT:-pos-local}"
ENV_FILE=".env.local"
PLATEAU_DIR="$REPO_ROOT/.demo/plateau"
# staff web app sign-in: off = PIN only for the demo stores started here
# (the product default is on). POS_STAFF_APP_MFA=on scripts/demo-up.sh to demo TOTP.
STAFF_APP_MFA="${POS_STAFF_APP_MFA:-off}"
PLATEAU_PORT="${PLATEAU_PORT:-8080}"
SAGE_POPPY_DIR="$REPO_ROOT/.demo/sage-poppy"
# not 8081: that's the cloud API
SAGE_POPPY_PORT="${SAGE_POPPY_PORT:-8082}"
SEED=1
[[ "${1:-}" == "--no-seed" ]] && SEED=0

# --- compose CLI detection (v2 plugin preferred, standalone fallback) ------------
if docker compose version >/dev/null 2>&1; then
  dc() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
  dc() { docker-compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
else
  echo "ERROR: neither 'docker compose' nor 'docker-compose' is available." >&2
  echo "Install Docker Desktop, or: brew install colima docker docker-compose && colima start" >&2
  exit 1
fi

# --- prerequisites ---------------------------------------------------------------
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon not reachable. If using colima: colima start" >&2
  exit 1
fi
command -v java >/dev/null 2>&1 || { echo "ERROR: java (17+) is needed for the Plateau desktop store." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "ERROR: openssl is needed to mint store keys." >&2; exit 1; }

# --- env file + per-store keys ---------------------------------------------------
set_env() { # set_env KEY VALUE — replace or append in $ENV_FILE (BSD/GNU sed)
  if grep -q "^$1=" "$ENV_FILE"; then
    sed -i.bak "s|^$1=.*|$1=$2|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
  else
    echo "$1=$2" >> "$ENV_FILE"
  fi
}
env_get() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }

if [[ ! -f "$ENV_FILE" ]]; then
  echo "No $ENV_FILE found — creating one from .env.local.example."
  cp .env.local.example "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo ">>> Review $ENV_FILE (DB_PASSWORD, ADMIN_PASSWORD) before a real demo. <<<"
fi
for key in STORE_API_KEY STORE_API_KEY_PLATEAU STORE_API_KEY_SAGE_POPPY; do
  current="$(env_get "$key")"
  if [[ -z "$current" || "$current" == replace-with-* ]]; then
    set_env "$key" "$(openssl rand -hex 32)"
    echo "Generated a fresh $key."
  fi
done
STORE_API_KEY_PLATEAU="$(env_get STORE_API_KEY_PLATEAU)"
STORE_API_KEY_SAGE_POPPY="$(env_get STORE_API_KEY_SAGE_POPPY)"
VENUE_TZ="$(env_get VENUE_TZ)"; VENUE_TZ="${VENUE_TZ:-America/New_York}"

# --- detect the Mac's LAN IP -----------------------------------------------------
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
[[ -z "$LAN_IP" ]] && LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
if [[ -z "$LAN_IP" ]]; then
  echo "WARN: could not auto-detect a LAN IP (en0/en1); the tablet will not reach this Mac." >&2
  LAN_IP="127.0.0.1"
fi
PUBLIC_URL="http://${LAN_IP}:${PLATEAU_PORT}"   # Plateau's scan-to-order / staff-app base
PORTAL_URL="http://${LAN_IP}:3000"
SYNC_URL="http://${LAN_IP}:8081"
set_env POS_PUBLIC_URL "$PUBLIC_URL"
set_env REPORTING_PORTAL_URL "$PORTAL_URL"
echo "LAN IP: ${LAN_IP}"

# --- the old Mac store container must not run next to the tablet ------------------
if [[ -n "$(dc -f "$COMPOSE_FILE" --profile mac-store ps -q store 2>/dev/null)" ]]; then
  echo "Stopping the legacy Mac store container (the tablet is Vieux-Port now)…"
  dc -f "$COMPOSE_FILE" --profile mac-store stop store >/dev/null
fi

# --- cloud side: build + start detached --------------------------------------------
echo "Building images and starting db + api + portal (detached)…"
dc -f "$COMPOSE_FILE" up -d --build db api web

wait_healthy() {
  local svc="$1" tries="${2:-60}" cid state
  cid="$(dc -f "$COMPOSE_FILE" ps -q "$svc")"
  if [[ -z "$cid" ]]; then echo "  $svc: no container?!" >&2; return 1; fi
  for ((i=0; i<tries; i++)); do
    state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
    case "$state" in
      healthy) echo "  $svc: healthy"; return 0 ;;
      exited|dead) echo "  $svc: $state" >&2; return 1 ;;
    esac
    sleep 2
  done
  echo "  $svc: not healthy after $((tries*2))s (state=$state)" >&2
  return 1
}
echo "Waiting for healthchecks…"
wait_healthy db 30 || true
wait_healthy api 90 || { echo "ERROR: api not healthy — docker compose -f $COMPOSE_FILE logs api" >&2; exit 1; }

# --- Plateau: the desktop store ------------------------------------------------------
mkdir -p "$PLATEAU_DIR"
PID_FILE="$PLATEAU_DIR/store.pid"
plateau_running() { [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

echo "Building the store server (Plateau)…"
(cd server && ./gradlew -q --no-daemon buildFatJar)
JAR="$REPO_ROOT/server/build/libs/pos-server-all.jar"

if plateau_running; then
  echo "Plateau store already running (pid $(cat "$PID_FILE")). Restart: scripts/demo-down.sh && scripts/demo-up.sh"
else
  if lsof -nP -iTCP:"$PLATEAU_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "ERROR: port $PLATEAU_PORT is busy. Free it or run with PLATEAU_PORT=<port>." >&2
    exit 1
  fi
  echo "Starting Copper Lantern — Plateau on :${PLATEAU_PORT}…"
  # optional Stripe test key for "Card (Stripe)": env wins, else the repo-root .env
  # (STRIPE_KEY=sk_test_...). Never printed. Unset → Stripe simply stays off.
  STRIPE_KEY="${STRIPE_KEY:-$(sed -n 's/^STRIPE_KEY=//p' "$REPO_ROOT/.env" 2>/dev/null | tail -1 | tr -d '"'"'"'\r')}"
  (
    cd "$PLATEAU_DIR"
    POS_VENUE=plateau \
    POS_PORT="$PLATEAU_PORT" \
    POS_DB="$PLATEAU_DIR/pos.db" \
    POS_RECEIPTS_DIR="$PLATEAU_DIR/receipts" \
    POS_BILLS_DIR="$PLATEAU_DIR/bills" \
    POS_PHOTOS_DIR="$PLATEAU_DIR/photos" \
    POS_PUBLIC_URL="$PUBLIC_URL" \
    POS_STAFF_APP_MFA="$STAFF_APP_MFA" \
    VENUE_TZ="$VENUE_TZ" \
    CLOUD_SYNC_URL="http://localhost:8081" \
    CLOUD_SYNC_API_KEY="$STORE_API_KEY_PLATEAU" \
    CLOUD_SYNC_INTERVAL_SECONDS=10 \
    REPORTING_PORTAL_URL="$PORTAL_URL" \
    STRIPE_KEY="$STRIPE_KEY" \
    STRIPE_LOCATION_ID="${STRIPE_LOCATION_ID:-}" \
    nohup java -jar "$JAR" >> "$PLATEAU_DIR/store.log" 2>&1 &
    echo $! > "$PID_FILE"
  )
fi
for ((i=0; i<60; i++)); do
  curl -fsS "http://localhost:${PLATEAU_PORT}/health" >/dev/null 2>&1 && break
  sleep 1
done
if curl -fsS "http://localhost:${PLATEAU_PORT}/health" >/dev/null 2>&1; then
  echo "  plateau: healthy"
else
  echo "ERROR: Plateau store did not come up — tail $PLATEAU_DIR/store.log" >&2
  exit 1
fi

# --- seed Plateau demo sales (once per Plateau database) ------------------------------
if [[ "$SEED" == "1" ]]; then
  if [[ -f "$PLATEAU_DIR/.demo-seeded" ]]; then
    echo "Plateau demo sales already seeded — skipping."
  elif STORE_URL="http://localhost:${PLATEAU_PORT}" python3 scripts/demo-seed.py; then
    touch "$PLATEAU_DIR/.demo-seeded"
  else
    echo "WARN: demo seed failed — re-run: STORE_URL=http://localhost:${PLATEAU_PORT} python3 scripts/demo-seed.py" >&2
  fi
fi

# --- Sage & Poppy: the US retail store (a second desktop store) ---------------------
mkdir -p "$SAGE_POPPY_DIR"
SP_PID_FILE="$SAGE_POPPY_DIR/store.pid"
if [[ -f "$SP_PID_FILE" ]] && kill -0 "$(cat "$SP_PID_FILE")" 2>/dev/null; then
  echo "Sage & Poppy store already running (pid $(cat "$SP_PID_FILE"))."
else
  if lsof -nP -iTCP:"$SAGE_POPPY_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "ERROR: port $SAGE_POPPY_PORT is busy. Free it or run with SAGE_POPPY_PORT=<port>." >&2
    exit 1
  fi
  echo "Starting Sage & Poppy Bottle Shop on :${SAGE_POPPY_PORT}…"
  (
    cd "$SAGE_POPPY_DIR"
    # no STRIPE_KEY: Stripe is Canada-only (CAD) for now; the store takes cash
    # and its own external card terminal
    POS_VENUE=sage-poppy \
    POS_PORT="$SAGE_POPPY_PORT" \
    POS_DB="$SAGE_POPPY_DIR/pos.db" \
    POS_RECEIPTS_DIR="$SAGE_POPPY_DIR/receipts" \
    POS_BILLS_DIR="$SAGE_POPPY_DIR/bills" \
    POS_PHOTOS_DIR="$SAGE_POPPY_DIR/photos" \
    POS_PUBLIC_URL="http://${LAN_IP}:${SAGE_POPPY_PORT}" \
    VENUE_TZ="America/Los_Angeles" \
    POS_STAFF_APP_MFA="$STAFF_APP_MFA" \
    POS_LEGAL_AGE="${POS_LEGAL_AGE:-21}" \
    CLOUD_SYNC_URL="http://localhost:8081" \
    CLOUD_SYNC_API_KEY="$STORE_API_KEY_SAGE_POPPY" \
    CLOUD_SYNC_INTERVAL_SECONDS=10 \
    REPORTING_PORTAL_URL="$PORTAL_URL" \
    nohup java -jar "$JAR" >> "$SAGE_POPPY_DIR/store.log" 2>&1 &
    echo $! > "$SP_PID_FILE"
  )
fi
for ((i=0; i<60; i++)); do
  curl -fsS "http://localhost:${SAGE_POPPY_PORT}/health" >/dev/null 2>&1 && break
  sleep 1
done
if curl -fsS "http://localhost:${SAGE_POPPY_PORT}/health" >/dev/null 2>&1; then
  echo "  sage-poppy: healthy"
else
  echo "ERROR: Sage & Poppy store did not come up — tail $SAGE_POPPY_DIR/store.log" >&2
  exit 1
fi
if [[ "$SEED" == "1" ]]; then
  if [[ -f "$SAGE_POPPY_DIR/.demo-seeded" ]]; then
    echo "Sage & Poppy demo sales already seeded — skipping."
  elif STORE_URL="http://localhost:${SAGE_POPPY_PORT}" python3 scripts/demo-seed-retail.py; then
    touch "$SAGE_POPPY_DIR/.demo-seeded"
  else
    echo "WARN: retail demo seed failed — re-run: STORE_URL=http://localhost:${SAGE_POPPY_PORT} python3 scripts/demo-seed-retail.py" >&2
  fi
  # stock history (deliveries, a refund, a shelf count) — after the sales, once
  if [[ -f "$SAGE_POPPY_DIR/.demo-stock-seeded" ]]; then
    echo "Sage & Poppy stock history already seeded — skipping."
  elif STORE_URL="http://localhost:${SAGE_POPPY_PORT}" python3 scripts/demo-seed-stock.py; then
    touch "$SAGE_POPPY_DIR/.demo-stock-seeded"
  else
    echo "WARN: stock demo seed failed — re-run: STORE_URL=http://localhost:${SAGE_POPPY_PORT} python3 scripts/demo-seed-stock.py" >&2
  fi
fi

# --- summary ---------------------------------------------------------------------
cat <<BANNER

============================================================
  Three-store demo is UP (two pubs in Montréal, one bottle shop in LA)
============================================================
  LAN IP         : ${LAN_IP}
  Owner portal   : ${PORTAL_URL}      (store picker: All stores / Vieux-Port / Plateau / Sage & Poppy)
  Cloud API      : ${SYNC_URL}/health
  Portal login   : $(env_get ADMIN_EMAIL)  /  (ADMIN_PASSWORD in $ENV_FILE)
                   First login enrolls TOTP — scan the QR in an authenticator app.

  Store sync (tenant copperlantern, one key per store, keys in $ENV_FILE):
    vieux-port   CLOUD_SYNC_URL=${SYNC_URL}   key: STORE_API_KEY          (Android tablet)
    plateau      CLOUD_SYNC_URL=http://localhost:8081   key: STORE_API_KEY_PLATEAU  (this Mac)
    sage-poppy   CLOUD_SYNC_URL=http://localhost:8081   key: STORE_API_KEY_SAGE_POPPY (this Mac)

  Plateau store  : http://${LAN_IP}:${PLATEAU_PORT}/health   (PIN 1234 manager, 9999 server)
                   staff app: http://${LAN_IP}:${PLATEAU_PORT}/staff-app   (MFA: ${STAFF_APP_MFA})
                   POS UI on the Mac: cd client && flutter run -d macos
                   log: .demo/plateau/store.log

  Sage & Poppy   : http://${LAN_IP}:${SAGE_POPPY_PORT}/health   (US retail · USD · en/es · 21+)
                   PINs: manager 1234, cashier 9999, Spanish-speaking cashier 5555
                   counter UI in Chrome: cd client && flutter run -d chrome \\
                     --dart-define=SERVER_URL=http://localhost:${SAGE_POPPY_PORT}
                   log: .demo/sage-poppy/store.log

  Vieux-Port tablet → this Mac (tablet on the same Wi-Fi, USB debugging on):
                   scripts/tablet-cloud-config.sh
                   (stages sync URL + key; the POS restarts and syncs here)

  Tear down      : scripts/demo-down.sh            (keep data)
                   scripts/demo-down.sh --reset    (wipe cloud db + Plateau store)
============================================================
BANNER
