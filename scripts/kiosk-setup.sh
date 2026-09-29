#!/usr/bin/env bash
# Copper Lantern — Express (the quick-serve counter) for a demo: start its
# store on this Mac, and/or install the self-order kiosk on an Android tablet
# and point it at the store.
#
#   scripts/kiosk-setup.sh --store                    # start the Express store on this Mac (:8098)
#   scripts/kiosk-setup.sh --stop                     # stop it
#   scripts/kiosk-setup.sh --kiosk <serial>           # install + launch the kiosk app on a tablet
#   scripts/kiosk-setup.sh --store --kiosk <serial>   # both
#   options: --port N (store port, default 8098)   --apk <path>   --no-build
#
# `adb devices` lists the serials; run --kiosk once per kiosk (many per store).
# The store keeps its data under .demo/express/ (gitignored). Kitchen tickets
# are on (the kitchen screen and the pickup board need them for "ready").
# When .env.local has STORE_API_KEY_EXPRESS and the local cloud is up
# (scripts/demo-up.sh), the store syncs to the Copper Lantern portal.
#
# An Express POS on a tablet instead of the Mac: the Copper Lantern POS app
# with store.venue=express in its store.properties (docs/quick-serve.md).
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PORT=8098 STORE=false STOP=false KIOSK="" BUILD=true
APK="$REPO_ROOT/client/build/app/outputs/flutter-apk/pos-kiosk-release.apk"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --store) STORE=true; shift;;
    --stop) STOP=true; shift;;
    --kiosk) KIOSK="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --apk) APK="$2"; shift 2;;
    --no-build) BUILD=false; shift;;
    -h|--help) sed -n '2,20p' "$0"; exit 0;;
    *) echo "unknown option $1 (see --help)" >&2; exit 2;;
  esac
done
[[ "$STORE" == true || "$STOP" == true || -n "$KIOSK" ]] || { sed -n '2,20p' "$0"; exit 2; }

DIR="$REPO_ROOT/.demo/express"
PID_FILE="$DIR/store.pid"
lan_ip() { ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 127.0.0.1; }
running() { [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }
env_local() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/.env.local" 2>/dev/null \
    | tail -1 | tr -d '\r"'"'"
}

# --- stop ----------------------------------------------------------------------
if [[ "$STOP" == true ]]; then
  if running; then kill "$(cat "$PID_FILE")" && rm -f "$PID_FILE" && echo "Express store stopped."
  else echo "Express store is not running."; fi
  [[ "$STORE" == true || -n "$KIOSK" ]] || exit 0
fi

# --- the store on this Mac -------------------------------------------------------
if [[ "$STORE" == true ]]; then
  mkdir -p "$DIR"
  if running; then
    echo "Express store already running (pid $(cat "$PID_FILE"))."
  else
    if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "ERROR: port $PORT is busy (--port N)." >&2; exit 1
    fi
    echo "Building the store server…"
    (cd "$REPO_ROOT/server" && ./gradlew -q --no-daemon buildFatJar)
    KEY="$(env_local STORE_API_KEY_EXPRESS)"
    SYNC_URL=""
    if [[ -n "$KEY" && "$KEY" != replace-* ]] && curl -fsS http://localhost:8081/health >/dev/null 2>&1; then
      SYNC_URL="http://localhost:8081"
    fi
    echo "Starting Copper Lantern — Express on :${PORT}…"
    (
      cd "$DIR"
      POS_VENUE=express POS_PORT="$PORT" POS_DB="$DIR/pos.db" \
      POS_RECEIPTS_DIR="$DIR/receipts" POS_BILLS_DIR="$DIR/bills" POS_PHOTOS_DIR="$DIR/photos" \
      POS_KITCHEN_PRINTING=on POS_PAYMENT_TERMINAL=simulator VENUE_TZ=America/Toronto \
      CLOUD_SYNC_URL="$SYNC_URL" CLOUD_SYNC_API_KEY="${SYNC_URL:+$KEY}" \
      REPORTING_PORTAL_URL="${SYNC_URL:+http://localhost:3000}" \
      nohup java -jar "$REPO_ROOT/server/build/libs/pos-server-all.jar" >> "$DIR/store.log" 2>&1 &
      echo $! > "$PID_FILE"
    )
    for ((i=0; i<60; i++)); do
      curl -fsS "http://localhost:$PORT/health" >/dev/null 2>&1 && break
      sleep 1
    done
    curl -fsS "http://localhost:$PORT/health" >/dev/null 2>&1 \
      || { echo "ERROR: the store did not come up — tail $DIR/store.log" >&2; exit 1; }
    echo "  healthy${SYNC_URL:+ (syncing to the local portal)}"
  fi
  IP="$(lan_ip)"
  cat <<EOF

  POS (this Mac):   cd client && flutter run -d macos --dart-define=SERVER_URL=http://localhost:$PORT
                    manager PIN 1234, cashier 9999
  Pickup board:     http://$IP:$PORT/pickup     (any browser or TV on the Wi-Fi)
  Kitchen screen:   http://$IP:$PORT/kitchen
  Kiosk address:    $IP:$PORT
EOF
fi

# --- the kiosk tablet ----------------------------------------------------------------
if [[ -n "$KIOSK" ]]; then
  command -v adb >/dev/null || { echo "ERROR: adb not found." >&2; exit 1; }
  if [[ ! -f "$APK" && "$BUILD" == true ]]; then
    echo "Building the kiosk app…"
    (cd "$REPO_ROOT/client" && flutter build apk --release --dart-define=POS_APP=kiosk)
    cp "$REPO_ROOT/client/build/app/outputs/flutter-apk/app-release.apk" "$APK"
  fi
  [[ -f "$APK" ]] || { echo "ERROR: no kiosk APK at $APK" >&2; exit 1; }
  echo "Installing the kiosk on ${KIOSK}…"
  adb -s "$KIOSK" install -r "$APK" >/dev/null
  # am start, not monkey (monkey resets the rotation)
  ACTIVITY="$(adb -s "$KIOSK" shell cmd package resolve-activity --brief dev.dwhipstock.pos_kiosk | tr -d '\r' | tail -1)"
  adb -s "$KIOSK" shell am start -n "$ACTIVITY" >/dev/null
  cat <<EOF
  started dev.dwhipstock.pos_kiosk

Pairing (once per kiosk):
  1. On the POS (manager): Orders → Pair a kiosk. It shows a 6-digit code (10 minutes, one use).
  2. On the kiosk: it looks for the store on the Wi-Fi; if it finds none, type the
     store's address (above), then the code, and tap Pair.
  The kiosk remembers the store; it goes straight to "Touch to order" after that.
EOF
fi
