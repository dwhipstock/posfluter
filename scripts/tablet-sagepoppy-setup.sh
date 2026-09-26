#!/usr/bin/env bash
# Set up the Sage & Poppy POS app (dev.dwhipstock.pos_sagepoppy) on the tablet,
# next to the Copper Lantern app. It is its own Android app: its own database,
# files, store.properties, cloud key and install id, its store on :8082.
#
#   scripts/tablet-sagepoppy-setup.sh status          # what the tablet has (read-only)
#   scripts/tablet-sagepoppy-setup.sh local-only      # store.venue=sage-poppy, no cloud (default)
#   scripts/tablet-sagepoppy-setup.sh move-from-mac   # move the Mac's Sage & Poppy store (data,
#                                                     # identity, hosted cloud key) to the tablet
#
# Why two ways: the cloud pins each venue to ONE store database (its install
# id, cloud migration 002). The hosted `sage-poppy` store is fed by the Mac's
# desktop store today, so a second, new database for the same venue would be
# refused (409 install_mismatch) — and must not be forced, as its sale/shift ids
# would collide with the Mac's history.
#
#  - local-only: the tablet runs Sage & Poppy on its own demo catalog, offline;
#    the Mac keeps feeding the portal. Nothing cloud-side changes.
#  - move-from-mac: the tablet takes over the Mac store's database, so it keeps
#    the SAME install id and the hosted cloud accepts it with no change there.
#    Afterwards the Mac store must never sync again (this script sets its
#    .demo/sage-poppy/store.env to DEMO_CLOUD=offline). Needs:
#      * the Mac's Sage & Poppy store STOPPED (nothing listening on :8082 here;
#        `scripts/demo-autostart.sh` users: stop it there too),
#      * the tablet app installed but its store NOT yet created — a store that
#        already has a database refuses the import. If you ran local-only first:
#        `adb shell pm clear dev.dwhipstock.pos_sagepoppy` (deletes that
#        local-only store and its store.properties), then run this.
#    The import is validated on the tablet (integrity, identity) before anything
#    is installed; watch `adb logcat -s TabletStore` for
#    "Validated store backup imported".
#
# The cloud key is never printed. Needs: adb with the tablet connected.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$REPO_ROOT/scripts/lib/tablet-app.sh"
tablet_app_set sagepoppy
MODE="${1:-local-only}"
DEMO_ROOT="${POS_DEMO_ROOT:-$REPO_ROOT}"
MAC_ENV="$DEMO_ROOT/.demo/sage-poppy/store.env"

file_get() { [[ -f "$2" ]] && grep "^$1=" "$2" | tail -1 | cut -d= -f2- || true; }

