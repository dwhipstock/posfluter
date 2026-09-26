#!/usr/bin/env bash
# Run the card terminal SIMULATOR on this Mac: a pretend countertop card reader
# that the tablet POS reaches over the store Wi-Fi by IP, like a real terminal.
#
#   scripts/demo-terminal.sh                      # start it and open the reader screen
#   scripts/demo-terminal.sh --push-tablet        # ...and point the tablet POS at it (adb)
#   scripts/demo-terminal.sh --push-tablet --app sagepoppy
#   scripts/demo-terminal.sh --jpmorgan --push-tablet   # tablet uses J.P. Morgan sandbox behind it
#
# The Mac window is the customer-facing reader (big amount, "Tap, insert or
# swipe"); the card "wallet" under it taps / inserts (PIN) / swipes a test card
# with an outcome (approve, insufficient funds, do not honour, time out,
# cancel). No internet, no real card data.
#
# --push-tablet writes payment.terminal=simulator (or jpmorgan with --jpmorgan)
# and payment.terminal.host=<this Mac>:<port> into the POS app's external
# store.properties (keeping every other line) and restarts the POS. Then on the
# POS: Settings → Card terminal → Pair, and enter the code shown on the Mac.
# --jpmorgan also pushes the JPM_* sandbox settings from the repo-root .env
# (never printed).
#
# Env: TERMINAL_PORT (default 8090), TERMINAL_NAME, REBUILD=1 to rebuild the jar.
# Needs: Java 17. For --push-tablet: adb with the tablet connected.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$REPO_ROOT/scripts/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

PUSH=false
KIND=simulator
for a in "$@"; do
  case "$a" in
    --push-tablet) PUSH=true ;;
    --jpmorgan) KIND=jpmorgan ;;
    *) echo "usage: $0 [--push-tablet] [--jpmorgan] [--app copperlantern|sagepoppy]" >&2; exit 2 ;;
  esac
done

PORT="${TERMINAL_PORT:-8090}"
JAR="$REPO_ROOT/server/build/libs/pos-server-all.jar"
if [[ ! -f "$JAR" || "${REBUILD:-0}" == 1 ]]; then
  echo "Building the store jar (once)…"
  (cd "$REPO_ROOT/server" && ./gradlew -q --no-daemon buildFatJar)
fi

# this Mac's address on the store Wi-Fi (the first active interface with an IPv4)
IP=""
for ifc in en0 en1 en2; do IP="$(ipconfig getifaddr "$ifc" 2>/dev/null || true)"; [[ -n "$IP" ]] && break; done
[[ -n "$IP" ]] || IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
[[ -n "$IP" ]] || { echo "WARNING: couldn't find this machine's LAN address; use its IP by hand." >&2; IP="<this-mac-ip>"; }

env_value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/.env" 2>/dev/null \
    | tail -1 | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//' -e 's/[[:space:]]*$//'
}

if [[ "$PUSH" == true ]]; then
  tablet_require_adb
  LINES=("payment.terminal=$KIND" "payment.terminal.host=$IP:$PORT")
  if [[ "$KIND" == jpmorgan ]]; then
    for k in CLIENT_ID CLIENT_SECRET TOKEN_URL SCOPE MERCHANT_ID BASE_URL; do
      v="$(env_value "JPM_$k")"
      [[ -n "$v" ]] || continue
      case "$k" in
        CLIENT_ID) LINES+=("payment.jpmorgan.clientId=$v") ;;
        CLIENT_SECRET) LINES+=("payment.jpmorgan.clientSecret=$v") ;;
        TOKEN_URL) LINES+=("payment.jpmorgan.tokenUrl=$v") ;;
        SCOPE) LINES+=("payment.jpmorgan.scope=$v") ;;
        MERCHANT_ID) LINES+=("payment.jpmorgan.merchantId=$v") ;;
        BASE_URL) LINES+=("payment.jpmorgan.baseUrl=$v") ;;
      esac
    done
  fi
  tablet_props_set 'payment\.terminal\|payment\.terminal\.host\|payment\.jpmorgan\.[A-Za-z]*' "${LINES[@]}"
  tablet_restart_app
  echo "Pointed $TABLET_APP_NAME at this terminal (payment.terminal=$KIND, host $IP:$PORT) and restarted it."
fi

cat <<EOF

  Card terminal simulator
  ---------------------------------------------------------------
  Reader screen:   http://localhost:$PORT/   (opening it now; press Full screen)
  On the Wi-Fi:    $IP:$PORT

  On the tablet (if you didn't use --push-tablet), in store.properties:
      payment.terminal=$KIND
      payment.terminal.host=$IP:$PORT
  then restart the POS and go to Settings → Card terminal → Pair,
  and enter the 6-digit code shown on this Mac.

  Ctrl-C stops the terminal.
EOF

( sleep 2; open "http://localhost:$PORT/?kiosk" >/dev/null 2>&1 || true ) &
TERMINAL_PORT="$PORT" exec java -cp "$JAR" dev.dwhipstock.pos.tools.TerminalSimulatorMainKt
