#!/usr/bin/env bash
# Provision (or update) ONE CLIENT's manager portal: its own database, API and
# branded web portal, behind the shared edge proxy. Same images for every
# client; this writes the client's runtime config.
#
#   cloud/infra/new-client.sh <client-id> <domain> [options]
#
#   cloud/infra/new-client.sh sagepoppy sagepoppy-manager.example.com \
#     --name "Sage & Poppy" --brand sagepoppy \
#     --store "sage-poppy=Sage & Poppy Bottle Shop" \
#     --zone America/Los_Angeles --currency USD --country US --retail \
#     --admin-email owner@example.com --image-tag <git-sha> --up
#
# Writes (all under clients/, gitignored):
#   clients/<id>/.env                 the client's settings + secrets (600)
#   clients/<id>/compose.sh           `docker compose` for this client (ps, logs, up -d, …)
#   clients/<id>/stores/<venue>.env   one per store: the store-side sync settings + key (600)
#   clients/proxy-sites/<id>.caddy    the client's hostname(s) on the edge proxy
#   clients/<id>/compose.override.yml only with --brand-dir (mounts a custom brand pack)
#
# Idempotent: re-running keeps every secret (DB password, admin password, store
# keys) and every setting not named on the command line; a new store gets a new
# key, existing stores keep theirs. Secrets are never printed — not in full,
# not in part. --dry-run prints the plan and writes nothing.
#
# Options
#   --name NAME            the group name the portal shows (VENUE_NAME)
#   --brand ID             portal brand pack: cloud/web/brands/<ID> (default: <client-id>)
#   --brand-dir DIR        a custom brand pack (brand.json + assets) mounted into the portal
#   --store ID=NAME        a store; repeat for each (first = primary). Replaces the store list.
#   --zone TZ              the stores' IANA time zone (VENUE_TZ; set when a store is first created)
#   --currency CUR         the stores' currency and the reporting currency (default CAD)
#   --country CC           the stores' country (default CA)
#   --retail               the stores are shops (stock pages, counter sales)
#   --admin-email EMAIL    first portal owner (TOTP enrolled at first sign-in)
#   --alias HOST           another hostname for the same portal (repeatable; e.g. an old name)
#   --registry R / --image-tag SHA         ECR images (pos-cloud-api / pos-cloud-web)
#   --api-image IMG / --web-image IMG      explicit images instead (local builds)
#   --project NAME         compose project (default <client-id>-portal)
#   --tenant ID            tenant id inside the database (default <client-id>; fixed forever)
#   --pg-volume NAME       use this EXISTING Postgres volume (adopting a running portal)
#   --from-env FILE        adopt: take settings and secrets from an existing .env
#   --local                plain-HTTP site, non-secure cookies (a laptop, never a server)
#   --no-totp              skip authenticator sign-in (isolated demos only)
#   --no-fx                drop the fixed conversion rates (a single-currency client)
#   --clients-dir DIR      default: cloud/infra/clients
#   --up                   start/refresh the containers and reload the edge proxy
#   --dry-run              show what would happen; write nothing, start nothing
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$INFRA_DIR/../.." && pwd)"
BRANDS_DIR="$REPO_ROOT/cloud/web/brands"

die() { echo "ERROR: $*" >&2; exit 1; }
usage() { sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-2}"; }

