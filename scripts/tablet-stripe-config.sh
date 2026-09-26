#!/usr/bin/env bash
# Give the Android tablet store a Stripe TEST key for the "Card (Stripe)" tender.
#
#   scripts/tablet-stripe-config.sh          # push STRIPE_KEY from the repo-root .env
#   scripts/tablet-stripe-config.sh --off    # remove the key (Stripe disabled)
#
# --app copperlantern|sagepoppy picks which POS app on the tablet (default
# copperlantern; see scripts/lib/tablet-app.sh). Stripe is CAD-only, so the
# Sage & Poppy (USD) store keeps it off even with a key.
#
# Reads STRIPE_KEY (and optional STRIPE_LOCATION_ID) from the gitignored .env at
# the repo root (one line: STRIPE_KEY=sk_test_...). Only sk_test_ keys are
# accepted, here and again by the store. Merges stripe.secretKey=... (and
# stripe.locationId=...) into the app's external files dir
# (/sdcard/Android/data/<package>/files/store.properties), keeping every other
# line (e.g. print.receipts), then restarts the POS, which reads it at store
# startup. The key is never printed. Check the result with:
#   adb logcat -s TabletStore | grep "Stripe:"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OFF=false
[[ "${1:-}" == "--off" ]] && OFF=true
[[ -z "${1:-}" || "$OFF" == true ]] || { echo "usage: $0 [--off] [--app copperlantern|sagepoppy]" >&2; exit 2; }

# value of KEY in .env (last one wins), without quotes / CR
env_value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/.env" 2>/dev/null \
    | tail -1 | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//' -e 's/[[:space:]]*$//'
}

KEY=""
LOCATION=""
if [[ "$OFF" == false ]]; then
  [[ -f "$REPO_ROOT/.env" ]] || { echo "ERROR: $REPO_ROOT/.env not found (add a line STRIPE_KEY=sk_test_...)." >&2; exit 1; }
  KEY="$(env_value STRIPE_KEY)"
  LOCATION="$(env_value STRIPE_LOCATION_ID)"
  [[ -n "$KEY" ]] || { echo "ERROR: no STRIPE_KEY in $REPO_ROOT/.env." >&2; exit 1; }
  if [[ "$KEY" != sk_test_* ]]; then
    echo "ERROR: STRIPE_KEY is not a test key (must start with sk_test_). Live keys are not supported yet." >&2
    exit 1
  fi
  [[ "$TABLET_APP" == sagepoppy ]] && echo "NOTE: Stripe is CAD-only; the Sage & Poppy (USD) store keeps Card (Stripe) off." >&2
fi

tablet_require_adb
# keep any other settings already in the file; replace only the stripe.* lines
LINES=()
if [[ "$OFF" == false ]]; then
  LINES+=("stripe.secretKey=$KEY")
  [[ -n "$LOCATION" ]] && LINES+=("stripe.locationId=$LOCATION")
fi
tablet_props_set 'stripe\.secretKey\|stripe\.locationId' "${LINES[@]+"${LINES[@]}"}"
if [[ "$OFF" == true ]]; then
  echo "Removed the Stripe key from $TABLET_APP_NAME (Card (Stripe) disabled)."
else
  echo "Set stripe.secretKey (a test key) for $TABLET_APP_NAME${LOCATION:+ (location $LOCATION)}."
fi

tablet_restart_app
echo "Restarted $TABLET_APP_NAME. Confirm with: adb logcat -s TabletStore | grep 'Stripe:'"
