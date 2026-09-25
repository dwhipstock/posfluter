#!/usr/bin/env bash
# Run the acceptance e2e against the qa staging venues (default).
#
# Prerequisites (one-time): qa1 + qa2 provisioned under the `qa` tenant with a
# portal admin, e.g.
#   scripts/provision-venue.sh qa1 --name "QA One" --tenant qa --tenant-name "QA Staging" \
#       --admin-email qa@example.com --admin-password '<pw>' --image-tag <sha> --xmx 160m
#   scripts/provision-venue.sh qa2 --name "QA Two" --tenant qa --image-tag <sha> --xmx 160m
#
# Then:
#   E2E_PORTAL_ADMIN_EMAIL=qa@example.com E2E_PORTAL_ADMIN_PASSWORD='<pw>' scripts/e2e/run.sh
#
# First run enrolls the admin's TOTP and caches the secret next to this script
# (gitignored); later runs reuse it. Override any E2E_* to aim elsewhere.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

: "${E2E_PORTAL_ADMIN_EMAIL:?set E2E_PORTAL_ADMIN_EMAIL (the qa-tenant portal admin)}"
: "${E2E_PORTAL_ADMIN_PASSWORD:?set E2E_PORTAL_ADMIN_PASSWORD}"

export E2E_BASE_DOMAIN="${E2E_BASE_DOMAIN:-example.com}"
export E2E_PORTAL_BASE="${E2E_PORTAL_BASE:-https://copperlantern.example.com}"
export E2E_VENUE_A="${E2E_VENUE_A:-qa1}"
export E2E_VENUE_B="${E2E_VENUE_B:-qa2}"
SECRET_FILE="${E2E_TOTP_SECRET_FILE:-$HERE/.qa-totp-secret}"

echo "== e2e: $E2E_VENUE_A + $E2E_VENUE_B on $E2E_BASE_DOMAIN (portal $E2E_PORTAL_BASE) =="
exec python3 "$HERE/e2e.py" "$E2E_PORTAL_ADMIN_EMAIL" "$E2E_PORTAL_ADMIN_PASSWORD" "$SECRET_FILE"
