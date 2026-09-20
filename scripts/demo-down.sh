#!/usr/bin/env bash
# Tear down the LOCAL POS demo stack.
#   scripts/demo-down.sh          # stop + remove containers, KEEP data (pgdata, storedata)
#   scripts/demo-down.sh --reset  # also wipe named volumes (fresh db + fresh store SQLite)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
COMPOSE_FILE="docker-compose.local.yml"

if docker compose version >/dev/null 2>&1; then
  dc() { docker compose "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
  dc() { docker-compose "$@"; }
else
  echo "ERROR: no docker compose available." >&2; exit 1
fi

if [[ "${1:-}" == "--reset" ]]; then
  echo "Tearing down AND wiping volumes (pgdata + storedata)…"
  dc -f "$COMPOSE_FILE" down -v
  echo "Done. Next 'demo-up.sh' starts from an empty db + fresh store (re-seeds demo sales)."
else
  echo "Stopping the stack (data volumes preserved)…"
  dc -f "$COMPOSE_FILE" down
  echo "Done. Data kept. Re-run scripts/demo-up.sh to bring it back."
fi
