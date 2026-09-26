#!/usr/bin/env bash
# Put a demo store back into a clean, good-looking state before a meeting.
#
#   scripts/demo-reset.sh --store sage-poppy            # print the plan only (changes nothing)
#   scripts/demo-reset.sh --store sage-poppy --yes      # do it
#   scripts/demo-reset.sh --store plateau --hosted --yes
#   scripts/demo-reset.sh --store all --yes             # plateau, sage-poppy, then the tablet
#
# Options
#   --store plateau|sage-poppy|tablet|all
#   --hosted | --local | --offline   which cloud the store syncs to afterwards
#                                    (default: the one it syncs to now)
#   --days N                         past business days of demo sales (default 2)
#   --install-id ID                  store identity to adopt when switching clouds
#   --yes                            actually do it (without it: plan only, exit 1)
#
# DESKTOP STORES (Plateau :8080, Sage & Poppy :8082, on this Mac)
#   1. stop the store (and its login agent, if scripts/demo-autostart.sh installed one)
#   2. back up pos.db + photos/ receipts/ bills/ + store.env to <data dir>/backups/<time>/
#   3. recreate the database from the store's built-in seed (menu, staff, floor plan)
#      keeping: the store's sync identity, its id counters (new checks never reuse
#      a check number the portal already has) and its venue settings (Wi-Fi slip,
#      printer, receipt footer)
#   4. re-upload the menu photos from the backup
#   5. ring up N past days of sales through the store's own API, in the store's
#      time zone and currency (one shift per day, closed with a drawer count),
#      then open today's shift — all while the store is OFFLINE, then move those
#      sales onto the right days before anything syncs
#   6. restart it with the same settings (.demo/<store>/store.env: staff app MFA
#      off, digital receipts, cloud URL + key) and wait for it to sync
#   The first run captures .demo/<store>/store.env from the running store.
#
# THE TABLET (Vieux-Port, over adb): a SAFE reset only — its sales database is NOT
#   wiped (a release build's private files can't be written over adb, and clearing
#   app data would also delete store.properties and the store's cloud identity).
#   It backs up store.properties to this Mac (read-only), lists any open checks
#   left on the floor (close or void them on the tablet), and restarts the POS app.
#
# THE HOSTED PORTAL keeps old history: nothing here deletes cloud data. The plan
#   says what the portal will show and what to clean up by hand, if anything.
#
# Rehearse against a copy: POS_DEMO_ROOT=/some/dir (holding .demo/<store>/) uses
# that instead of this checkout.
set -euo pipefail
[[ -n "${DEMO_TRACE:-}" ]] && set -x

# shellcheck source=lib/demo-store.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/demo-store.sh"
HELPER="$REPO_ROOT/scripts/demo-reset-helper.py"

STORE=""; CLOUD_FLAG=""; YES=0; DAYS=2; INSTALL_ID_FLAG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --store) STORE="${2:-}"; shift 2;;
    --store=*) STORE="${1#*=}"; shift;;
    --hosted) CLOUD_FLAG=hosted; shift;;
    --local) CLOUD_FLAG=local; shift;;
    --offline) CLOUD_FLAG=offline; shift;;
    --days) DAYS="${2:-}"; shift 2;;
    --install-id) INSTALL_ID_FLAG="${2:-}"; shift 2;;
    --yes|-y) YES=1; shift;;
    -h|--help) sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) demo_err "unknown option $1 (see --help)"; exit 2;;
  esac
done
case "$STORE" in
  plateau|sage-poppy|tablet) STORES="$STORE";;
  all) STORES="plateau sage-poppy tablet";;
  *) demo_err "--store plateau|sage-poppy|tablet|all is required"; exit 2;;
esac
[[ "$DAYS" =~ ^[0-9]+$ && "$DAYS" -ge 1 && "$DAYS" -le 7 ]] || { demo_err "--days must be 1-7"; exit 2; }
for tool in sqlite3 python3 curl lsof; do
  command -v "$tool" >/dev/null 2>&1 || { demo_err "$tool is needed"; exit 1; }
done

helper() { python3 "$HELPER" "$@"; }
mask() { local v="$1"; [[ -n "$v" ]] && echo "…${v: -4}" || echo "(none)"; }
rel() { echo "${1#"$DEMO_ROOT"/}"; }

# ---------------------------------------------------------------------------------
# desktop stores
# ---------------------------------------------------------------------------------

