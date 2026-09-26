#!/usr/bin/env bash
# LOCAL proof of "one portal per client": three client portal instances side
# by side on this Mac behind ONE shared edge proxy, exactly as on a server —
#
#   http://cpr.localhost:8088   Copper Lantern (Vieux-Port + Plateau, CAD, fr/en)
#   http://sp.localhost:8088    Sage & Poppy   (one bottle shop, USD, en/es)
#   http://pf.localhost:8088    Pronghorn      (one gas station, USD, en/es)
#
# each with its own Postgres, API and branded portal (the same two images), and
# optionally a throwaway desktop store per venue syncing to ITS client's portal.
# Nothing here touches .demo/plateau, .demo/sage-poppy*, the tablet, the hosted
# portal or AWS; it uses its own ports. The gas station's demo store is the
# same store server started with POS_VENUE=pronghorn; its demo sales come from
# scripts/demo-seed-fuel.py when that script is present.
#
#   scripts/demo-clients.sh up [--build] [--stores]   # --build: rebuild the local images
#                                                     # --stores: + demo stores, seeded once
#   scripts/demo-clients.sh status
#   scripts/demo-clients.sh down [--reset]            # --reset also deletes their data
#
# Sign in: owner@example.test, password = ADMIN_PASSWORD in
# .demo/clients/<client>/.env (authenticator sign-in is off for this local demo).
# *.localhost resolves to this Mac in Chrome/Safari/curl; the Java demo stores get
# the client names from a hosts file (jdk.net.hosts.file).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA="$REPO_ROOT/cloud/infra"
export CLIENTS_DIR="${DEMO_CLIENTS_DIR:-$REPO_ROOT/.demo/clients}"
STORES_DIR="$REPO_ROOT/.demo/clients-stores"
PORT="${DEMO_CLIENTS_PORT:-8088}"
API_IMAGE=pos-cloud-api:local
WEB_IMAGE=pos-cloud-web:local
JAR="$REPO_ROOT/server/build/libs/pos-server-all.jar"
CPR_HOST=cpr.localhost
SP_HOST=sp.localhost
PF_HOST=pf.localhost
CLIENTS="copperlantern sagepoppy pronghorn"
# venue  port  client  seed
STORE_TABLE="vieux-port 8093 copperlantern pub
plateau 8094 copperlantern pub
sage-poppy 8095 sagepoppy retail
pronghorn 8097 pronghorn fuel"

die() { echo "ERROR: $*" >&2; exit 1; }

cmd="${1:-}"; shift || true
BUILD=0; WITH_STORES=0; RESET=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) BUILD=1;; --stores) WITH_STORES=1;; --reset) RESET=1;;
    *) die "unknown option $1";;
  esac
  shift
done

store_pid() { lsof -nP -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1 || true; }

start_store() { # venue port client kind
  local venue="$1" port="$2" client="$3" kind="$4" dir file
  dir="$STORES_DIR/$venue"; file="$CLIENTS_DIR/$client/stores/$venue.env"
  [[ -f "$file" ]] || die "no store file $file"
  mkdir -p "$dir/receipts" "$dir/bills" "$dir/photos"
  if [[ -n "$(store_pid "$port")" ]]; then echo "  $venue: already up on :$port"; return; fi
  printf '127.0.0.1 localhost %s %s %s\n::1 localhost\n' "$CPR_HOST" "$SP_HOST" "$PF_HOST" > "$STORES_DIR/hosts"
  local url key portal zone
  url="$(grep '^CLOUD_SYNC_URL=' "$file" | cut -d= -f2-)"
  key="$(grep '^CLOUD_SYNC_API_KEY=' "$file" | cut -d= -f2-)"
  portal="$(grep '^REPORTING_PORTAL_URL=' "$file" | cut -d= -f2-)"
  zone="$(grep '^VENUE_TZ=' "$file" | cut -d= -f2-)"
  (
    cd "$dir"
    # the URL carries the proxy's port (new-client.sh reads proxy.env); the store
    # resolves the client's hostname through the hosts file
    env POS_VENUE="$venue" POS_PORT="$port" POS_DB="$dir/pos.db" \
      POS_RECEIPTS_DIR="$dir/receipts" POS_BILLS_DIR="$dir/bills" POS_PHOTOS_DIR="$dir/photos" \
      POS_PUBLIC_URL="http://127.0.0.1:$port" POS_STAFF_APP_MFA=off POS_PRINT_RECEIPTS=digital \
      VENUE_TZ="$zone" POS_LEGAL_AGE=21 \
      CLOUD_SYNC_URL="$url" CLOUD_SYNC_API_KEY="$key" CLOUD_SYNC_INTERVAL_SECONDS=5 \
      REPORTING_PORTAL_URL="$portal" \
      JAVA_TOOL_OPTIONS="-Djdk.net.hosts.file=$STORES_DIR/hosts" \
      nohup java -jar "$JAR" >> "$dir/store.log" 2>&1 &
    echo $! > "$dir/store.pid"
  )
  for ((i = 0; i < 60; i++)); do curl -fsS -m 2 "http://localhost:$port/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -fsS -m 2 "http://localhost:$port/health" >/dev/null 2>&1 || die "$venue did not come up — tail $dir/store.log"
  echo "  $venue: up on :$port → $client portal"
  if [[ ! -f "$dir/.seeded" ]]; then
    if [[ "$kind" == pub ]]; then STORE_URL="http://localhost:$port" python3 "$REPO_ROOT/scripts/demo-seed.py" >/dev/null
    elif [[ "$kind" == fuel ]]; then
      # the gas station seeds its own pumps and shop, when the store side ships a seeder
      if [[ -f "$REPO_ROOT/scripts/demo-seed-fuel.py" ]]; then
        STORE_URL="http://localhost:$port" python3 "$REPO_ROOT/scripts/demo-seed-fuel.py" >/dev/null || true
      else
        echo "  $venue: no scripts/demo-seed-fuel.py — started without demo sales"
      fi
    else
      STORE_URL="http://localhost:$port" python3 "$REPO_ROOT/scripts/demo-seed-retail.py" >/dev/null
      STORE_URL="http://localhost:$port" python3 "$REPO_ROOT/scripts/demo-seed-stock.py" >/dev/null || true
    fi
    touch "$dir/.seeded"; echo "  $venue: demo sales seeded"
  fi
}

