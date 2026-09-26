#!/usr/bin/env bash
# Switch the Android tablet store between paper and digital-only sale receipts.
#
#   scripts/tablet-print-mode.sh paper     # receipts + bills print on the thermal printer (default)
#   scripts/tablet-print-mode.sh digital   # receipts + bills are saved digitally only (no paper)
#   scripts/tablet-print-mode.sh digital --app sagepoppy   # the Sage & Poppy app instead
#
# --app copperlantern|sagepoppy picks which POS app on the tablet (default
# copperlantern; see scripts/lib/tablet-app.sh). Writes print.receipts=<mode>
# into that app's external files dir
# (/sdcard/Android/data/<package>/files/store.properties; other keys in that
# file are kept) and restarts that app, which reads it at store startup.
# Manual prints (test page, table QR slips) always use paper. Nothing here
# touches the network or the store's data. Check the result with:
#   adb logcat -s TabletStore | grep "Receipt printing"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

MODE="${1:-}"
[[ "$MODE" == "paper" || "$MODE" == "digital" ]] || { echo "usage: $0 paper|digital [--app copperlantern|sagepoppy]" >&2; exit 2; }

tablet_require_adb
# keep any other settings already in the file; replace only print.receipts
tablet_props_set 'print\.receipts' "print.receipts=$MODE"
echo "Set print.receipts=$MODE for $TABLET_APP_NAME on the tablet."

tablet_restart_app
echo "Restarted $TABLET_APP_NAME. Confirm with: adb logcat -s TabletStore | grep 'Receipt printing'"
