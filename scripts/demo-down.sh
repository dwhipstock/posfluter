#!/usr/bin/env bash
# Tear down the LOCAL two-store demo (see demo-up.sh).
#   scripts/demo-down.sh          # stop the Plateau store + the cloud containers, KEEP data
#   scripts/demo-down.sh --reset  # also wipe the cloud db volume and the Plateau store
#                                 # (.demo/plateau) — the next demo-up starts fresh
# The Vieux-Port tablet is never touched; with the Mac down it keeps selling and
# only its sync pauses.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
COMPOSE_FILE="docker-compose.local.yml"
# compose project (volume prefix); override to run a throwaway copy side by side
PROJECT="${POS_DEMO_PROJECT:-pos-local}"
PLATEAU_DIR="$REPO_ROOT/.demo/plateau"
ENV_FILE=".env.local"
[[ -f "$ENV_FILE" ]] || ENV_FILE=".env.local.example"   # compose only needs it to parse
PID_FILE="$PLATEAU_DIR/store.pid"

if docker compose version >/dev/null 2>&1; then
  dc() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
  dc() { docker-compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
else
  echo "ERROR: no docker compose available." >&2; exit 1
fi

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Stopping the Plateau store (pid $(cat "$PID_FILE"))…"
  kill "$(cat "$PID_FILE")"
  for _ in $(seq 1 20); do kill -0 "$(cat "$PID_FILE")" 2>/dev/null || break; sleep 0.5; done
fi
rm -f "$PID_FILE"

if [[ "${1:-}" == "--reset" ]]; then
  echo "Tearing down AND wiping the cloud db volume + the Plateau store…"
  dc -f "$COMPOSE_FILE" down
  # only the cloud db: the legacy `storedata` volume (the old Mac store) is kept
  docker volume rm -f "${PROJECT}_pgdata" >/dev/null
  rm -rf "$PLATEAU_DIR"
  echo "Done. The next demo-up.sh starts from an empty cloud db and a fresh Plateau store."
  echo "(The tablet keeps its data; after a reset, re-run scripts/tablet-cloud-config.sh.)"
else
  echo "Stopping the cloud containers (data preserved)…"
  dc -f "$COMPOSE_FILE" down
  echo "Done. Data kept. Re-run scripts/demo-up.sh to bring it back."
fi
