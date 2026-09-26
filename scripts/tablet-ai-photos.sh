#!/usr/bin/env bash
# Turn the AI menu photos add-on on or off for the Android tablet store.
#
#   scripts/tablet-ai-photos.sh flux      # or gemini | openai: key from the repo-root .env
#   scripts/tablet-ai-photos.sh --off     # image.generation=off, key removed
#
# --app copperlantern|sagepoppy picks which POS app on the tablet (default
# copperlantern; see scripts/lib/tablet-app.sh).
#
# Reads the provider's key from the gitignored .env at the repo root
# (BFL_API_KEY / GEMINI_API_KEY / OPENAI_API_KEY) and merges
#   image.generation=on
#   image.provider=<provider>
#   image.<bfl|gemini|openai>.apiKey=<key>
# into the app's external files dir
# (/sdcard/Android/data/<package>/files/store.properties), keeping every other
# line, then restarts the POS, which reads it at store startup. The key is never
# printed. Check the result with:
#   adb logcat -s TabletStore | grep "AI photos:"
#
# Needs: adb with the tablet connected (USB debugging), the POS app installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() { echo "usage: $0 flux|gemini|openai|--off [--app copperlantern|sagepoppy]" >&2; exit 2; }
[[ $# -eq 1 ]] || usage

env_value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/.env" 2>/dev/null \
    | tail -1 | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//' -e 's/[[:space:]]*$//'
}

LINES=()
case "$1" in
  --off)
    LINES+=("image.generation=off")
    ;;
  flux|gemini|openai)
    PROVIDER="$1"
    case "$PROVIDER" in
      flux) ENV_NAME=BFL_API_KEY; PROP=image.bfl.apiKey ;;
      gemini) ENV_NAME=GEMINI_API_KEY; PROP=image.gemini.apiKey ;;
      openai) ENV_NAME=OPENAI_API_KEY; PROP=image.openai.apiKey ;;
    esac
    [[ -f "$REPO_ROOT/.env" ]] || { echo "ERROR: $REPO_ROOT/.env not found (add a line $ENV_NAME=...)." >&2; exit 1; }
    KEY="$(env_value "$ENV_NAME")"
    [[ -n "$KEY" ]] || { echo "ERROR: no $ENV_NAME in $REPO_ROOT/.env." >&2; exit 1; }
    LINES+=("image.generation=on" "image.provider=$PROVIDER" "$PROP=$KEY")
    ;;
  *) usage ;;
esac

tablet_require_adb
# replace only the image.* lines; every other setting stays
tablet_props_set 'image\.[A-Za-z.]*' "${LINES[@]}"
if [[ "$1" == --off ]]; then
  echo "AI photos off for $TABLET_APP_NAME (keys removed)."
else
  echo "AI photos on for $TABLET_APP_NAME via $PROVIDER (key set, not shown)."
fi

tablet_restart_app
echo "Restarted $TABLET_APP_NAME. Confirm with: adb logcat -s TabletStore | grep 'AI photos:'"
