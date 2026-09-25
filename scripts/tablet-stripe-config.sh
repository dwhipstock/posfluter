#!/usr/bin/env bash
# Give the Android tablet store a Stripe TEST key for the "Card (Stripe)" tender.
#
#   scripts/tablet-stripe-config.sh          # push STRIPE_KEY from the repo-root .env
#   scripts/tablet-stripe-config.sh --off    # remove the key (Stripe disabled)
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

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="${POS_PACKAGE:-dev.dwhipstock.pos_client}"
DIR="/sdcard/Android/data/$PACKAGE/files"
OFF=false
[[ "${1:-}" == "--off" ]] && OFF=true
[[ -z "${1:-}" || "$OFF" == true ]] || { echo "usage: $0 [--off]" >&2; exit 2; }

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
fi

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found (brew install --cask android-platform-tools)." >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "ERROR: no tablet on adb (USB debugging on? adb devices)." >&2; exit 1; }

TMP="$(mktemp -d)"
chmod 700 "$TMP"
trap 'rm -rf "$TMP"' EXIT
# keep any other settings already in the file; replace only the stripe.* lines
adb shell cat "$DIR/store.properties" 2>/dev/null | tr -d '\r' \
  | grep -v '^[[:space:]]*stripe\.\(secretKey\|locationId\)[[:space:]]*[=:]' > "$TMP/store.properties" || true
if [[ "$OFF" == false ]]; then
  printf 'stripe.secretKey=%s\n' "$KEY" >> "$TMP/store.properties"
  [[ -n "$LOCATION" ]] && printf 'stripe.locationId=%s\n' "$LOCATION" >> "$TMP/store.properties"
fi

adb shell mkdir -p "$DIR"
adb push "$TMP/store.properties" "$DIR/store.properties" >/dev/null
if [[ "$OFF" == true ]]; then
  echo "Removed the Stripe key from the tablet (Card (Stripe) disabled)."
else
  echo "Set stripe.secretKey (a test key) on the tablet${LOCATION:+ (location $LOCATION)}."
fi

adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "Restarted the POS. Confirm with: adb logcat -s TabletStore | grep 'Stripe:'"
