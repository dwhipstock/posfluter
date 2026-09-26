#!/usr/bin/env bash
# Turn the tablet store's staff web app (/staff-app) MFA on or off.
#
#   scripts/tablet-staff-mfa.sh on    # PIN + authenticator code (default)
#   scripts/tablet-staff-mfa.sh off   # PIN only
#   scripts/tablet-staff-mfa.sh off --app sagepoppy   # the Sage & Poppy app instead
#
# --app copperlantern|sagepoppy picks which POS app on the tablet (default
# copperlantern; see scripts/lib/tablet-app.sh). Writes staff.app.mfa=<on|off>
# into that app's external files dir
# (/sdcard/Android/data/<package>/files/store.properties; other keys in that
# file are kept) and restarts that app, which reads it at store startup.
# Staff already signed in keep their session; the change applies at the next
# staff-app sign-in. The terminal's PIN login and the owner portal are
# unaffected. Check the result with:
#   adb logcat -s TabletStore | grep "Staff app MFA"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

MODE="${1:-}"
[[ "$MODE" == "on" || "$MODE" == "off" ]] || { echo "usage: $0 on|off [--app copperlantern|sagepoppy]" >&2; exit 2; }

tablet_require_adb
# keep any other settings already in the file; replace only staff.app.mfa
tablet_props_set 'staff\.app\.mfa' "staff.app.mfa=$MODE"
echo "Set staff.app.mfa=$MODE for $TABLET_APP_NAME on the tablet."

tablet_restart_app
echo "Restarted $TABLET_APP_NAME. Confirm with: adb logcat -s TabletStore | grep 'Staff app MFA'"
