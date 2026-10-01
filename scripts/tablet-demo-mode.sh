#!/usr/bin/env bash
# Turn the tablet store's demo mode on or off (docs/demo-runbook.md, "Demo mode").
#
#   scripts/tablet-demo-mode.sh on                      # demo mode on
#   scripts/tablet-demo-mode.sh off                     # back to normal
#   scripts/tablet-demo-mode.sh on --app sagepoppy      # the Sage & Poppy app instead
#
# Demo mode: the staff phone app signs in by PIN only, and managers get
# "Print demo QR sheet" in More → Venue settings.
#
# The sheet's manager-portal block prints the sign-in from demo.portal.* when
# these are set in the environment (never typed on the command line):
#   DEMO_PORTAL_URL=https://portal.example.com DEMO_PORTAL_USER=owner@example.com \
#     scripts/tablet-demo-mode.sh on
# The password is asked for without echo (or DEMO_PORTAL_PASSWORD). Unset ones
# print "ask the presenter". Nothing here prints or logs the password.
#
# Writes demo.mode (and demo.portal.*) into the app's external
# store.properties (other keys kept) and restarts the app. Check with:
#   adb logcat -s TabletStore | grep "Demo mode"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

MODE="${1:-}"
[[ "$MODE" == "on" || "$MODE" == "off" ]] || { echo "usage: $0 on|off [--app copperlantern|sagepoppy]" >&2; exit 2; }

tablet_require_adb
lines=("demo.mode=$MODE")
if [[ "$MODE" == "on" ]]; then
  [[ -n "${DEMO_PORTAL_URL:-}" ]] && lines+=("demo.portal.url=$DEMO_PORTAL_URL")
  if [[ -n "${DEMO_PORTAL_USER:-}" ]]; then
    lines+=("demo.portal.user=$DEMO_PORTAL_USER")
    pw="${DEMO_PORTAL_PASSWORD:-}"
    if [[ -z "$pw" && -t 0 ]]; then
      read -rsp "Portal password for the demo sheet (Enter = ask the presenter): " pw; echo
    fi
    [[ -n "$pw" ]] && lines+=("demo.portal.password=$pw")
  fi
  # replace any older demo sign-in only when a new one is given
  if [[ ${#lines[@]} -gt 1 ]]; then
    tablet_props_set 'demo\.[a-z.]*' "${lines[@]}"
  else
    tablet_props_set 'demo\.mode' "${lines[@]}"
  fi
else
  tablet_props_set 'demo\.mode' "${lines[@]}"
fi
unset pw
echo "Set demo.mode=$MODE for $TABLET_APP_NAME on the tablet."

tablet_restart_app
echo "Restarted $TABLET_APP_NAME. Confirm with: adb logcat -s TabletStore | grep 'Demo mode'"