[[ $# -ge 1 && ( "$1" == -h || "$1" == --help ) ]] && usage 0
[[ $# -ge 2 ]] || usage
CLIENT_ID="$1"; DOMAIN_ARG="$2"; shift 2

F_NAME=""; F_BRAND=""; F_BRAND_DIR=""; F_STORES=(); F_ZONE=""; F_CURRENCY=""; F_COUNTRY=""; F_RETAIL=0
F_ADMIN=""; F_ALIASES=(); F_REGISTRY=""; F_TAG=""; F_API_IMAGE=""; F_WEB_IMAGE=""; F_PROJECT=""
F_TENANT=""; F_PG_VOLUME=""; F_FROM_ENV=""; LOCAL=0; NO_TOTP=0; NO_FX=0; UP=0; DRY=0
CLIENTS_DIR="${CLIENTS_DIR:-$INFRA_DIR/clients}"
while [[ $# -gt 0 ]]; do
  need() { [[ $# -ge 2 && -n "$2" ]] || die "$1 needs a value"; }
  case "$1" in
    --name) need "$@"; F_NAME="$2"; shift 2;;
    --brand) need "$@"; F_BRAND="$2"; shift 2;;
    --brand-dir) need "$@"; F_BRAND_DIR="$2"; shift 2;;
    --store) need "$@"; F_STORES+=("$2"); shift 2;;
    --zone) need "$@"; F_ZONE="$2"; shift 2;;
    --currency) need "$@"; F_CURRENCY="$(echo "$2" | tr '[:lower:]' '[:upper:]')"; shift 2;;
    --country) need "$@"; F_COUNTRY="$(echo "$2" | tr '[:lower:]' '[:upper:]')"; shift 2;;
    --retail) F_RETAIL=1; shift;;
    --admin-email) need "$@"; F_ADMIN="$2"; shift 2;;
    --alias) need "$@"; F_ALIASES+=("$2"); shift 2;;
    --registry) need "$@"; F_REGISTRY="$2"; shift 2;;
    --image-tag) need "$@"; F_TAG="$2"; shift 2;;
    --api-image) need "$@"; F_API_IMAGE="$2"; shift 2;;
    --web-image) need "$@"; F_WEB_IMAGE="$2"; shift 2;;
    --project) need "$@"; F_PROJECT="$2"; shift 2;;
    --tenant) need "$@"; F_TENANT="$2"; shift 2;;
    --pg-volume) need "$@"; F_PG_VOLUME="$2"; shift 2;;
    --from-env) need "$@"; F_FROM_ENV="$2"; shift 2;;
    --clients-dir) need "$@"; CLIENTS_DIR="$2"; shift 2;;
    --local) LOCAL=1; shift;;
    --no-totp) NO_TOTP=1; shift;;
    --no-fx) NO_FX=1; shift;;
    --up) UP=1; shift;;
    --dry-run) DRY=1; shift;;
    -h|--help) usage 0;;
    *) die "unknown option $1 (see --help)";;
  esac
done

# ---- validation -------------------------------------------------------------------
SLUG_RE='^[a-z0-9][a-z0-9-]{1,39}$'
HOST_RE='^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$'
[[ "$CLIENT_ID" =~ $SLUG_RE ]] || die "client id '$CLIENT_ID': lowercase letters, digits and dashes (2-40)"
[[ "$DOMAIN_ARG" =~ $HOST_RE ]] || die "domain '$DOMAIN_ARG' is not a hostname"
for a in ${F_ALIASES[@]+"${F_ALIASES[@]}"}; do [[ "$a" =~ $HOST_RE ]] || die "alias '$a' is not a hostname"; done
[[ -z "$F_TENANT" || "$F_TENANT" =~ $SLUG_RE ]] || die "tenant id '$F_TENANT': lowercase letters, digits and dashes"
[[ -z "$F_CURRENCY" || "$F_CURRENCY" =~ ^[A-Z]{3}$ ]] || die "--currency: a 3-letter code (CAD, USD, …)"
[[ -z "$F_COUNTRY" || "$F_COUNTRY" =~ ^[A-Z]{2}$ ]] || die "--country: a 2-letter code (CA, US, …)"
[[ -z "$F_ADMIN" || "$F_ADMIN" =~ ^[^@[:space:]]+@[^@[:space:]]+$ ]] || die "--admin-email: not an email address"
[[ -z "$F_FROM_ENV" || -f "$F_FROM_ENV" ]] || die "--from-env: $F_FROM_ENV not found"
if [[ -n "$F_BRAND_DIR" ]]; then
  [[ -f "$F_BRAND_DIR/brand.json" ]] || die "--brand-dir: $F_BRAND_DIR/brand.json not found"
  F_BRAND_DIR="$(cd "$F_BRAND_DIR" && pwd)"
fi
# values end up in a compose env file: keep them free of quoting/interpolation hazards
safe_value() { case "$2" in *'$'*|*'"'*|*"'"*|*'#'*|*'`'*|*$'\n'*) die "$1: must not contain \$ \" ' # or \`";; esac; }
safe_value --name "$F_NAME"
for s in ${F_STORES[@]+"${F_STORES[@]}"}; do
  safe_value --store "$s"
  [[ "$s" == *=* ]] || die "--store '$s': use <venue-id>=<name>"
  vid="${s%%=*}"; vname="${s#*=}"
  [[ "$vid" =~ ^[a-z0-9][a-z0-9-]*$ ]] || die "--store '$s': the venue id is lowercase letters, digits and dashes"
  [[ -n "$vname" && "$vname" != *,* ]] || die "--store '$s': the name is required and has no commas"
