# shellcheck shell=bash
# Shared helpers for the Mac demo stores (Plateau, Sage & Poppy): where each
# store's data and settings live, and how to start/stop one with the same env
# every time. Sourced by scripts/demo-reset.sh and scripts/demo-autostart.sh.
# Written for macOS /bin/bash 3.2 (launchd runs it): no associative arrays.
#
# Each store has ONE settings file, .demo/<store>/store.env (gitignored, 600):
#
#   DEMO_DATA_DIR=.demo/sage-poppy-hosted   # pos.db, photos/, receipts/, bills/, store.log
#   DEMO_CLOUD=hosted                       # hosted | local | offline
#   DEMO_HOSTED_SYNC_URL=https://…          # the hosted cloud + this store's key
#   DEMO_HOSTED_API_KEY=…
#   DEMO_HOSTED_PORTAL_URL=https://…
#   DEMO_HOSTED_INSTALL_ID=…                # the store identity the hosted cloud pinned
#   DEMO_LOCAL_INSTALL_ID=…                 # same, for the local Docker cloud (demo-up.sh)
#   DEMO_JAVA=/opt/homebrew/opt/openjdk@17/bin/java
#   DEMO_JAR=…/server/build/libs/pos-server-all.jar
#   POS_VENUE=sage-poppy  POS_PORT=8082  VENUE_TZ=…  POS_STAFF_APP_MFA=off
#   POS_PRINT_RECEIPTS=digital  POS_PUBLIC_URL=auto  CLOUD_SYNC_INTERVAL_SECONDS=10 …
#
# Every POS_*, VENUE_TZ, STRIPE_* and CLOUD_SYNC_INTERVAL_SECONDS line is passed
# to the store as-is. The paths (POS_DB, POS_*_DIR) always come from DEMO_DATA_DIR
# and the cloud URL/key from DEMO_CLOUD, so the file never goes stale.
# POS_PUBLIC_URL=auto means "this Mac's current LAN address" at each start.

DEMO_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEMO_LIB_DIR/../.." && pwd)"
# where .demo/ and .env.local live; override to rehearse against a scratch copy
DEMO_ROOT="${POS_DEMO_ROOT:-$REPO_ROOT}"

demo_err() { echo "ERROR: $*" >&2; }
demo_warn() { echo "WARN: $*" >&2; }

# the desktop stores this Mac runs, and their defaults
demo_known_store() { case "$1" in plateau|sage-poppy) return 0;; *) return 1;; esac; }
demo_default_port() { case "$1" in plateau) echo 8080;; sage-poppy) echo 8082;; esac; }
demo_display_name() {
  case "$1" in
    plateau) echo "Copper Lantern — Plateau";;
    sage-poppy) echo "Sage & Poppy Bottle Shop";;
    tablet) echo "Copper Lantern — Vieux-Port (tablet)";;
    *) echo "$1";;
  esac
}
# key name in .env.local for the local Docker cloud (demo-up.sh)
demo_local_key_name() { case "$1" in plateau) echo STORE_API_KEY_PLATEAU;; sage-poppy) echo STORE_API_KEY_SAGE_POPPY;; esac; }

demo_env_file() { echo "$DEMO_ROOT/.demo/$1/store.env"; }

demo_lan_ip() {
  local ip
  ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
  [[ -z "$ip" ]] && ip="$(ipconfig getifaddr en1 2>/dev/null || true)"
  echo "$ip"
}

