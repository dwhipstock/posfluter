#!/usr/bin/env bash
# Turn AI menu setup (menu from photos, menu chat) on or off for the Android
# tablet store.
#
#   scripts/tablet-ai-menu.sh gemini      # or openai | anthropic: key from the repo-root .env
#   scripts/tablet-ai-menu.sh --off       # menu.ai=off, key removed
#
# --app copperlantern|sagepoppy picks which POS app on the tablet (default
# copperlantern; see scripts/lib/tablet-app.sh).
#
# Reads the provider's key from the gitignored .env at the repo root
# (GEMINI_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY) and merges
#   menu.ai=on
#   menu.ai.provider=<provider>
#   menu.ai.<provider>.apiKey=<key>
# into the app's store.properties, keeping every other line, then restarts the
# POS. The key is never printed. Check the result with:
#   adb logcat -s TabletStore | grep "AI menu:"
#
# Windows / desktop store: put the same lines in the store.properties next to
# the store's data (POS_CONFIG_FILE), or set POS_MENU_AI=on,
# POS_MENU_AI_PROVIDER=<provider> and the key env var. See docs/ai-menu.md.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/tablet-app.sh"
tablet_app_parse "$@"; set -- "${TABLET_ARGS[@]+"${TABLET_ARGS[@]}"}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() { echo "usage: $0 gemini|openai|anthropic|--off [--app copperlantern|sagepoppy]" >&2; exit 2; }
[[ $# -eq 1 ]] || usage

env_value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/.env" 2>/dev/null \
    | tail -1 | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//' -e 's/[[:space:]]*$//'
}

LINES=()
case "$1" in
  --off)
    LINES+=("menu.ai=off")
    ;;
  gemini|openai|anthropic)
    PROVIDER="$1"
    case "$PROVIDER" in
      gemini) ENV_NAME=GEMINI_API_KEY ;;
      openai) ENV_NAME=OPENAI_API_KEY ;;
      anthropic) ENV_NAME=ANTHROPIC_API_KEY ;;
    esac
    [[ -f "$REPO_ROOT/.env" ]] || { echo "ERROR: $REPO_ROOT/.env not found (add a line $ENV_NAME=...)." >&2; exit 1; }
    KEY="$(env_value "$ENV_NAME")"
    [[ -n "$KEY" ]] || { echo "ERROR: no $ENV_NAME in $REPO_ROOT/.env." >&2; exit 1; }
    LINES+=("menu.ai=on" "menu.ai.provider=$PROVIDER" "menu.ai.$PROVIDER.apiKey=$KEY")
    ;;
  *) usage ;;
esac

tablet_require_adb
# replace only the menu.ai lines; every other setting stays
tablet_props_set 'menu\.ai[A-Za-z.]*' "${LINES[@]}"
if [[ "$1" == --off ]]; then
  echo "AI menu off for $TABLET_APP_NAME (keys removed)."
else
  echo "AI menu on for $TABLET_APP_NAME via $PROVIDER (key set, not shown)."
fi

tablet_restart_app
echo "Restarted $TABLET_APP_NAME. Confirm with: adb logcat -s TabletStore | grep 'AI menu:'"