# Sets: TARGET, OLD_DB, OLD_INSTALL, NEW_INSTALL, ID_FLOOR, SALE_KIND (+ store.env vars)
desktop_prepare() {
  local store="$1"
  [[ -f "$(demo_env_file "$store")" ]] || demo_capture_env "$store"
  demo_load_env "$store"
  TARGET="${CLOUD_FLAG:-$DEMO_CLOUD}"
  OLD_DB="$DEMO_DATA_DIR/pos.db"
  SALE_KIND=retail; [[ "$store" == plateau ]] && SALE_KIND=pub
  OLD_INSTALL=""; [[ -f "$OLD_DB" ]] && OLD_INSTALL="$(helper info --db "$OLD_DB" --field install_id)"
  ID_FLOOR=0
  if [[ "$TARGET" == "$DEMO_CLOUD" || "$TARGET" == offline ]]; then
    NEW_INSTALL="${INSTALL_ID_FLAG:-$OLD_INSTALL}"
  else
    # another cloud: its own pinned identity, and ids above anything it could hold
    if [[ "$TARGET" == hosted ]]; then NEW_INSTALL="${INSTALL_ID_FLAG:-${DEMO_HOSTED_INSTALL_ID:-}}"
    else NEW_INSTALL="${INSTALL_ID_FLAG:-${DEMO_LOCAL_INSTALL_ID:-}}"; fi
    ID_FLOOR=$(( ( ($(date +%s) - 1767225600) / 86400 ) * 100000 ))   # days since 2026-01-01 × 100k
  fi
  if [[ "$TARGET" == hosted && -z "$NEW_INSTALL" && "$DEMO_CLOUD" != hosted ]]; then
    demo_err "$store: switching to the hosted cloud needs the identity it pinned for this store"
    demo_err "  (DEMO_HOSTED_INSTALL_ID in $(rel "$(demo_env_file "$store")"), or --install-id). A new one would be refused (409 install_mismatch)."
    return 1
  fi
  demo_cloud_settings "$TARGET" || return 1
}