# abs path for a DEMO_DATA_DIR value (relative ones are relative to DEMO_ROOT)
demo_abs() { case "$1" in /*) echo "$1";; *) echo "$DEMO_ROOT/$1";; esac; }

# pid listening on a TCP port (empty if none)
demo_port_pid() { { lsof -nP -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null || true; } | head -1; }

# value of KEY in an env-style file (last one wins), without sourcing it
demo_file_get() { [[ -f "$2" ]] && grep "^$1=" "$2" | tail -1 | cut -d= -f2- || true; }

# set/replace KEY=VALUE in an env-style file (BSD sed safe; values are not echoed)
demo_file_set() {
  local key="$1" value="$2" file="$3" tmp
  tmp="$(mktemp "${file}.XXXXXX")"
  { grep -v "^$key=" "$file" 2>/dev/null || true; printf '%s=%s\n' "$key" "$value"; } > "$tmp"
  chmod 600 "$tmp"; mv "$tmp" "$file"
}

# Load .demo/<store>/store.env into the current shell (DEMO_* and POS_* vars).
demo_load_env() {
  local store="$1" file
  file="$(demo_env_file "$store")"
  [[ -f "$file" ]] || { demo_err "$file not found (run scripts/demo-reset.sh --store $store to capture it)"; return 1; }
  set -a
  # shellcheck disable=SC1090
  source "$file"
  set +a
  DEMO_STORE="$store"
  DEMO_DATA_DIR="$(demo_abs "${DEMO_DATA_DIR:-.demo/$store}")"
  DEMO_CLOUD="${DEMO_CLOUD:-offline}"
  POS_PORT="${POS_PORT:-$(demo_default_port "$store")}"
  # demo defaults the meeting depends on, even if a line went missing
  POS_STAFF_APP_MFA="${POS_STAFF_APP_MFA:-off}"
  POS_PRINT_RECEIPTS="${POS_PRINT_RECEIPTS:-digital}"
  DEMO_JAVA="${DEMO_JAVA:-$(command -v java || true)}"
  DEMO_JAR="${DEMO_JAR:-$REPO_ROOT/server/build/libs/pos-server-all.jar}"
}

# Capture .demo/<store>/store.env from the store running on its port (first run).
# Reads the live process env (same user) — so the restart uses exactly what runs now.
demo_capture_env() {
  local store="$1" port pid file dir env_lines db_path jar cwd cloud url
  port="$(demo_default_port "$store")"
  file="$(demo_env_file "$store")"
  pid="$(demo_port_pid "$port")"
  [[ -n "$pid" ]] || { demo_err "no store is listening on :$port to capture settings from, and $file does not exist"; return 1; }
  env_lines="$(ps eww -o command= -p "$pid" | tr ' ' '\n' | grep -E '^(POS_[A-Z_]+|VENUE_TZ|CLOUD_SYNC_[A-Z_]+|REPORTING_PORTAL_URL|STRIPE_[A-Z_]+)=' || true)"
  [[ -n "$env_lines" ]] || { demo_err "could not read the env of pid $pid on :$port"; return 1; }
  getv() { printf '%s\n' "$env_lines" | grep "^$1=" | tail -1 | cut -d= -f2-; }
  [[ "$(getv POS_VENUE)" == "$store" ]] || { demo_err "the store on :$port is POS_VENUE=$(getv POS_VENUE), not $store"; return 1; }
  db_path="$(getv POS_DB)"
  [[ -n "$db_path" ]] || { cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"; db_path="$cwd/pos.db"; }
  dir="$(dirname "$db_path")"
  case "$dir" in "$DEMO_ROOT"/*) dir="${dir#"$DEMO_ROOT"/}";; esac
  jar="$(ps -o command= -p "$pid" | sed -n 's/.*-jar \([^ ]*\).*/\1/p')"
  url="$(getv CLOUD_SYNC_URL)"
  case "$url" in
    "") cloud=offline;;
    http://localhost:*|http://127.0.0.1:*|http://192.168.*|http://10.*) cloud=local;;
    *) cloud=hosted;;
  esac
  mkdir -p "$(dirname "$file")"
  umask 077
  {
    echo "# Demo store settings for $(demo_display_name "$store"). Gitignored; holds this"
    echo "# store's cloud key — never commit or paste it. Captured $(date '+%Y-%m-%d %H:%M')"
    echo "# from the running store (pid $pid). Used by scripts/demo-reset.sh and demo-autostart.sh."
    echo "DEMO_DATA_DIR=$dir"
    echo "DEMO_CLOUD=$cloud"
    if [[ "$cloud" == hosted ]]; then
      echo "DEMO_HOSTED_SYNC_URL=$url"
      echo "DEMO_HOSTED_API_KEY=$(getv CLOUD_SYNC_API_KEY)"
      echo "DEMO_HOSTED_PORTAL_URL=$(getv REPORTING_PORTAL_URL)"
    fi
    echo "DEMO_JAVA=$(command -v java || true)"
    echo "DEMO_JAR=${jar:-$REPO_ROOT/server/build/libs/pos-server-all.jar}"
    printf '%s\n' "$env_lines" | grep -vE '^(POS_DB|POS_RECEIPTS_DIR|POS_BILLS_DIR|POS_PHOTOS_DIR|CLOUD_SYNC_URL|CLOUD_SYNC_API_KEY|REPORTING_PORTAL_URL|POS_PUBLIC_URL)=' || true
    # a LAN address goes stale when DHCP moves the Mac; re-detect at every start
    case "$(getv POS_PUBLIC_URL)" in ""|http://192.168.*|http://10.*|http://172.*) echo "POS_PUBLIC_URL=auto";; *) echo "POS_PUBLIC_URL=$(getv POS_PUBLIC_URL)";; esac
  } > "$file"
  chmod 600 "$file"
  unset -f getv
  echo "Captured the running store's settings into ${file#"$DEMO_ROOT"/} (cloud: $cloud)."
}