case "$MODE" in
  status)
    tablet_require_adb
    echo "$TABLET_APP_NAME ($PACKAGE), store port $TABLET_STORE_PORT"
    echo "--- store.properties"
    adb shell cat "$TABLET_FILES/store.properties" 2>/dev/null | tr -d '\r' \
      | sed -E 's/^(stripe\.secretKey|cloud\.apiKey)=.*/\1=<hidden>/' || echo "(none)"
    echo "--- staged for next start (migration/)"
    adb shell ls "$TABLET_FILES/migration" 2>/dev/null | tr -d '\r' || echo "(nothing)"
    echo "--- listening on the tablet"
    adb shell ss -ltn 2>/dev/null | grep -E ':(8080|8082)\b' || echo "(no store port open)"
    ;;

  local-only)
    tablet_require_adb
    tablet_props_set 'store\.venue' "store.venue=$TABLET_VENUE"
    # a half-staged cloud config from an earlier attempt would be applied at start
    adb shell rm -f "$TABLET_FILES/migration/store-cloud.properties" >/dev/null 2>&1 || true
    echo "Set store.venue=$TABLET_VENUE for $TABLET_APP_NAME (no cloud: runs offline on its own catalog)."
    tablet_restart_app
    echo "Restarted $TABLET_APP_NAME. Its store answers on :$TABLET_STORE_PORT; check with:"
    echo "  adb logcat -s TabletStore      # 'Store: sage-poppy (port 8082)'"
    ;;

  move-from-mac)
    command -v sqlite3 >/dev/null 2>&1 || { echo "ERROR: sqlite3 not found." >&2; exit 1; }
    command -v zip >/dev/null 2>&1 || { echo "ERROR: zip not found." >&2; exit 1; }
    [[ -f "$MAC_ENV" ]] || { echo "ERROR: $MAC_ENV not found (run scripts/demo-reset.sh --store sage-poppy once)." >&2; exit 1; }
    if lsof -nP -iTCP:8082 -sTCP:LISTEN >/dev/null 2>&1; then
      echo "ERROR: a store is still listening on :8082 on this Mac. Stop the Mac's Sage & Poppy store first" >&2
      echo "       (it must not sync again once the tablet has its database)." >&2
      exit 1
    fi
    data_dir="$(file_get DEMO_DATA_DIR "$MAC_ENV")"; data_dir="${data_dir:-.demo/sage-poppy}"
    case "$data_dir" in /*) ;; *) data_dir="$DEMO_ROOT/$data_dir";; esac
    url="$(file_get DEMO_HOSTED_SYNC_URL "$MAC_ENV")"
    key="$(file_get DEMO_HOSTED_API_KEY "$MAC_ENV")"
    portal="$(file_get DEMO_HOSTED_PORTAL_URL "$MAC_ENV")"
    pinned="$(file_get DEMO_HOSTED_INSTALL_ID "$MAC_ENV")"
    [[ -f "$data_dir/pos.db" ]] || { echo "ERROR: no pos.db in $data_dir." >&2; exit 1; }
    [[ "$url" == https://* && -n "$key" ]] || { echo "ERROR: $MAC_ENV has no hosted cloud URL/key (DEMO_HOSTED_*)." >&2; exit 1; }
    tablet_require_adb

    TMP="$(mktemp -d)"; chmod 700 "$TMP"
    trap 'rm -rf "$TMP"' EXIT
    umask 077
    # a consistent copy even with a WAL next to it
    sqlite3 "$data_dir/pos.db" ".backup '$TMP/pos.db'"
    [[ "$(sqlite3 "$TMP/pos.db" 'PRAGMA integrity_check')" == ok ]] || { echo "ERROR: database integrity check failed." >&2; exit 1; }
    install_id="$(sqlite3 "$TMP/pos.db" "SELECT value FROM sync_state WHERE key='install_id'")"
    [[ "$install_id" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "ERROR: the Mac store has no install id (never synced)." >&2; exit 1; }
    if [[ -n "$pinned" && "$pinned" != "$install_id" ]]; then
      echo "ERROR: the Mac database's install id is not the one the hosted cloud pinned ($MAC_ENV)." >&2; exit 1
    fi
    printf 'store.installId=%s\ncloud.url=%s\ncloud.apiKey=%s\n' "$install_id" "$url" "$key" > "$TMP/store-cloud.properties"
    [[ -n "$portal" ]] && printf 'portal.url=%s\n' "$portal" >> "$TMP/store-cloud.properties"
    for d in photos receipts bills; do
      mkdir -p "$TMP/media/$d"
      [[ -d "$data_dir/$d" ]] && cp -R "$data_dir/$d/." "$TMP/media/$d/"
    done
    (cd "$TMP/media" && zip -qr "$TMP/store-media.zip" photos receipts bills)

    tablet_props_set 'store\.venue' "store.venue=$TABLET_VENUE"
    adb shell mkdir -p "$TABLET_FILES/migration"
    adb push "$TMP/pos.db" "$TABLET_FILES/migration/pos.db" >/dev/null
    adb push "$TMP/store-media.zip" "$TABLET_FILES/migration/store-media.zip" >/dev/null
    adb push "$TMP/store-cloud.properties" "$TABLET_FILES/migration/store-cloud.properties" >/dev/null
    # the Mac store must not push as the same store again
    tmpenv="$(mktemp "$MAC_ENV.XXXXXX")"
    { grep -v '^DEMO_CLOUD=' "$MAC_ENV" || true; echo 'DEMO_CLOUD=offline'; } > "$tmpenv"
    chmod 600 "$tmpenv"; mv "$tmpenv" "$MAC_ENV"
    echo "Staged the Mac's Sage & Poppy store for a one-time import (install id $install_id)."
    echo "Set DEMO_CLOUD=offline in ${MAC_ENV#"$DEMO_ROOT"/}: the Mac store no longer syncs."
    tablet_restart_app
    echo "Restarted $TABLET_APP_NAME. Watch: adb logcat -s TabletStore   ('Validated store backup imported')"
    ;;

  *) echo "usage: $0 status|local-only|move-from-mac" >&2; exit 2;;
esac