desktop_plan() {
  local store="$1" pid info checks max_check photos open_checks outbox hwm dates d i span=""
  pid="$(demo_running_pid)"
  echo "=== $(demo_display_name "$store")  (--store $store) ==="
  echo "  data dir      : $(rel "$DEMO_DATA_DIR")      settings: $(rel "$(demo_env_file "$store")")"
  echo "  running       : $([[ -n "$pid" ]] && echo "yes, pid $pid on :$POS_PORT" || echo "no (nothing on :$POS_PORT)")"
  if [[ -f "$OLD_DB" ]]; then
    info="$(helper info --db "$OLD_DB" --tz "${VENUE_TZ:-}")"
    span="$(echo "$info" | python3 -c 'import json,sys;print(json.load(sys.stdin)["sales_span"])')"
    checks="$(echo "$info" | python3 -c 'import json,sys;print(json.load(sys.stdin)["checks"])')"
    max_check="$(echo "$info" | python3 -c 'import json,sys;print(json.load(sys.stdin)["max_check"])')"
    open_checks="$(echo "$info" | python3 -c 'import json,sys;print(json.load(sys.stdin)["open_checks"])')"
    photos="$(echo "$info" | python3 -c 'import json,sys;print(json.load(sys.stdin)["photos"])')"
    outbox="$(echo "$info" | python3 -c 'import json,sys;print(json.load(sys.stdin)["outbox_max"])')"
    hwm="$(echo "$info" | python3 -c 'import json,sys;print(json.load(sys.stdin)["push_hwm"])')"
    echo "  database now  : $checks checks${span:+ ($span)}, last #$max_check, $open_checks open; $photos menu photos; sync identity $(mask "$OLD_INSTALL")"
    if [[ "$DEMO_CLOUD" != offline && "$hwm" -lt "$outbox" ]]; then
      echo "  !! $((outbox - hwm)) sync event(s) not yet sent to the cloud — they stay in the backup only"
    fi
  else
    max_check=0; photos=0
    echo "  database now  : none yet"
  fi
  echo "  cloud after   : $TARGET$([[ "$TARGET" != "$DEMO_CLOUD" ]] && echo " (switching from $DEMO_CLOUD)")$([[ -n "$DEMO_SYNC_URL" ]] && echo " → $DEMO_SYNC_URL, key $(mask "$DEMO_SYNC_KEY")")"
  echo "  keeps         : staff app MFA $POS_STAFF_APP_MFA · receipts $POS_PRINT_RECEIPTS · public URL $(demo_public_url || true) · VENUE_TZ ${VENUE_TZ:-default}"
  dates=""
  for ((i = DAYS; i >= 1; i--)); do
    d="$(TZ="${VENUE_TZ:-America/New_York}" date -v-"${i}"d '+%a %d %b')"; dates="$dates${dates:+, }$d"
  done
  echo "  steps:"
  demo_agent_loaded "$store" && echo "    0. unload its login agent ($(demo_agent_label "$store")); reloaded at the end"
  echo "    1. stop the store"
  echo "    2. back up pos.db, photos/, receipts/, bills/, store.env → $(rel "$DEMO_DATA_DIR")/backups/<time>/"
  echo "    3. recreate the database from the $store seed; keep sync identity $(mask "$NEW_INSTALL"), id counters$([[ "$ID_FLOOR" -gt 0 ]] && echo " (raised to $ID_FLOOR for the new cloud)"), venue settings"
  echo "    4. restore $photos menu photo(s) from the backup"
  echo "    5. seed $DAYS day(s) of $([[ $SALE_KIND == pub ]] && echo "pub checks (CAD, GST+QST)" || echo "counter sales (USD, CRV, sales tax, ID checks)") on $dates ($VENUE_TZ); open today's shift"
  echo "    6. restart on :$POS_PORT with store.env and wait for the first sync"
  if [[ "$TARGET" == hosted ]]; then
    echo "  the hosted portal afterwards:"
    echo "    - the store card goes back to online within ~10 s; the new days appear in reports, dashboard and tax report"
    echo "    - OLD history stays: checks #1–#$max_check${span:+ ($span)} remain in every report; on $dates"
    echo "      the new demo sales add ON TOP of any old sales from those same days"
    echo "    - menu, staff and photos are re-sent from the fresh seed (same item ids, so nothing doubles)"
    echo "    - devices paired to the old database stay on the Devices page until you remove them"
    echo "    MANUAL (you, not this script): to show only the fresh data, delete this venue's pre-reset"
    echo "    history on the hosted database — tenant copperlantern, venue ${POS_VENUE:-$store}, check ids <= $max_check"
    echo "    (and their lines/tenders/refunds), shifts and cash movements from before the reset."
  elif [[ "$TARGET" == local ]]; then
    echo "  the local portal (:3000) keeps its old history the same way; scripts/demo-down.sh --reset wipes it"
  fi
  echo
}

desktop_wait_sync() { # log_offset
  local offset="$1" i hwm outbox tail_log
  for ((i = 0; i < 45; i++)); do
    sleep 2
    tail_log="$(tail -c +"$((offset + 1))" "$DEMO_DATA_DIR/store.log" 2>/dev/null || true)"
    if echo "$tail_log" | grep -q "install_mismatch"; then
      demo_err "the cloud refused this store's identity (install_mismatch). It keeps selling offline."
      demo_err "  pass the identity the cloud pinned: --install-id <id> (see store.log)"; return 1
    fi
    hwm="$(helper info --db "$DEMO_DATA_DIR/pos.db" --field push_hwm)"
    outbox="$(helper info --db "$DEMO_DATA_DIR/pos.db" --field outbox_max)"
    if [[ "$hwm" -ge "$outbox" ]] && ! echo "$tail_log" | grep -qE "heartbeat failed|push failed"; then
      echo "  synced: all $outbox event(s) sent, heartbeat ok"
      return 0
    fi
  done
  demo_warn "not fully synced after 90 s (sent $hwm of $outbox). The store is up and sells offline; it keeps retrying."
  echo "$tail_log" | grep -E "WARN|failed" | tail -3 >&2 || true
  return 0
}

