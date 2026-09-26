#!/usr/bin/env bash
# Turn kitchen / station tickets (and the kitchen screen) on or off for the
# tablet store.
#
#   scripts/tablet-kitchen-printing.sh on    # send orders to Kitchen, Bar, …
#   scripts/tablet-kitchen-printing.sh off   # as before: no kitchen tickets (default)
#
# Writes kitchen.printing=<on|off> into the Copper Lantern app's external
# files dir (/sdcard/Android/data/<package>/files/store.properties; other keys
# in that file are kept) and restarts the app, which reads it at store
# startup. Restaurants only: a retail store ignores it. Stations and menu
# mappings are then set up on the tablet (More → Venue settings → Kitchen
# tickets). Check the result with:
#   adb logcat -s TabletStore | grep "Kitchen tickets"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

MODE="${1:-}"
[[ "$MODE" == "on" || "$MODE" == "off" ]] || { echo "usage: $0 on|off" >&2; exit 2; }

tablet_require_adb
# keep any other settings already in the file; replace only kitchen.printing
tablet_props_set 'kitchen\.printing' "kitchen.printing=$MODE"
echo "Set kitchen.printing=$MODE for $TABLET_APP_NAME on the tablet."

tablet_restart_app
echo "Restarted $TABLET_APP_NAME. Confirm with: adb logcat -s TabletStore | grep 'Kitchen tickets'"
