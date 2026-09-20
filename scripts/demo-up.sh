#!/usr/bin/env bash
# Bring up the LOCAL POS demo stack (db + cloud api + owner portal + store) on this
# Mac, detached, and print the LAN URLs. Idempotent: safe to re-run; only rebuilds
# what changed. NOT for EC2 — that's cloud/infra/docker-compose.yml.
#
#   scripts/demo-up.sh            # build + start + seed demo sales
#   scripts/demo-up.sh --no-seed  # build + start, skip demo-sale generation
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

COMPOSE_FILE="docker-compose.local.yml"
ENV_FILE=".env.local"
SEED=1
[[ "${1:-}" == "--no-seed" ]] && SEED=0

# --- compose CLI detection (v2 plugin preferred, standalone fallback) ------------
if docker compose version >/dev/null 2>&1; then
  dc() { docker compose "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
  dc() { docker-compose "$@"; }
else
  echo "ERROR: neither 'docker compose' nor 'docker-compose' is available." >&2
  echo "Install Docker Desktop, or: brew install colima docker docker-compose && colima start" >&2
  exit 1
fi

# --- docker daemon reachable? ----------------------------------------------------
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon not reachable. If using colima: colima start" >&2
  exit 1
fi

# --- env file --------------------------------------------------------------------
if [[ ! -f "$ENV_FILE" ]]; then
  echo "No $ENV_FILE found — creating one from .env.local.example."
  cp .env.local.example "$ENV_FILE"
  # Generate a real store API key so cloud sync works out of the box.
  if command -v openssl >/dev/null 2>&1; then
    KEY="$(openssl rand -hex 32)"
    # portable in-place edit (BSD/GNU sed)
    sed -i.bak "s|^STORE_API_KEY=.*|STORE_API_KEY=${KEY}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
    echo "Generated a fresh STORE_API_KEY."
  fi
  echo ">>> Review $ENV_FILE (DB_PASSWORD, ADMIN_PASSWORD) before a real demo. <<<"
fi

# --- detect the Mac's LAN IP and write POS_PUBLIC_URL into .env.local ------------
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
[[ -z "$LAN_IP" ]] && LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
if [[ -z "$LAN_IP" ]]; then
  echo "WARN: could not auto-detect a LAN IP (en0/en1). Set POS_PUBLIC_URL in $ENV_FILE by hand." >&2
  LAN_IP="127.0.0.1"
fi
PUBLIC_URL="http://${LAN_IP}:8080"
if grep -q '^POS_PUBLIC_URL=' "$ENV_FILE"; then
  sed -i.bak "s|^POS_PUBLIC_URL=.*|POS_PUBLIC_URL=${PUBLIC_URL}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
else
  echo "POS_PUBLIC_URL=${PUBLIC_URL}" >> "$ENV_FILE"
fi
echo "LAN IP: ${LAN_IP}  →  POS_PUBLIC_URL=${PUBLIC_URL}"

# The owner reporting portal is a SEPARATE LAN service (the `web` container on
# :3000), not the compose-internal sync host (api:8081). Derive its URL from the
# same LAN IP and hand it to the store as REPORTING_PORTAL_URL so the Reports QR
# in Venue settings is phone-scannable instead of the dead internal `http://api:8081`.
PORTAL_URL="http://${LAN_IP}:3000"
if grep -q '^REPORTING_PORTAL_URL=' "$ENV_FILE"; then
  sed -i.bak "s|^REPORTING_PORTAL_URL=.*|REPORTING_PORTAL_URL=${PORTAL_URL}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
else
  echo "REPORTING_PORTAL_URL=${PORTAL_URL}" >> "$ENV_FILE"
fi
echo "Reports portal : ${PORTAL_URL}"

# --- build + start detached ------------------------------------------------------
echo "Building images and starting the stack (detached)…"
dc -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build

# --- wait for healthchecks -------------------------------------------------------
wait_healthy() {
  local svc="$1" tries="${2:-60}" cid state
  cid="$(dc -f "$COMPOSE_FILE" ps -q "$svc")"
  if [[ -z "$cid" ]]; then echo "  $svc: no container?!" >&2; return 1; fi
  for ((i=0; i<tries; i++)); do
    state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
    case "$state" in
      healthy|running) [[ "$state" == "healthy" ]] && { echo "  $svc: healthy"; return 0; } ;;
      exited|dead)     echo "  $svc: $state" >&2; return 1 ;;
    esac
    sleep 2
  done
  echo "  $svc: not healthy after $((tries*2))s (state=$state)" >&2
  return 1
}
echo "Waiting for healthchecks…"
wait_healthy db  30 || true
wait_healthy api 60 || echo "WARN: api not healthy yet — portal login may not work until it is." >&2
wait_healthy store 60 || echo "WARN: store not healthy yet." >&2

# --- seed demo sales -------------------------------------------------------------
if [[ "$SEED" == "1" ]]; then
  # Idempotent: the marker lives on the store's /data volume, so it's cleared by
  # 'demo-down.sh --reset' (which re-seeds) but survives an ordinary restart.
  if dc -f "$COMPOSE_FILE" exec -T store sh -c 'test -f /data/.demo-seeded' 2>/dev/null; then
    echo "Demo sales already seeded (found /data/.demo-seeded) — skipping."
  elif [[ -f scripts/demo-seed.py ]]; then
    echo "Seeding demo sales…"
    if STORE_URL="http://localhost:8080" python3 scripts/demo-seed.py; then
      dc -f "$COMPOSE_FILE" exec -T store sh -c 'touch /data/.demo-seeded' 2>/dev/null || true
    else
      echo "WARN: demo seed failed — reports may be empty. Re-run: python3 scripts/demo-seed.py" >&2
    fi
  else
    echo "  (scripts/demo-seed.py missing — skipping)"
  fi
fi

# --- summary ---------------------------------------------------------------------
cat <<BANNER

============================================================
  POS demo stack is UP
============================================================
  LAN IP        : ${LAN_IP}
  Store  (POS)  : http://${LAN_IP}:8080/health   (tablet points here)
  Owner portal  : http://${LAN_IP}:3000
  Cloud API     : http://${LAN_IP}:8081/health
  Scan-to-order : ${PUBLIC_URL}  (baked into table QR codes)

  Portal login  : $(grep '^ADMIN_EMAIL=' "$ENV_FILE" | cut -d= -f2)  /  (see ADMIN_PASSWORD in $ENV_FILE)
                  First login enrolls TOTP — scan the QR in an authenticator app.

  Printer (real): 192.168.123.100:9100  (set in portal/store settings; see runbook)
  Logs          : docker compose -f ${COMPOSE_FILE} logs -f store
  Tear down     : scripts/demo-down.sh            (keep data)
                  scripts/demo-down.sh --reset     (wipe volumes)
============================================================
BANNER
