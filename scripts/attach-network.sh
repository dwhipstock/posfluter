#!/usr/bin/env bash
# Re-point the ALREADY-RUNNING demo stack at the current network — run this after
# the Mac joins a new Wi-Fi (e.g. the bar's). It re-detects the Mac's LAN IP,
# rewrites POS_PUBLIC_URL in .env.local, and recreates ONLY the store container so
# scan-to-order QR codes + the cloud heartbeat use the new IP. No image rebuild.
#
# The other two moving parts on a new network handle themselves:
#   - Tablet → store: the app auto-rediscovers the store on the new subnet.
#   - Store → printer: open the app's Settings → "Scan network for printer" to
#     re-find it (its DHCP IP changes per network), then Save.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
COMPOSE_FILE="docker-compose.local.yml"
ENV_FILE=".env.local"

if docker compose version >/dev/null 2>&1; then
  dc() { docker compose "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
  dc() { docker-compose "$@"; }
else
  echo "ERROR: no docker compose available." >&2; exit 1
fi
[[ -f "$ENV_FILE" ]] || { echo "ERROR: $ENV_FILE missing — run scripts/demo-up.sh first." >&2; exit 1; }

LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
[[ -z "$LAN_IP" ]] && LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
[[ -z "$LAN_IP" ]] && { echo "ERROR: could not detect a LAN IP (en0/en1). Are you on Wi-Fi?" >&2; exit 1; }
PUBLIC_URL="http://${LAN_IP}:8080"

if grep -q '^POS_PUBLIC_URL=' "$ENV_FILE"; then
  sed -i.bak "s|^POS_PUBLIC_URL=.*|POS_PUBLIC_URL=${PUBLIC_URL}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
else
  echo "POS_PUBLIC_URL=${PUBLIC_URL}" >> "$ENV_FILE"
fi

echo "New LAN IP: ${LAN_IP} → POS_PUBLIC_URL=${PUBLIC_URL}"
echo "Recreating the store container with the new IP…"
dc -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d store

cat <<BANNER

============================================================
  Stack re-pointed at this network
============================================================
  Store  (POS)  : http://${LAN_IP}:8080/health
  Owner portal  : http://${LAN_IP}:3000
  Cloud API     : http://${LAN_IP}:8081/health

  Tablet : just relaunch the app — it auto-finds the store.
  Printer: app → Settings → "Scan network for printer" → Save.
============================================================
BANNER
