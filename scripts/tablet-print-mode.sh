#!/usr/bin/env bash
# Switch the Android tablet store between paper and digital-only sale receipts.
#
#   scripts/tablet-print-mode.sh paper     # receipts + bills print on the thermal printer (default)
#   scripts/tablet-print-mode.sh digital   # receipts + bills are saved digitally only (no paper)
#
# Writes print.receipts=<mode> into the app's external files dir
# (/sdcard/Android/data/<package>/files/store.properties; other keys in that
# file are kept) and restarts the POS app, which reads it at store startup.
# Manual prints (test page, table QR slips) always use paper. Nothing here
# touches the network or the store's data. Check the result with:
#   adb logcat -s TabletStore | grep "Receipt printing"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail

MODE="${1:-}"
[[ "$MODE" == "paper" || "$MODE" == "digital" ]] || { echo "usage: $0 paper|digital" >&2; exit 2; }
PACKAGE="${POS_PACKAGE:-dev.dwhipstock.pos_client}"
DIR="/sdcard/Android/data/$PACKAGE/files"

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found (brew install --cask android-platform-tools)." >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "ERROR: no tablet on adb (USB debugging on? adb devices)." >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# keep any other settings already in the file; replace only print.receipts
adb shell cat "$DIR/store.properties" 2>/dev/null | tr -d '\r' | grep -v '^[[:space:]]*print\.receipts[[:space:]]*[=:]' > "$TMP/store.properties" || true
printf 'print.receipts=%s\n' "$MODE" >> "$TMP/store.properties"

adb shell mkdir -p "$DIR"
adb push "$TMP/store.properties" "$DIR/store.properties" >/dev/null
echo "Set print.receipts=$MODE on the tablet."

adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "Restarted the POS. Confirm with: adb logcat -s TabletStore | grep 'Receipt printing'"