desktop_reset() {
  local store="$1" ts bk agent=0 offset install
  ts="$(date +%Y%m%d-%H%M%S)"
  bk="$DEMO_DATA_DIR/backups/$ts"
  echo ">>> resetting $(demo_display_name "$store")"
  # remember the identity this store holds on its current cloud, for switching back later
  if [[ -n "$OLD_INSTALL" && "$DEMO_CLOUD" == hosted ]]; then demo_file_set DEMO_HOSTED_INSTALL_ID "$OLD_INSTALL" "$(demo_env_file "$store")"; fi
  if [[ -n "$OLD_INSTALL" && "$DEMO_CLOUD" == local ]]; then demo_file_set DEMO_LOCAL_INSTALL_ID "$OLD_INSTALL" "$(demo_env_file "$store")"; fi

  if demo_agent_loaded "$store"; then
    agent=1
    launchctl bootout "gui/$(id -u)/$(demo_agent_label "$store")" 2>/dev/null || true
  fi
  echo "  stopping…"; demo_stop

  echo "  backing up → $(rel "$bk")"
  mkdir -p "$bk"; chmod 700 "$DEMO_DATA_DIR/backups" "$bk"
  if [[ -f "$OLD_DB" ]]; then
    sqlite3 "$OLD_DB" ".backup '$bk/pos.db'"
    [[ "$(sqlite3 "$bk/pos.db" 'PRAGMA integrity_check')" == ok ]] || { demo_err "backup failed its integrity check — nothing was deleted"; exit 1; }
  fi
  [[ -d "$DEMO_DATA_DIR/photos" ]] && cp -Rp "$DEMO_DATA_DIR/photos" "$bk/photos"
  cp -p "$(demo_env_file "$store")" "$bk/store.env"
  # receipts/bills belong to the old sales: move them into the backup
  local d; for d in receipts bills; do [[ -d "$DEMO_DATA_DIR/$d" ]] && mv "$DEMO_DATA_DIR/$d" "$bk/$d"; done
  rm -f "$OLD_DB" "$OLD_DB-wal" "$OLD_DB-shm" "$DEMO_DATA_DIR/.demo-seeded"

  echo "  recreating the database from the seed (offline)…"
  demo_start_bg offline >/dev/null; demo_stop
  install="$NEW_INSTALL"
  if [[ -f "$bk/pos.db" ]]; then
    helper prep --db "$OLD_DB" --old "$bk/pos.db" --install-id "$install" --id-floor "$ID_FLOOR"
  elif [[ -n "$install" ]]; then
    sqlite3 "$OLD_DB" "INSERT OR REPLACE INTO sync_state(key,value) VALUES('install_id','$install')"
  fi

  demo_start_bg offline >/dev/null
  [[ -d "$bk/photos" ]] && helper photos --url "http://localhost:$POS_PORT" --from "$bk/photos"
  echo "  seeding $DAYS day(s) of sales…"
  helper seed --url "http://localhost:$POS_PORT" --kind "$SALE_KIND" --days "$DAYS" --tz "$VENUE_TZ" --plan "$bk/seed-plan.json"
  demo_stop
  helper backdate --db "$OLD_DB" --plan "$bk/seed-plan.json" --tz "$VENUE_TZ" --kind "$SALE_KIND"
  touch "$DEMO_DATA_DIR/.demo-seeded"

  demo_file_set DEMO_CLOUD "$TARGET" "$(demo_env_file "$store")"
  DEMO_CLOUD="$TARGET"
  offset="$(wc -c < "$DEMO_DATA_DIR/store.log" | tr -d ' ')"
  echo "  starting with its cloud ($TARGET)…"
  if [[ "$agent" == 1 || -f "$(demo_agent_plist "$store")" ]]; then
    launchctl bootstrap "gui/$(id -u)" "$(demo_agent_plist "$store")"
    demo_wait_healthy "$POS_PORT" 60 || { demo_err "the login agent did not bring the store up — tail $(rel "$DEMO_DATA_DIR")/store.log"; exit 1; }
    echo "  up on :$POS_PORT (via its login agent)"
  else
    demo_start_bg "$TARGET"
  fi
  [[ "$TARGET" != offline ]] && desktop_wait_sync "$offset"
  echo "  done. Backup: $(rel "$bk")   (restore: stop the store, copy pos.db + photos back)"
  echo
}

# ---------------------------------------------------------------------------------
# the tablet
# ---------------------------------------------------------------------------------
PACKAGE="${POS_PACKAGE:-dev.dwhipstock.pos_client}"
PY_OPEN_TABLES="$(cat <<'PY'
import json, sys
zones = json.load(sys.stdin)
print(", ".join("%s (%s)" % (t.get("label", t["id"]), z.get("nameEn", ""))
                for z in zones for t in z.get("tables", []) if t.get("openCheckId")))
