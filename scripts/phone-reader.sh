#!/usr/bin/env bash
# The owner's phone as the card reader (Stripe Tap to Pay on Android) for a US
# store: install the Card Reader app on the phone, point the store at it, and
# print the pairing steps.
#
#   scripts/phone-reader.sh --phone <serial> --tablet <serial> [--app sagepoppy|pronghorn]
#   scripts/phone-reader.sh --phone <serial>              # desktop store: prints its env instead
#   options: --real (real cards instead of Stripe's simulated reader)
#            --no-install (store setup only)   --apk <path>
#
# `adb devices` lists the serials. The phone needs Developer options + USB
# debugging ON only for the install; Stripe refuses Tap to Pay (even the
# simulated reader) while Developer options are on, so turn them OFF afterwards.
#
# Store side (tablet): merges into the POS app's store.properties
#   payment.terminal=tap_to_pay
#   payment.taptopay.simulated=true|false
#   stripe.secretKey=<STRIPE_KEY_US from the repo-root .env>   (never printed)
# then restarts the POS. The US stores need the USD test account: the store
# refuses a Stripe account whose currency isn't its own.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$REPO_ROOT/scripts/lib/tablet-app.sh"

APP=sagepoppy PHONE="" TABLET="" SIMULATED=true INSTALL=true
APK="$REPO_ROOT/client/build/app/outputs/flutter-apk/pos-reader-release.apk"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) APP="$2"; shift 2;;
    --phone) PHONE="$2"; shift 2;;
    --tablet) TABLET="$2"; shift 2;;
    --real) SIMULATED=false; shift;;
    --no-install) INSTALL=false; shift;;
    --apk) APK="$2"; shift 2;;
    -h|--help) sed -n '2,21p' "$0"; exit 0;;
    *) echo "unknown option $1 (see --help)" >&2; exit 2;;
  esac
done
[[ "$APP" == sagepoppy || "$APP" == pronghorn ]] || { echo "ERROR: --app must be sagepoppy or pronghorn (the US stores)." >&2; exit 2; }
tablet_app_set "$APP"
command -v adb >/dev/null || { echo "ERROR: adb not found." >&2; exit 1; }

env_value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/.env" 2>/dev/null \
    | tail -1 | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//' -e 's/[[:space:]]*$//'
}

# --- 1. the phone --------------------------------------------------------------
if [[ "$INSTALL" == true ]]; then
  [[ -n "$PHONE" ]] || { echo "ERROR: --phone <serial> (adb devices)." >&2; exit 2; }
  if [[ ! -f "$APK" ]]; then
    echo "Building the Card Reader app (release: Tap to Pay refuses debuggable apps)…"
    (cd "$REPO_ROOT/client" && flutter build apk --release --dart-define=POS_APP=reader)
    cp "$REPO_ROOT/client/build/app/outputs/flutter-apk/app-release.apk" "$APK"
  fi
  echo "Installing Card Reader on the phone ($PHONE)…"
  adb -s "$PHONE" install -r "$APK" >/dev/null
  echo "  installed dev.dwhipstock.pos_reader"
fi

# --- 2. the store ----------------------------------------------------------------
KEY="$(env_value STRIPE_KEY_US)"
if [[ -z "$KEY" || "$KEY" != sk_test_* ]]; then
  echo "ERROR: no sk_test_ STRIPE_KEY_US in $REPO_ROOT/.env (the US stores' USD test account)." >&2; exit 1
fi
if [[ -n "$TABLET" ]]; then
  export ANDROID_SERIAL="$TABLET"
  tablet_require_adb
  tablet_props_set 'payment\.terminal\|payment\.taptopay\.simulated\|stripe\.secretKey\|stripe\.locationId' \
    "payment.terminal=tap_to_pay" "payment.taptopay.simulated=$SIMULATED" "stripe.secretKey=$KEY"
  echo "Set payment.terminal=tap_to_pay, simulated=$SIMULATED and the US Stripe test key for $TABLET_APP_NAME."
  adb shell am force-stop "$PACKAGE"
  # am start, not monkey (monkey flips the tablet to portrait)
  ACTIVITY="$(adb shell cmd package resolve-activity --brief "$PACKAGE" | tr -d '\r' | tail -1)"
  adb shell am start -n "$ACTIVITY" >/dev/null
  echo "Restarted $TABLET_APP_NAME. Its log shows the pairing code:"
  echo "  adb -s $TABLET logcat -d | grep 'phone pairing code'"
  unset ANDROID_SERIAL
else
  cat <<EOF
No --tablet: for a desktop store, start it with (key from .env, not printed here):
  POS_PAYMENT_TERMINAL=tap_to_pay POS_TAPTOPAY_SIMULATED=$SIMULATED STRIPE_KEY_US=\$(sed -n 's/^STRIPE_KEY_US=//p' .env) …
  (the store log prints the phone pairing code)
EOF
fi

# --- 3. pairing ------------------------------------------------------------------
cat <<EOF

Pairing (once):
  1. On the phone: Settings → System → Developer options → OFF (Stripe refuses
     Tap to Pay while they're on, simulated reader included). NFC on, same Wi-Fi as the store.
  2. On the POS: Settings → Card terminal shows "Phone pairing code: NNNNNN".
  3. On the phone: open Card Reader. It finds the store on the Wi-Fi (or type
     its address, e.g. <tablet-ip>:$TABLET_STORE_PORT), enter the code, Pair. Allow location.
  4. The phone shows "Ready". On the POS, ring up a sale → Card (terminal).
     $( [[ "$SIMULATED" == true ]] && echo "The phone shows the amount: pick a Stripe test card, tap \"Tap card (simulated)\"." || echo "The phone shows Stripe's tap screen: tap the card on the back of the phone." )
  5. The POS records the sale (Stripe test mode).
EOF
