#!/usr/bin/env bash
# Point a Mac demo store (Plateau, Sage & Poppy) at ITS OWN client portal —
# e.g. Sage & Poppy at its own portal instead of Copper Lantern's — using the
# store file cloud/infra/new-client.sh wrote for it. The key is copied from that
# file into .demo/<store>/store.env and never printed.
#
#   scripts/demo-point-store.sh sage-poppy --hosted <clients/sagepoppy/stores/sage-poppy.env>
#   scripts/demo-point-store.sh sage-poppy --hosted <file> --resend --yes
#   scripts/demo-point-store.sh sage-poppy --local  <file> --yes      # a local client portal
#
#   --hosted | --local   which slot of store.env the new portal goes in (DEMO_HOSTED_* /
#                        DEMO_LOCAL_*); the store then syncs to it (DEMO_CLOUD)
#   --resend             the new portal is empty: send the store's WHOLE history again
#                        (clears push_hwm + catalog_cursor in its sync_state; the outbox
#                        keeps every event, and the portal ignores events it already has)
#   --yes                do it (without it: print the plan only, exit 1)
#
# Steps: back up store.env (+ pos.db with --resend) → stop the store → write the
# new URL/key → (clear the cursors) → start it with the same settings → wait for
# its first sync. Selling is never affected: a store with no portal just queues.
set -euo pipefail

# shellcheck source=lib/demo-store.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/demo-store.sh"

STORE="${1:-}"; shift || true
SLOT=""; FILE=""; RESEND=0; YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --hosted|--local) SLOT="${1#--}"; FILE="${2:-}"; shift 2 || { demo_err "$1 needs a store file"; exit 2; };;
    --resend) RESEND=1; shift;;
    --yes|-y) YES=1; shift;;
    -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) demo_err "unknown option $1 (see --help)"; exit 2;;
  esac
done
demo_known_store "$STORE" || { demo_err "store: plateau | sage-poppy"; exit 2; }
[[ -n "$SLOT" && -f "$FILE" ]] || { demo_err "--hosted <file> or --local <file> is required (a store file from cloud/infra/new-client.sh)"; exit 2; }
for tool in sqlite3 curl; do command -v "$tool" >/dev/null || { demo_err "$tool is needed"; exit 1; }; done

URL="$(demo_file_get CLOUD_SYNC_URL "$FILE")"
KEY="$(demo_file_get CLOUD_SYNC_API_KEY "$FILE")"
PORTAL="$(demo_file_get REPORTING_PORTAL_URL "$FILE")"; PORTAL="${PORTAL:-$URL}"
VENUE="$(demo_file_get POS_VENUE "$FILE")"
[[ -n "$URL" && -n "$KEY" ]] || { demo_err "$FILE has no CLOUD_SYNC_URL / CLOUD_SYNC_API_KEY"; exit 1; }

demo_load_env "$STORE"
[[ -z "$VENUE" || "$VENUE" == "${POS_VENUE:-$STORE}" ]] || { demo_err "$FILE is for store '$VENUE', not ${POS_VENUE:-$STORE}"; exit 1; }
ENVF="$(demo_env_file "$STORE")"
DB="$DEMO_DATA_DIR/pos.db"
UPPER="$(echo "$SLOT" | tr '[:lower:]' '[:upper:]')"
OLD_URL="$(demo_file_get "DEMO_${UPPER}_SYNC_URL" "$ENVF")"

echo "=== $(demo_display_name "$STORE") → its own portal ==="
echo "  settings     : ${ENVF#"$DEMO_ROOT"/} (DEMO_${UPPER}_*; now: ${OLD_URL:-none}; syncing to: $DEMO_CLOUD)"
echo "  new portal   : $URL   (key from ${FILE#"$REPO_ROOT"/}, not shown)"
echo "  after        : DEMO_CLOUD=$SLOT"
if [[ "$RESEND" == 1 ]]; then
  hwm="$(sqlite3 "$DB" "SELECT value FROM sync_state WHERE key='push_hwm'" 2>/dev/null || true)"
  outbox="$(sqlite3 "$DB" "SELECT COALESCE(MAX(id),0) FROM sync_outbox" 2>/dev/null || echo "?")"
  echo "  re-send      : the whole history — outbox rows 1…$outbox (the old portal had acked up to ${hwm:-0})"
fi
if [[ "$YES" != 1 ]]; then
  echo "  (plan only — add --yes to do it)"
  exit 1
fi

ts="$(date +%Y%m%d-%H%M%S)"
bk="$DEMO_DATA_DIR/backups/repoint-$ts"
mkdir -p "$bk"; chmod 700 "$bk"
cp -p "$ENVF" "$bk/store.env"
agent=0
if demo_agent_loaded "$STORE"; then agent=1; launchctl bootout "gui/$(id -u)/$(demo_agent_label "$STORE")" 2>/dev/null || true; fi
demo_stop
echo "  stopped the store"
if [[ "$RESEND" == 1 ]]; then
  sqlite3 "$DB" ".backup '$bk/pos.db'"
  sqlite3 "$DB" "DELETE FROM sync_state WHERE key IN ('push_hwm','catalog_cursor');"
  echo "  cleared push_hwm + catalog_cursor (backup: ${bk#"$DEMO_ROOT"/}/pos.db)"
fi
demo_file_set "DEMO_${UPPER}_SYNC_URL" "$URL" "$ENVF"
demo_file_set "DEMO_${UPPER}_API_KEY" "$KEY" "$ENVF"
demo_file_set "DEMO_${UPPER}_PORTAL_URL" "$PORTAL" "$ENVF"
demo_file_set DEMO_CLOUD "$SLOT" "$ENVF"
demo_load_env "$STORE"
offset="$(wc -c < "$DEMO_DATA_DIR/store.log" 2>/dev/null || echo 0)"
if [[ "$agent" == 1 ]]; then
  launchctl bootstrap "gui/$(id -u)" "$(demo_agent_plist "$STORE")"
  demo_wait_healthy "$POS_PORT" 60 || { demo_err "the store did not come up — tail $DEMO_DATA_DIR/store.log"; exit 1; }
  echo "  started (login agent)"
else
  demo_start_bg "$SLOT"
fi
hwm_now() { sqlite3 "$DB" "SELECT value FROM sync_state WHERE key='push_hwm'" 2>/dev/null || true; }
start_hwm="$(hwm_now)"
outbox="$(sqlite3 "$DB" "SELECT COALESCE(MAX(id),0) FROM sync_outbox" 2>/dev/null || echo 0)"
for ((i = 0; i < 45; i++)); do
  hwm="$(hwm_now)"
  [[ -n "$hwm" && ( "$hwm" != "$start_hwm" || "$hwm" -ge "$outbox" ) ]] && break
  sleep 2
done
[[ -n "$hwm" ]] || demo_warn "nothing acked yet — check the portal URL/key: tail -c +$((offset + 1)) $DEMO_DATA_DIR/store.log"
echo "  syncing to $URL — acked up to outbox row ${hwm:-0} so far (it keeps going in the background)"
echo "  undo: cp ${bk#"$DEMO_ROOT"/}/store.env ${ENVF#"$DEMO_ROOT"/} && scripts/demo-autostart.sh restart --store $STORE"