PY
)"
TABLET_FWD_PORT="${TABLET_FWD_PORT:-18080}"

tablet_plan() {
  local dev
  echo "=== $(demo_display_name tablet)  (--store tablet) ==="
  dev="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}' | head -1 || true)"
  echo "  adb device    : ${dev:-none connected}"
  echo "  steps (SAFE: the tablet's sales, menu, staff and identity are NOT touched):"
  echo "    1. copy store.properties from the tablet to .demo/tablet/backups/<time>/ (read-only)"
  echo "    2. list checks still open on the floor — close or void them on the tablet yourself"
  echo "    3. restart the POS app (clears a stuck screen, reconnects the printer) and wait for it"
  echo "  not done, on purpose: wiping its database. adb cannot write a release app's private"
  echo "  files, and clearing app data would also delete store.properties and the store's cloud"
  echo "  identity. Demo sales rung on the tablet stay in its history (and the portal's)."
  [[ -n "$CLOUD_FLAG" ]] && echo "  cloud: --$CLOUD_FLAG is not applied to the tablet; use scripts/tablet-cloud-config.sh for that"
  echo
}

tablet_reset() {
  local ts bk token zones open
  command -v adb >/dev/null 2>&1 || { demo_err "adb not found"; return 1; }
  [[ -n "$(adb devices | awk 'NR>1 && $2=="device"{print $1}')" ]] || { demo_err "no tablet on adb"; return 1; }
  echo ">>> tablet: safe reset"
  ts="$(date +%Y%m%d-%H%M%S)"; bk="$DEMO_ROOT/.demo/tablet/backups/$ts"
  mkdir -p "$bk"; chmod 700 "$DEMO_ROOT/.demo/tablet" "$DEMO_ROOT/.demo/tablet/backups" "$bk"
  if adb shell cat "/sdcard/Android/data/$PACKAGE/files/store.properties" > "$bk/store.properties" 2>/dev/null; then
    chmod 600 "$bk/store.properties"; echo "  store.properties copied → $(rel "$bk") (the tablet's copy is untouched)"
  else
    rm -f "$bk/store.properties"; echo "  no store.properties on the tablet (nothing to back up)"
  fi
  adb forward "tcp:$TABLET_FWD_PORT" tcp:8080 >/dev/null
  if curl -fsS -m 5 "http://localhost:$TABLET_FWD_PORT/health" >/dev/null 2>&1; then
    token="$(curl -fsS -m 5 -X POST "http://localhost:$TABLET_FWD_PORT/login" -H 'Content-Type: application/json' \
      -d "{\"pin\":\"${DEMO_MANAGER_PIN:-1234}\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])' 2>/dev/null || true)"
    if [[ -n "$token" ]]; then
      zones="$(curl -fsS -m 5 "http://localhost:$TABLET_FWD_PORT/zones" -H "Authorization: Bearer $token" || echo '[]')"
      open="$(echo "$zones" | python3 -c "$PY_OPEN_TABLES")"
      echo "  open checks on the floor: ${open:-none}"
    fi
  else
    echo "  the tablet's store did not answer on adb-forwarded :8080 (app closed?)"
  fi
  adb shell am force-stop "$PACKAGE"
  adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  local i; for ((i = 0; i < 40; i++)); do curl -fsS -m 2 "http://localhost:$TABLET_FWD_PORT/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -fsS -m 2 "http://localhost:$TABLET_FWD_PORT/health" >/dev/null 2>&1 && echo "  POS app restarted and answering" || demo_warn "the POS app did not answer after restart — open it on the tablet"
  adb forward --remove "tcp:$TABLET_FWD_PORT" >/dev/null 2>&1 || true
  echo
}

# ---------------------------------------------------------------------------------
echo "Demo reset — $(date '+%a %d %b %Y %H:%M')  (root: $DEMO_ROOT)"
echo
for s in $STORES; do
  if [[ "$s" == tablet ]]; then tablet_plan; else desktop_prepare "$s"; desktop_plan "$s"; fi
done
if [[ "$YES" != 1 ]]; then
  echo "Nothing changed. Re-run with --yes to do the above."
  exit 1
fi
for s in $STORES; do
  if [[ "$s" == tablet ]]; then tablet_reset; else desktop_prepare "$s"; desktop_reset "$s"; fi
done
echo "All done."
