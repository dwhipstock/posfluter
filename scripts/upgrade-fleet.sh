#!/usr/bin/env bash
# upgrade-fleet.sh <tag> [venue ...] — roll the venue fleet to an ECR image tag,
# serially, CANARY FIRST, stopping on the first failure (S5c / rec 6).
#
# demo1 is the permanent canary: it always upgrades first, and if its gate fails
# (auto-rolled-back by upgrade-venue.sh) the walk stops before any real venue is
# touched. With no explicit list, remaining venues are discovered from the host,
# excluding the canary and an operator-defined denylist.
#
# Each venue goes through the full snapshot → upgrade → gate → auto-rollback path.
set -euo pipefail

HOST="${POS_CLOUD_HOST:-ubuntu@example.com}"
SSH_KEY="${POS_CLOUD_SSH_KEY:-$HOME/.ssh/pos-cloud.pem}"
REMOTE_ROOT="/home/ubuntu/pos"
CANARY="${POS_CANARY_VENUE:-demo1}"
# Never auto-sweep reserved administrative deployments.
DENYLIST="${POS_FLEET_DENYLIST:-main staging admin}"
HERE="$(cd "$(dirname "$0")" && pwd)"

die() { echo "ERROR: $*" >&2; exit 2; }
TAG="${1:-}"; [ -n "$TAG" ] || die "usage: upgrade-fleet.sh <tag> [venue ...]"
shift
EXPLICIT=("$@")

is_denied() { case " $DENYLIST " in *" $1 "*) return 0;; *) return 1;; esac; }

if [ "${#EXPLICIT[@]}" -gt 0 ]; then
  VENUES=("${EXPLICIT[@]}")
else
  # discover: dirs under venues/ that have a docker-compose.yml
  mapfile -t DISCOVERED < <(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$HOST" \
    "for d in $REMOTE_ROOT/venues/*/; do [ -f \"\$d/docker-compose.yml\" ] && basename \"\$d\"; done" 2>/dev/null | sort)
  VENUES=()
  for v in "${DISCOVERED[@]}"; do
    [ "$v" = "$CANARY" ] && continue
    is_denied "$v" && { echo "-- skipping denylisted venue '$v'"; continue; }
    VENUES+=("$v")
  done
fi

# canary always first (unless an explicit list omits it deliberately)
ORDER=()
if [ "${#EXPLICIT[@]}" -eq 0 ]; then ORDER+=("$CANARY"); fi
ORDER+=("${VENUES[@]}")

echo "== fleet upgrade to pos-store:$TAG =="
echo "-- order: ${ORDER[*]}"
echo

failed=""
for v in "${ORDER[@]}"; do
  echo "########## $v ##########"
  if "$HERE/upgrade-venue.sh" "$v" "$TAG"; then
    echo "---- $v: OK"
  else
    rc=$?
    failed="$v"
    echo "---- $v: FAILED (rc=$rc) — upgrade-venue.sh rolled it back. STOPPING the walk." >&2
    break
  fi
  echo
done

if [ -n "$failed" ]; then
  echo "== fleet upgrade HALTED at '$failed' (rolled back). Venues after it were not touched. ==" >&2
  exit 1
fi
echo "== fleet upgrade complete: ${ORDER[*]} on pos-store:$TAG =="