done

CLIENT_DIR="$CLIENTS_DIR/$CLIENT_ID"
ENV_FILE="$CLIENT_DIR/.env"
SITES_DIR="$CLIENTS_DIR/proxy-sites"
SITE_FILE="$SITES_DIR/$CLIENT_ID.caddy"
OVERRIDE_FILE="$CLIENT_DIR/compose.override.yml"
COMPOSE_SH="$CLIENT_DIR/compose.sh"
rel() { case "$1" in "$REPO_ROOT"/*) echo "${1#"$REPO_ROOT"/}";; *) echo "$1";; esac; }

# ---- existing settings (re-run) or the adopted stack's (--from-env) ----------------
[[ -f "$ENV_FILE" ]] && EXISTING=1 || EXISTING=0
SOURCE_FILE=""
if [[ "$EXISTING" == 1 ]]; then SOURCE_FILE="$ENV_FILE"; elif [[ -n "$F_FROM_ENV" ]]; then SOURCE_FILE="$F_FROM_ENV"; fi
old() { [[ -n "$SOURCE_FILE" ]] && { grep "^$1=" "$SOURCE_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true; } || true; }
pick() { # pick <flag value> <key> <default>: flag, else existing, else default
  if [[ -n "$1" ]]; then echo "$1"; else local v; v="$(old "$2")"; [[ -n "$v" ]] && echo "$v" || echo "$3"; fi
}
gen_hex() { openssl rand -hex 32; }
gen_pw() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 24; }
SECRET_NOTES=()
secret() { # secret <key> <generator>: sets $<key> — the existing value, else a new one
  local v; v="$(old "$1")"
  if [[ -n "$v" && "$v" != change-me* && "$v" != replace-with-* ]]; then SECRET_NOTES+=("$1: kept")
  else SECRET_NOTES+=("$1: generated"); v="$("$2")"; fi
  printf -v "$1" '%s' "$v"
}

PROJECT="$(pick "$F_PROJECT" COMPOSE_PROJECT_NAME "$CLIENT_ID-portal")"
[[ "$PROJECT" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "project '$PROJECT': lowercase letters, digits, dashes, underscores"
TENANT_ID="$(pick "$F_TENANT" TENANT_ID "$CLIENT_ID")"
if [[ "$EXISTING" == 1 && -n "$F_TENANT" && "$F_TENANT" != "$(old TENANT_ID)" && -n "$(old TENANT_ID)" ]]; then
  die "the tenant id is fixed for the life of the client's database ($(old TENANT_ID)); refusing to change it"
fi
VENUE_NAME="$(pick "$F_NAME" VENUE_NAME "")"
[[ -n "$VENUE_NAME" ]] || die "--name is required for a new client"
BRAND="$(pick "$F_BRAND" PORTAL_BRAND "$CLIENT_ID")"
if [[ -z "$F_BRAND_DIR" ]]; then
  [[ -f "$BRANDS_DIR/$BRAND/brand.json" ]] || die "no brand pack cloud/web/brands/$BRAND (use --brand <id> or --brand-dir <dir>)"
fi
VENUE_TZ="$(pick "$F_ZONE" VENUE_TZ "America/New_York")"
ADMIN_EMAIL="$(pick "$F_ADMIN" ADMIN_EMAIL "")"
[[ -n "$ADMIN_EMAIL" ]] || die "--admin-email is required for a new client"

# stores: the flags replace the list; else keep the existing one
if [[ ${#F_STORES[@]} -gt 0 ]]; then
  STORES="$(IFS=,; echo "${F_STORES[*]}")"
else
  STORES="$(old STORES)"
fi
[[ -n "$STORES" ]] || die "at least one --store <venue-id>=<name> is required"
VENUES=()
while IFS= read -r pair; do [[ -n "$pair" ]] && VENUES+=("${pair%%=*}"); done < <(echo "$STORES" | tr ',' '\n' | sed 's/^ *//;s/ *$//')
[[ ${#VENUES[@]} -gt 0 ]] || die "STORES is empty"
dup="$(printf '%s\n' "${VENUES[@]}" | sort | uniq -d | head -1)"
[[ -z "$dup" ]] || die "store '$dup' is listed twice"

join_pairs() { # join_pairs <value>: "v1=<value>,v2=<value>" over every venue
  local out="" v; for v in "${VENUES[@]}"; do out="${out:+$out,}$v=$1"; done; echo "$out"
}
if [[ -n "$F_CURRENCY" ]]; then
  REPORTING_CURRENCY="$F_CURRENCY"
  [[ "$F_CURRENCY" == CAD ]] && STORE_CURRENCIES="" || STORE_CURRENCIES="$(join_pairs "$F_CURRENCY")"
else
  REPORTING_CURRENCY="$(pick "" REPORTING_CURRENCY CAD)"; STORE_CURRENCIES="$(old STORE_CURRENCIES)"
fi
if [[ -n "$F_COUNTRY" ]]; then
  [[ "$F_COUNTRY" == CA ]] && STORE_COUNTRIES="" || STORE_COUNTRIES="$(join_pairs "$F_COUNTRY")"
else
  STORE_COUNTRIES="$(old STORE_COUNTRIES)"
fi
if [[ "$F_RETAIL" == 1 ]]; then RETAIL_STORES="$(IFS=,; echo "${VENUES[*]}")"; else RETAIL_STORES="$(old RETAIL_STORES)"; fi
STORE_ZONES="$(old STORE_ZONES)"
FX_USD_CAD="$(old FX_USD_CAD)"; FX_CAD_USD="$(old FX_CAD_USD)"
[[ "$NO_FX" == 1 ]] && { FX_USD_CAD=""; FX_CAD_USD=""; }
# per-store lists only name stores the client still has (a store taken off the
# list takes its zone/currency/country/retail entries with it)
listed() { printf '%s\n' "${VENUES[@]}" | grep -qx "$1"; }
prune_pairs() { # "a=x,b=y" → only the listed venues
  local out="" p; for p in $(echo "$1" | tr ',' ' '); do listed "${p%%=*}" && out="${out:+$out,}$p"; done; echo "$out"
}
prune_ids() { # "a,b" → only the listed venues
  local out="" v; for v in $(echo "$1" | tr ',' ' '); do listed "$v" && out="${out:+$out,}$v"; done; echo "$out"
}
STORE_ZONES="$(prune_pairs "$STORE_ZONES")"
STORE_CURRENCIES="$(prune_pairs "$STORE_CURRENCIES")"
STORE_COUNTRIES="$(prune_pairs "$STORE_COUNTRIES")"
RETAIL_STORES="$(prune_ids "$RETAIL_STORES")"

if [[ ${#F_ALIASES[@]} -gt 0 ]]; then DOMAIN_ALIASES="$(IFS=,; echo "${F_ALIASES[*]}")"
else DOMAIN_ALIASES="$(old DOMAIN_ALIASES)"; [[ -z "$DOMAIN_ALIASES" && -n "$F_FROM_ENV" ]] && DOMAIN_ALIASES="$(old LEGACY_DOMAIN)"; fi
DOMAIN="$DOMAIN_ARG"
if [[ "$LOCAL" == 1 ]]; then COOKIE_SECURE=false; SCHEME=http; else COOKIE_SECURE="$(pick "" COOKIE_SECURE true)"; SCHEME="$( [[ "$COOKIE_SECURE" == false ]] && echo http || echo https)"; fi
# the portal's public base URL: a plain-HTTP (local) proxy on a non-standard port
# (clients/proxy.env HTTP_PORT) puts the port in the URLs the stores get
BASE_URL="$SCHEME://$DOMAIN"
if [[ "$SCHEME" == http && -f "$CLIENTS_DIR/proxy.env" ]]; then
  pport="$(grep '^HTTP_PORT=' "$CLIENTS_DIR/proxy.env" | tail -1 | cut -d= -f2- || true)"
  [[ -n "$pport" && "$pport" != 80 ]] && BASE_URL="$BASE_URL:$pport"
fi
[[ "$NO_TOTP" == 1 ]] && TOTP_REQUIRED=false || TOTP_REQUIRED="$(pick "" TOTP_REQUIRED true)"
PUBLIC_BASE_DOMAIN="$(pick "" PUBLIC_BASE_DOMAIN "")"
REGISTRY="$(pick "$F_REGISTRY" REGISTRY "")"
IMAGE_TAG="$(pick "$F_TAG" IMAGE_TAG "")"
WEB_IMAGE_TAG="$( [[ -n "$F_TAG" ]] && echo "" || old WEB_IMAGE_TAG)"
API_IMAGE="$(pick "$F_API_IMAGE" API_IMAGE "")"
WEB_IMAGE="$(pick "$F_WEB_IMAGE" WEB_IMAGE "")"
[[ -n "$API_IMAGE" || ( -n "$REGISTRY" && -n "$IMAGE_TAG" ) ]] || die "images: --registry + --image-tag (or --api-image / --web-image)"
[[ -n "$WEB_IMAGE" || ( -n "$REGISTRY" && -n "$IMAGE_TAG" ) ]] || die "images: --registry + --image-tag (or --web-image)"
PG_VOLUME="$(pick "$F_PG_VOLUME" PG_VOLUME "${PROJECT}_pgdata")"
EDGE_NETWORK="$(pick "" EDGE_NETWORK pos-edge)"
SESSION_IDLE="$(pick "" PORTAL_SESSION_IDLE_MINUTES 60)"
SESSION_MAX="$(pick "" PORTAL_SESSION_MAX_HOURS 12)"
if [[ -n "$F_BRAND_DIR" ]]; then BRAND_DIR_IN_CONTAINER=/brand; BRAND_HOST_DIR="$F_BRAND_DIR"
elif [[ -f "$OVERRIDE_FILE" ]]; then BRAND_DIR_IN_CONTAINER="$(old PORTAL_BRAND_DIR_IN_CONTAINER)"; BRAND_HOST_DIR=""
else BRAND_DIR_IN_CONTAINER=""; BRAND_HOST_DIR=""; fi

# secrets: kept on re-runs, minted once
secret DB_PASSWORD gen_pw
secret ADMIN_PASSWORD gen_pw
# store keys: venue → key from STORE_API_KEY (the old primary) + STORE_API_KEYS
OLD_PRIMARY="$(old STORES | cut -d, -f1 | cut -d= -f1 | sed 's/^ *//;s/ *$//')"
old_key_of() { # old_key_of <venue>
  local v="$1" k
  if [[ -n "$OLD_PRIMARY" && "$v" == "$OLD_PRIMARY" ]]; then k="$(old STORE_API_KEY)"; [[ -n "$k" && "$k" != replace-with-* ]] && { echo "$k"; return; }; fi
  old STORE_API_KEYS | tr ',' '\n' | sed 's/^ *//;s/ *$//' | grep "^$v=" | head -1 | cut -d= -f2- || true
}
STORE_API_KEY=""; STORE_API_KEYS=""; KEY_NOTES=(); STORE_KEYS=()
for v in "${VENUES[@]}"; do
  k="$(old_key_of "$v")"
  if [[ -n "$k" && "$k" != replace-with-* ]]; then KEY_NOTES+=("$v: kept"); else k="$(gen_hex)"; KEY_NOTES+=("$v: generated"); fi
  STORE_KEYS+=("$k")
  if [[ -z "$STORE_API_KEY" ]]; then STORE_API_KEY="$k"; else STORE_API_KEYS="${STORE_API_KEYS:+$STORE_API_KEYS,}$v=$k"; fi
done
DROPPED=()
while IFS= read -r ov; do
  [[ -z "$ov" ]] && continue
  printf '%s\n' "${VENUES[@]}" | grep -qx "$ov" || DROPPED+=("$ov")
done < <(old STORES | tr ',' '\n' | cut -d= -f1 | sed 's/^ *//;s/ *$//')

# extra keys a hand edit added (FX_EUR_CAD, RESET_TOTP_EMAIL, …): carried over as-is
KNOWN_KEYS="CLIENT_ID COMPOSE_PROJECT_NAME DOMAIN DOMAIN_ALIASES TENANT_ID VENUE_NAME VENUE_TZ PORTAL_BRAND PORTAL_BRAND_DIR_IN_CONTAINER STORES STORE_ZONES STORE_CURRENCIES STORE_COUNTRIES RETAIL_STORES REPORTING_CURRENCY FX_USD_CAD FX_CAD_USD PUBLIC_BASE_DOMAIN COOKIE_SECURE TOTP_REQUIRED ADMIN_EMAIL ADMIN_PASSWORD DB_PASSWORD STORE_API_KEY STORE_API_KEYS REGISTRY IMAGE_TAG WEB_IMAGE_TAG API_IMAGE WEB_IMAGE PG_VOLUME EDGE_NETWORK PORTAL_SESSION_IDLE_MINUTES PORTAL_SESSION_MAX_HOURS"
# an adopted manager stack's keys that mean nothing to a client instance
ADOPT_SKIP="LEGACY_DOMAIN STORE_DOMAIN ACME_EMAIL BASE_DOMAIN AWS_REGION"
EXTRA_LINES=""
if [[ -n "$SOURCE_FILE" ]]; then
  while IFS= read -r line; do
    key="${line%%=*}"
    [[ "$line" == *=* && "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
    case " $KNOWN_KEYS $ADOPT_SKIP " in *" $key "*) continue;; esac
    EXTRA_LINES="${EXTRA_LINES}${line}"$'\n'
  done < "$SOURCE_FILE"
fi

# ---- render ---------------------------------------------------------------------------
render_env() {
  cat <<EOF
# Client portal: $VENUE_NAME ($CLIENT_ID). Written by cloud/infra/new-client.sh:
# re-run it to change settings (values below that the command line doesn't name
# are kept, secrets always). Holds secrets — 600, never commit, never paste.
CLIENT_ID=$CLIENT_ID
COMPOSE_PROJECT_NAME=$PROJECT
DOMAIN=$DOMAIN
DOMAIN_ALIASES=$DOMAIN_ALIASES
# the tenant id inside this client's database: fixed for the life of the database
TENANT_ID=$TENANT_ID
VENUE_NAME=$VENUE_NAME
VENUE_TZ=$VENUE_TZ
PORTAL_BRAND=$BRAND
PORTAL_BRAND_DIR_IN_CONTAINER=$BRAND_DIR_IN_CONTAINER
STORES=$STORES
STORE_ZONES=$STORE_ZONES
STORE_CURRENCIES=$STORE_CURRENCIES
STORE_COUNTRIES=$STORE_COUNTRIES
RETAIL_STORES=$RETAIL_STORES
REPORTING_CURRENCY=$REPORTING_CURRENCY
FX_USD_CAD=$FX_USD_CAD
FX_CAD_USD=$FX_CAD_USD
PUBLIC_BASE_DOMAIN=$PUBLIC_BASE_DOMAIN
COOKIE_SECURE=$COOKIE_SECURE
TOTP_REQUIRED=$TOTP_REQUIRED
PORTAL_SESSION_IDLE_MINUTES=$SESSION_IDLE
PORTAL_SESSION_MAX_HOURS=$SESSION_MAX
ADMIN_EMAIL=$ADMIN_EMAIL
ADMIN_PASSWORD=$ADMIN_PASSWORD
DB_PASSWORD=$DB_PASSWORD
STORE_API_KEY=$STORE_API_KEY
STORE_API_KEYS=$STORE_API_KEYS
REGISTRY=$REGISTRY
IMAGE_TAG=$IMAGE_TAG
WEB_IMAGE_TAG=$WEB_IMAGE_TAG
API_IMAGE=$API_IMAGE
WEB_IMAGE=$WEB_IMAGE
PG_VOLUME=$PG_VOLUME
EDGE_NETWORK=$EDGE_NETWORK
EOF
  if [[ -n "$EXTRA_LINES" ]]; then
    echo "# carried over from $( [[ "$EXISTING" == 1 ]] && echo "the previous version of this file" || echo "the adopted .env")"
    printf '%s' "$EXTRA_LINES"
  fi
}

render_site() {
  local hosts="" h
  for h in "$DOMAIN" $(echo "$DOMAIN_ALIASES" | tr ',' ' '); do
    [[ -n "$h" ]] && hosts="${hosts:+$hosts, }$( [[ "$SCHEME" == http ]] && echo "http://$h" || echo "$h")"
  done
  cat <<EOF
# $VENUE_NAME ($CLIENT_ID) — generated by cloud/infra/new-client.sh; re-run it
# to change this file. Routes this client's hostname(s) to its own api/web on
# the shared edge network, nowhere else.
$hosts {
	encode gzip

	handle /health {
		reverse_proxy $CLIENT_ID-api:8081
	}

	handle /v1/* {
		reverse_proxy $CLIENT_ID-api:8081
	}

	handle {
		reverse_proxy $CLIENT_ID-web:3000
	}
}
EOF
}

render_store_env() { # render_store_env <venue> <key>
  local zone
  zone="$(echo "$STORE_ZONES" | tr ',' '\n' | grep "^$1=" | head -1 | cut -d= -f2- || true)"
  cat <<EOF
# Store-side cloud settings for store '$1' of $VENUE_NAME ($CLIENT_ID).
# Generated by cloud/infra/new-client.sh. Holds the store's key: copy this file
# to the store (desktop store.env / tablet-cloud-config.sh), never paste the key.
POS_VENUE=$1
VENUE_TZ=${zone:-$VENUE_TZ}
CLOUD_SYNC_URL=$BASE_URL
CLOUD_SYNC_API_KEY=$2
REPORTING_PORTAL_URL=$BASE_URL
EOF
}

render_override() {
  cat <<EOF
# Generated by cloud/infra/new-client.sh --brand-dir: this client's own brand
# pack, mounted into the portal (read-only). No image rebuild needed.
services:
  web:
    volumes:
      - "$BRAND_HOST_DIR:/brand:ro"
EOF
}

render_compose_sh() {
  cat <<EOF
#!/usr/bin/env bash
# docker compose for client '$CLIENT_ID' (generated by cloud/infra/new-client.sh):
#   $(rel "$COMPOSE_SH") ps | logs -f api | up -d | stop api | …
set -euo pipefail
here="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
files=(-f "$INFRA_DIR/docker-compose.client.yml")
[[ -f "\$here/compose.override.yml" ]] && files+=(-f "\$here/compose.override.yml")
exec docker compose -p "$PROJECT" --env-file "\$here/.env" "\${files[@]}" "\$@"
EOF
}

write_file() { # write_file <path> <mode> < content — atomic, never world-readable
  local path="$1" mode="$2" tmp
  mkdir -p "$(dirname "$path")"
  tmp="$(mktemp "$path.XXXXXX")"
  chmod 600 "$tmp"
  cat > "$tmp"
  chmod "$mode" "$tmp"
  mv "$tmp" "$path"
}

# ---- plan -------------------------------------------------------------------------------
echo "Client $CLIENT_ID — $VENUE_NAME$( [[ "$DRY" == 1 ]] && echo "   (DRY RUN: nothing is written or started)")"
echo "  portal      : $BASE_URL$( [[ -n "$DOMAIN_ALIASES" ]] && echo "  (also: $DOMAIN_ALIASES)")"
echo "  brand       : $( [[ -n "$F_BRAND_DIR" ]] && echo "custom pack $F_BRAND_DIR" || echo "$BRAND (cloud/web/brands/$BRAND)")"
echo "  tenant      : $TENANT_ID   project: $PROJECT   database volume: $PG_VOLUME"
echo "  stores      : $STORES"
echo "  money       : $REPORTING_CURRENCY$( [[ -n "$RETAIL_STORES" ]] && echo "   retail: $RETAIL_STORES")   zone: $VENUE_TZ"
echo "  images      : ${API_IMAGE:-$REGISTRY/pos-cloud-api:$IMAGE_TAG}, ${WEB_IMAGE:-$REGISTRY/pos-cloud-web:${WEB_IMAGE_TAG:-$IMAGE_TAG}}"
echo "  secrets     : ${SECRET_NOTES[*]}; store keys — ${KEY_NOTES[*]} (values never shown)"
[[ "$EXISTING" == 0 && -n "$F_FROM_ENV" ]] && echo "  adopting    : settings and secrets from $F_FROM_ENV"
[[ ${#DROPPED[@]} -gt 0 ]] && echo "  NOTE        : no longer listed: ${DROPPED[*]} — their keys leave this file; the database keeps its history and key rows (see docs/hosted-client-split.md for removing a store's data)"
[[ "$COOKIE_SECURE" == false && "$LOCAL" == 0 ]] && echo "  WARNING     : COOKIE_SECURE=false outside --local"
[[ "$TOTP_REQUIRED" == false ]] && echo "  NOTE        : TOTP_REQUIRED=false — authenticator sign-in is off (isolated demos only)"

if [[ "$DRY" == 1 ]]; then
  echo "  would write : $(rel "$ENV_FILE") (600), $(rel "$COMPOSE_SH"), $(rel "$SITE_FILE")"
  for v in "${VENUES[@]}"; do echo "                $(rel "$CLIENT_DIR/stores/$v.env") (600)"; done
  [[ -n "$F_BRAND_DIR" ]] && echo "                $(rel "$OVERRIDE_FILE")"
  [[ "$UP" == 1 ]] && echo "  would start : $PROJECT (db, api, web) and reload the edge proxy"
  exit 0
fi

# ---- write ------------------------------------------------------------------------------
umask 077
mkdir -p "$CLIENT_DIR/stores" "$SITES_DIR"
chmod 700 "$CLIENT_DIR" "$CLIENT_DIR/stores"
render_env | write_file "$ENV_FILE" 600
render_compose_sh | write_file "$COMPOSE_SH" 700
render_site | write_file "$SITE_FILE" 644
i=0
for v in "${VENUES[@]}"; do
  render_store_env "$v" "${STORE_KEYS[$i]}" | write_file "$CLIENT_DIR/stores/$v.env" 600
  i=$((i + 1))
done
for d in ${DROPPED[@]+"${DROPPED[@]}"}; do rm -f "$CLIENT_DIR/stores/$d.env"; done
if [[ -n "$F_BRAND_DIR" ]]; then render_override | write_file "$OVERRIDE_FILE" 644; fi

echo "  wrote       : $(rel "$ENV_FILE"), $(rel "$COMPOSE_SH"), $(rel "$SITE_FILE")"

# ---- start ------------------------------------------------------------------------------
if [[ "$UP" == 1 ]]; then
  command -v docker >/dev/null || die "docker is needed for --up"
  if ! docker volume inspect "$PG_VOLUME" >/dev/null 2>&1; then
    # an adopted client names a volume that MUST already exist: never start it on an empty one
    [[ -n "$F_PG_VOLUME" || -n "$F_FROM_ENV" ]] && die "database volume $PG_VOLUME does not exist — refusing to start an adopted client on an empty database"
    docker volume create "$PG_VOLUME" >/dev/null
    echo "  created     : database volume $PG_VOLUME"
  fi
  CLIENTS_DIR="$CLIENTS_DIR" "$INFRA_DIR/proxy.sh" up
  "$COMPOSE_SH" up -d
  echo "  waiting for the api to be healthy…"
  cid="$("$COMPOSE_SH" ps -q api)"
  for ((n = 0; n < 90; n++)); do
    st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
    [[ "$st" == healthy ]] && break
    [[ "$st" == exited || "$st" == dead ]] && die "the api stopped — $(rel "$COMPOSE_SH") logs api"
    sleep 2
  done
  [[ "$st" == healthy ]] || die "the api is not healthy after 3 minutes — $(rel "$COMPOSE_SH") logs api"
  CLIENTS_DIR="$CLIENTS_DIR" "$INFRA_DIR/proxy.sh" reload
fi

# ---- the store side -----------------------------------------------------------------------
echo
echo "  Store-side settings (each file holds that store's key — copy the file, never paste the key):"
for v in "${VENUES[@]}"; do
  printf '    %-14s CLOUD_SYNC_URL=%s  → %s\n' "$v" "$BASE_URL" "$(rel "$CLIENT_DIR/stores/$v.env")"
done
echo "  Portal sign-in: $ADMIN_EMAIL, password = ADMIN_PASSWORD in $(rel "$ENV_FILE")$( [[ "$TOTP_REQUIRED" == true ]] && echo " (authenticator enrolled at first sign-in)")"
CD_ARG=""; [[ "$CLIENTS_DIR" != "$INFRA_DIR/clients" ]] && CD_ARG=" --clients-dir $CLIENTS_DIR"
[[ "$UP" == 1 ]] || echo "  Start it     : $(rel "$0") $CLIENT_ID $DOMAIN$CD_ARG --up"
