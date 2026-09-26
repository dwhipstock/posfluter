#!/usr/bin/env bash
# Turn the tablet store's staff web app (/staff-app) MFA on or off.
#
#   scripts/tablet-staff-mfa.sh on    # PIN + authenticator code (default)
#   scripts/tablet-staff-mfa.sh off   # PIN only
#
# Writes staff.app.mfa=<on|off> into the app's external files dir
# (/sdcard/Android/data/<package>/files/store.properties; other keys in that
# file are kept) and restarts the POS app, which reads it at store startup.
# Staff already signed in keep their session; the change applies at the next
# staff-app sign-in. The terminal's PIN login and the owner portal are
# unaffected. Check the result with:
#   adb logcat -s TabletStore | grep "Staff app MFA"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail

MODE="${1:-}"
[[ "$MODE" == "on" || "$MODE" == "off" ]] || { echo "usage: $0 on|off" >&2; exit 2; }
PACKAGE="${POS_PACKAGE:-dev.dwhipstock.pos_client}"
DIR="/sdcard/Android/data/$PACKAGE/files"

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found (brew install --cask android-platform-tools)." >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "ERROR: no tablet on adb (USB debugging on? adb devices)." >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# keep any other settings already in the file; replace only staff.app.mfa
adb shell cat "$DIR/store.properties" 2>/dev/null | tr -d '\r' | grep -v '^[[:space:]]*staff\.app\.mfa[[:space:]]*[=:]' > "$TMP/store.properties" || true
printf 'staff.app.mfa=%s\n' "$MODE" >> "$TMP/store.properties"

adb shell mkdir -p "$DIR"
adb push "$TMP/store.properties" "$DIR/store.properties" >/dev/null
echo "Set staff.app.mfa=$MODE on the tablet."

adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "Restarted the POS. Confirm with: adb logcat -s TabletStore | grep 'Staff app MFA'"