# Sets DEMO_SYNC_URL / DEMO_SYNC_KEY / DEMO_PORTAL_URL for cloud $1 (hosted|local|offline).
demo_cloud_settings() {
  local cloud="$1" lan keyname
  DEMO_SYNC_URL=""; DEMO_SYNC_KEY=""; DEMO_PORTAL_URL=""
  case "$cloud" in
    hosted)
      DEMO_SYNC_URL="${DEMO_HOSTED_SYNC_URL:-}"; DEMO_SYNC_KEY="${DEMO_HOSTED_API_KEY:-}"
      DEMO_PORTAL_URL="${DEMO_HOSTED_PORTAL_URL:-$DEMO_SYNC_URL}"
      [[ -n "$DEMO_SYNC_URL" && -n "$DEMO_SYNC_KEY" ]] || { demo_err "no hosted cloud URL/key in $(demo_env_file "$DEMO_STORE") (DEMO_HOSTED_SYNC_URL / DEMO_HOSTED_API_KEY)"; return 1; }
      ;;
    local)
      keyname="$(demo_local_key_name "$DEMO_STORE")"
      DEMO_SYNC_URL="http://localhost:8081"
      DEMO_SYNC_KEY="$(demo_file_get "$keyname" "$DEMO_ROOT/.env.local")"
      lan="$(demo_lan_ip)"; DEMO_PORTAL_URL="http://${lan:-localhost}:3000"
      [[ -n "$DEMO_SYNC_KEY" && "$DEMO_SYNC_KEY" != replace-with-* ]] || { demo_err "no $keyname in .env.local — run scripts/demo-up.sh once to mint the local keys"; return 1; }
      ;;
    offline) ;;
    *) demo_err "unknown cloud '$cloud' (hosted|local|offline)"; return 1;;
  esac
}

demo_public_url() {
  local lan
  if [[ "${POS_PUBLIC_URL:-auto}" == auto ]]; then
    lan="$(demo_lan_ip)"
    [[ -n "$lan" ]] && echo "http://$lan:$POS_PORT"
  else
    echo "$POS_PUBLIC_URL"
  fi
}

# Print the store's environment (NAME=VALUE lines) for cloud $1. Secrets included —
# only ever fed to `env`, never echoed.
demo_store_env_lines() {
  local cloud="$1" pub
  demo_cloud_settings "$cloud" || return 1
  pub="$(demo_public_url)"
  env | grep -E '^(POS_[A-Z_]+|VENUE_TZ|STRIPE_[A-Z_]+|CLOUD_SYNC_INTERVAL_SECONDS)=' \
    | grep -vE '^(POS_DB|POS_RECEIPTS_DIR|POS_BILLS_DIR|POS_PHOTOS_DIR|POS_PUBLIC_URL|POS_DEMO_ROOT)='
  echo "POS_DB=$DEMO_DATA_DIR/pos.db"
  echo "POS_RECEIPTS_DIR=$DEMO_DATA_DIR/receipts"
  echo "POS_BILLS_DIR=$DEMO_DATA_DIR/bills"
  echo "POS_PHOTOS_DIR=$DEMO_DATA_DIR/photos"
  [[ -n "$pub" ]] && echo "POS_PUBLIC_URL=$pub"
  if [[ -n "$DEMO_SYNC_URL" ]]; then
    echo "CLOUD_SYNC_URL=$DEMO_SYNC_URL"
    echo "CLOUD_SYNC_API_KEY=$DEMO_SYNC_KEY"
    echo "REPORTING_PORTAL_URL=$DEMO_PORTAL_URL"
    [[ -n "${CLOUD_SYNC_INTERVAL_SECONDS:-}" ]] || echo "CLOUD_SYNC_INTERVAL_SECONDS=10"
  fi
}

demo_healthy() { curl -fsS -m 3 "http://localhost:$1/health" >/dev/null 2>&1; }

