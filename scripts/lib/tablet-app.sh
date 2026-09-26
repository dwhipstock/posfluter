# shellcheck shell=bash
# Which POS app on the tablet a scripts/tablet-*.sh helper talks to. One tablet
# can run both brand apps side by side, each its own Android app with its own
# data and its own external store.properties:
#
#   --app copperlantern   dev.dwhipstock.pos_client     store :8080  (default; Vieux-Port)
#   --app sagepoppy       dev.dwhipstock.pos_sagepoppy  store :8082  (Sage & Poppy)
#
# Must match the brand table in client/android/app/build.gradle.kts.
# POS_PACKAGE=<id> still overrides the package (e.g. a debug build).
#
# Usage in a script:  source "$(dirname "$0")/lib/tablet-app.sh"
#                     tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"
# Sets TABLET_APP, PACKAGE, TABLET_STORE_PORT, TABLET_VENUE, TABLET_APP_NAME,
# TABLET_FILES (the app's external files dir on the tablet).

tablet_app_set() {
  case "$1" in
    copperlantern)
      TABLET_APP=copperlantern; TABLET_PACKAGE_DEFAULT=dev.dwhipstock.pos_client
      TABLET_STORE_PORT=8080; TABLET_VENUE=vieux-port; TABLET_APP_NAME="Copper Lantern POS";;
    sagepoppy)
      TABLET_APP=sagepoppy; TABLET_PACKAGE_DEFAULT=dev.dwhipstock.pos_sagepoppy
      TABLET_STORE_PORT=8082; TABLET_VENUE=sage-poppy; TABLET_APP_NAME="Sage & Poppy POS";;
    *) echo "ERROR: --app must be copperlantern or sagepoppy (got '$1')." >&2; exit 2;;
  esac
  PACKAGE="${POS_PACKAGE:-$TABLET_PACKAGE_DEFAULT}"
  TABLET_FILES="/sdcard/Android/data/$PACKAGE/files"
}

# Pull --app <name> / --app=<name> out of the arguments; the rest are left in
# TABLET_ARGS for the script's own parsing.
tablet_app_parse() {
  local app="copperlantern"
  TABLET_ARGS=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --app) [[ -n "${2:-}" ]] || { echo "ERROR: --app needs copperlantern or sagepoppy." >&2; exit 2; }
             app="$2"; shift 2;;
      --app=*) app="${1#--app=}"; shift;;
      *) TABLET_ARGS+=("$1"); shift;;
    esac
  done
  tablet_app_set "$app"
}

tablet_require_adb() {
  command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found (brew install --cask android-platform-tools)." >&2; exit 1; }
  adb get-state >/dev/null 2>&1 || { echo "ERROR: no tablet on adb (USB debugging on? adb devices)." >&2; exit 1; }
  adb shell pm path "$PACKAGE" >/dev/null 2>&1 || {
    echo "ERROR: $TABLET_APP_NAME ($PACKAGE) is not installed on the tablet." >&2; exit 1; }
}

# Replace KEY lines in the app's store.properties (other lines kept). Values in
# "$@" are full KEY=VALUE lines to append; an empty list only removes KEY.
tablet_props_set() {
  local key_regex="$1"; shift
  local tmp
  tmp="$(mktemp -d)"
  chmod 700 "$tmp"
  adb shell cat "$TABLET_FILES/store.properties" 2>/dev/null | tr -d '\r' \
    | grep -v "^[[:space:]]*\\($key_regex\\)[[:space:]]*[=:]" > "$tmp/store.properties" || true
  local line
  for line in "$@"; do printf '%s\n' "$line" >> "$tmp/store.properties"; done
  adb shell mkdir -p "$TABLET_FILES"
  adb push "$tmp/store.properties" "$TABLET_FILES/store.properties" >/dev/null
  rm -rf "$tmp"
}

tablet_restart_app() {
  adb shell am force-stop "$PACKAGE"
  adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
}