stop_store() { # venue port
  local pid; pid="$(store_pid "$2")"
  [[ -n "$pid" ]] && { kill "$pid" 2>/dev/null || true; echo "  $1: stopped"; }
  rm -f "$STORES_DIR/$1/store.pid"
}

case "$cmd" in
  up)
    docker info >/dev/null 2>&1 || die "Docker is not running (colima start)"
    if [[ "$BUILD" == 1 ]] || ! docker image inspect "$API_IMAGE" >/dev/null 2>&1; then
      echo "Building $API_IMAGE…"; docker build -q -f "$INFRA/Dockerfile.api" -t "$API_IMAGE" "$REPO_ROOT/cloud" >/dev/null
    fi
    if [[ "$BUILD" == 1 ]] || ! docker image inspect "$WEB_IMAGE" >/dev/null 2>&1; then
      echo "Building $WEB_IMAGE…"; docker build -q -f "$INFRA/Dockerfile.web" -t "$WEB_IMAGE" "$REPO_ROOT/cloud" >/dev/null
    fi
    mkdir -p "$CLIENTS_DIR"
    if [[ ! -f "$CLIENTS_DIR/proxy.env" ]]; then
      umask 077
      printf 'HTTP_PORT=%s\nHTTPS_PORT=%s\nACME_EMAIL=local@example.test\nCADDY_DATA_VOLUME=pos-proxy-local_caddy_data\nEDGE_NETWORK=pos-edge\n' \
        "$PORT" "$((PORT + 360))" > "$CLIENTS_DIR/proxy.env"
    fi
    common=(--clients-dir "$CLIENTS_DIR" --admin-email owner@example.test --api-image "$API_IMAGE" --web-image "$WEB_IMAGE" --local --no-totp --up)
    "$INFRA/new-client.sh" copperlantern "$CPR_HOST" --name "Copper Lantern" --brand copperlantern \
      --store "vieux-port=Copper Lantern — Vieux-Port" --store "plateau=Copper Lantern — Plateau" \
      --zone America/Toronto --currency CAD --country CA "${common[@]}"
    echo
    "$INFRA/new-client.sh" sagepoppy "$SP_HOST" --name "Sage & Poppy" --brand sagepoppy \
      --store "sage-poppy=Sage & Poppy Bottle Shop" \
      --zone America/Los_Angeles --currency USD --country US --retail "${common[@]}"
    echo
    "$INFRA/new-client.sh" pronghorn "$PF_HOST" --name "Pronghorn Fuel & Market" --brand pronghorn \
      --store "pronghorn=Pronghorn Fuel & Market" \
      --zone America/Chicago --currency USD --country US --retail "${common[@]}"
    if [[ "$WITH_STORES" == 1 ]]; then
      [[ -f "$JAR" ]] || (cd "$REPO_ROOT/server" && ./gradlew -q --no-daemon buildFatJar)
      echo; echo "Demo stores (each syncs to its own client's portal):"
      while read -r venue port client kind; do start_store "$venue" "$port" "$client" "$kind"; done <<<"$STORE_TABLE"
    fi
    cat <<EOF

  Three client portals behind one edge proxy:
    Copper Lantern : http://$CPR_HOST:$PORT     (Vieux-Port + Plateau · CAD · fr/en)
    Sage & Poppy   : http://$SP_HOST:$PORT      (the bottle shop · USD · en/es)
    Pronghorn      : http://$PF_HOST:$PORT      (the gas station · USD · en/es)
  Sign in        : owner@example.test / ADMIN_PASSWORD in .demo/clients/<client>/.env
  Tear down      : scripts/demo-clients.sh down [--reset]
EOF
    ;;
  status)
    "$INFRA/proxy.sh" status || true
    for c in $CLIENTS; do [[ -x "$CLIENTS_DIR/$c/compose.sh" ]] && "$CLIENTS_DIR/$c/compose.sh" ps; done
    while read -r venue port _ _; do
      printf '  %-11s %s\n' "$venue" "$( [[ -n "$(store_pid "$port")" ]] && echo "up :$port" || echo down)"
    done <<<"$STORE_TABLE"
    ;;
  down)
    while read -r venue port _ _; do stop_store "$venue" "$port"; done <<<"$STORE_TABLE"
    for c in $CLIENTS; do
      [[ -x "$CLIENTS_DIR/$c/compose.sh" ]] || continue
      "$CLIENTS_DIR/$c/compose.sh" down
      if [[ "$RESET" == 1 ]]; then
        vol="$(grep '^PG_VOLUME=' "$CLIENTS_DIR/$c/.env" | cut -d= -f2-)"
        docker volume rm "$vol" >/dev/null 2>&1 && echo "  deleted $vol"
      fi
    done
    [[ -f "$CLIENTS_DIR/proxy.env" ]] && "$INFRA/proxy.sh" down || true
    if [[ "$RESET" == 1 ]]; then rm -rf "$CLIENTS_DIR" "$STORES_DIR"; echo "  local client settings and demo stores deleted"; fi
    ;;
  *) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 2;;
esac