demo_wait_healthy() { # port [seconds]
  local i
  for ((i = 0; i < ${2:-60}; i++)); do demo_healthy "$1" && return 0; sleep 1; done
  return 1
}

demo_running_pid() { # the store's pid: pid file if alive, else whoever holds the port
  local pidf="$DEMO_DATA_DIR/store.pid" pid=""
  [[ -f "$pidf" ]] && pid="$(cat "$pidf")"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then echo "$pid"; return; fi
  demo_port_pid "$POS_PORT"
}

# Start the store in the background for cloud $1 (hosted|local|offline).
demo_start_bg() {
  local cloud="$1" envfile pid
  [[ -x "$DEMO_JAVA" ]] || { demo_err "java not found (DEMO_JAVA=$DEMO_JAVA)"; return 1; }
  [[ -f "$DEMO_JAR" ]] || { demo_err "store server jar missing: $DEMO_JAR (cd server && ./gradlew buildFatJar)"; return 1; }
  if [[ -n "$(demo_port_pid "$POS_PORT")" ]]; then demo_err "port $POS_PORT is busy"; return 1; fi
  mkdir -p "$DEMO_DATA_DIR/receipts" "$DEMO_DATA_DIR/bills" "$DEMO_DATA_DIR/photos"
  envfile="$(mktemp)"; chmod 600 "$envfile"
  demo_store_env_lines "$cloud" > "$envfile" || { rm -f "$envfile"; return 1; }
  local -a envargs=()
  local line
  while IFS= read -r line; do [[ -n "$line" ]] && envargs+=("$line"); done < "$envfile"
  rm -f "$envfile"
  (
    cd "$DEMO_DATA_DIR" || exit 1
    nohup env "${envargs[@]}" "$DEMO_JAVA" -jar "$DEMO_JAR" >> "$DEMO_DATA_DIR/store.log" 2>&1 &
    echo $! > "$DEMO_DATA_DIR/store.pid"
  )
  pid="$(cat "$DEMO_DATA_DIR/store.pid")"
  demo_wait_healthy "$POS_PORT" 60 || { demo_err "store did not come up on :$POS_PORT — tail $DEMO_DATA_DIR/store.log"; return 1; }
  echo "  up on :$POS_PORT (pid $pid, cloud: $cloud)"
}

# Foreground (exec) start — what the launchd agent runs.
demo_exec_fg() {
  local cloud="${DEMO_CLOUD}" lines i
  # at login Wi-Fi may still be joining: give the LAN address up to a minute
  if [[ "${POS_PUBLIC_URL:-auto}" == auto ]]; then
    for ((i = 0; i < 60; i++)); do [[ -n "$(demo_lan_ip)" ]] && break; sleep 1; done
  fi
  mkdir -p "$DEMO_DATA_DIR/receipts" "$DEMO_DATA_DIR/bills" "$DEMO_DATA_DIR/photos"
  lines="$(demo_store_env_lines "$cloud")" || exit 1
  local -a envargs=()
  local line
  while IFS= read -r line; do [[ -n "$line" ]] && envargs+=("$line"); done <<< "$lines"
  cd "$DEMO_DATA_DIR" || exit 1
  echo $$ > "$DEMO_DATA_DIR/store.pid"
  exec env "${envargs[@]}" "$DEMO_JAVA" -jar "$DEMO_JAR"
}

demo_stop() {
  local pid i
  pid="$(demo_running_pid)"
  if [[ -z "$pid" ]]; then rm -f "$DEMO_DATA_DIR/store.pid"; return 0; fi
  kill "$pid" 2>/dev/null || true
  for ((i = 0; i < 40; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  if kill -0 "$pid" 2>/dev/null; then demo_warn "pid $pid ignored SIGTERM; forcing"; kill -9 "$pid" 2>/dev/null || true; sleep 1; fi
  for ((i = 0; i < 20; i++)); do [[ -z "$(demo_port_pid "$POS_PORT")" ]] && break; sleep 0.5; done
  rm -f "$DEMO_DATA_DIR/store.pid"
}

# launchd (scripts/demo-autostart.sh)
demo_agent_label() { echo "dev.dwhipstock.pos.demo.$1"; }
demo_agent_plist() { echo "$HOME/Library/LaunchAgents/$(demo_agent_label "$1").plist"; }
demo_agent_loaded() { launchctl print "gui/$(id -u)/$(demo_agent_label "$1")" >/dev/null 2>&1; }
