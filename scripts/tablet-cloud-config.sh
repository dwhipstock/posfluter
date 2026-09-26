#!/usr/bin/env bash
# Point a tablet store's cloud sync at THIS Mac's local demo stack
# (scripts/demo-up.sh) over the LAN. Stages ONLY the sync settings; the tablet's
# store data (menu, staff, sales, identity) is never touched. The POS applies
# them on its next start (TabletStoreService.applyStagedCloudSettings) and then
# re-sends its outbox, so the local portal shows the store's full history.
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed and
# opened at least once, and the tablet on the same Wi-Fi as this Mac.
#
#   scripts/tablet-cloud-config.sh               # stage + restart the POS app
#   scripts/tablet-cloud-config.sh --no-restart  # stage only (applied on next app start)
#   scripts/tablet-cloud-config.sh --print       # show the settings (key masked), no adb
#   scripts/tablet-cloud-config.sh --install-id <id>   # adopt this sync identity (see below)
#   scripts/tablet-cloud-config.sh --app sagepoppy     # the Sage & Poppy app (see below)
#
# --app copperlantern|sagepoppy picks which POS app on the tablet (default
# copperlantern = Vieux-Port, key STORE_API_KEY; sagepoppy = Sage & Poppy, key
# STORE_API_KEY_SAGE_POPPY). The cloud pins each venue to ONE store database
# (its install id): while the Mac's desktop Sage & Poppy store syncs to this
# cloud, the tablet's Sage & Poppy app must not (docs/demo-runbook.md, "Two
# apps on one tablet"; scripts/tablet-sagepoppy-setup.sh).
#
# A tablet store that has NEVER synced has no install id, and the POS refuses to
# re-point it (logged as "REFUSED to re-point cloud sync"; it keeps starting
# normally) unless the identity is named explicitly with --install-id (or
# STORE_INSTALL_ID=<id>). A store that has synced keeps its own id; a given id
# must match it. This script also refuses up front when it can see (debuggable
# builds, via run-as) that the tablet has no identity yet.
#
# To go back to another cloud, stage that cloud's URL + Vieux-Port key the same way
# (the previous settings stay on the tablet as store-cloud.properties.prev).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$REPO_ROOT/scripts/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"
ENV_FILE="$REPO_ROOT/.env.local"
STAGING="$TABLET_FILES/migration"
case "$TABLET_APP" in
  sagepoppy) KEY_NAME=STORE_API_KEY_SAGE_POPPY; STORE_NAME="Sage & Poppy";;
  *) KEY_NAME=STORE_API_KEY; STORE_NAME="Vieux-Port";;
esac
MODE=""
INSTALL_ID="${STORE_INSTALL_ID:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-restart|--print) MODE="$1"; shift;;
    --install-id) INSTALL_ID="${2:-}"; [[ -n "$INSTALL_ID" ]] || { echo "ERROR: --install-id needs a value." >&2; exit 2; }; shift 2;;
    *) echo "ERROR: unknown argument $1" >&2; exit 2;;
  esac
done

[[ -f "$ENV_FILE" ]] || { echo "ERROR: $ENV_FILE missing — run scripts/demo-up.sh first." >&2; exit 1; }
KEY="$(grep "^$KEY_NAME=" "$ENV_FILE" | tail -1 | cut -d= -f2-)"
[[ -n "$KEY" && "$KEY" != replace-with-* ]] || { echo "ERROR: no $KEY_NAME in $ENV_FILE — run scripts/demo-up.sh." >&2; exit 1; }

LAN_IP="${LAN_IP:-$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)}"
[[ -n "$LAN_IP" ]] || { echo "ERROR: no LAN IP on en0/en1; set LAN_IP=<this Mac's Wi-Fi address>." >&2; exit 1; }
SYNC_URL="http://${LAN_IP}:8081"
PORTAL_URL="http://${LAN_IP}:3000"

if ! curl -fsS -m 5 "$SYNC_URL/health" >/dev/null 2>&1; then
  echo "WARN: $SYNC_URL/health does not answer from this Mac — is demo-up.sh running?" >&2
fi

if [[ "$MODE" == "--print" ]]; then
  printf 'cloud.url=%s\ncloud.apiKey=%s…\nportal.url=%s\n' "$SYNC_URL" "${KEY:0:8}" "$PORTAL_URL"
  [[ -n "$INSTALL_ID" ]] && printf 'store.installId=%s\n' "$INSTALL_ID"
  exit 0
fi

tablet_require_adb

# Best-effort pre-check (debuggable builds only; a release build hides its files
# and the tablet itself enforces the same rule at startup).
# A synced store always has store.installId in its private settings file.
if [[ -z "$INSTALL_ID" ]] && adb shell run-as "$PACKAGE" true >/dev/null 2>&1; then
  CURRENT="$(adb shell run-as "$PACKAGE" cat files/store-cloud.properties 2>/dev/null | tr -d '\r' || true)"
  if ! grep -q '^store.installId=.' <<<"$CURRENT"; then
    echo "REFUSED: this tablet's store has never synced (no install id). Re-run with" >&2
    echo "  --install-id <id>  to adopt a sync identity explicitly." >&2
    exit 1
  fi
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
umask 077
# store.installId is only sent when given: a synced tablet keeps its own
# identity, and a never-synced one is refused without it.
printf 'cloud.url=%s\ncloud.apiKey=%s\nportal.url=%s\n' "$SYNC_URL" "$KEY" "$PORTAL_URL" > "$TMP/store-cloud.properties"
[[ -n "$INSTALL_ID" ]] && printf 'store.installId=%s\n' "$INSTALL_ID" >> "$TMP/store-cloud.properties"

adb shell mkdir -p "$STAGING"
adb push "$TMP/store-cloud.properties" "$STAGING/store-cloud.properties" >/dev/null
echo "Staged $STORE_NAME sync ($TABLET_APP_NAME) → $SYNC_URL (portal $PORTAL_URL)."

if [[ "$MODE" != "--no-restart" ]]; then
  tablet_restart_app
  echo "Restarted $TABLET_APP_NAME. It applies the settings at startup; watch with:"
  echo "  adb logcat -s TabletStore"
  echo "(a never-synced store logs REFUSED and keeps its current settings)"
fi
