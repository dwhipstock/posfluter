#!/usr/bin/env bash
# Tests for cloud/infra/new-client.sh — no Docker, no network, a scratch
# clients dir. Run: bash cloud/infra/tests/new-client-test.sh
#   1. --dry-run writes nothing and prints no secret
#   2. a real run writes the env (600), store files, the proxy site, compose.sh
#   3. re-running is idempotent: every secret and key is kept
#   4. adding a store keeps the old keys and mints one new key
#   5. adopting a running stack (--from-env) keeps its secrets and database volume
#   6. nothing it prints contains a secret; bad input is refused
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../new-client.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
CL="$TMP/clients"
PASS=0
ok() { PASS=$((PASS + 1)); echo "  ok  $*"; }
bad() { echo "  FAIL $*" >&2; exit 1; }
getv() { grep "^$1=" "$2" | tail -1 | cut -d= -f2-; }
mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
no_secrets_in() { # no_secrets_in <output> <env file>
  local out="$1" envf="$2" k v
  for k in DB_PASSWORD ADMIN_PASSWORD STORE_API_KEY; do
    v="$(getv "$k" "$envf")"
    [[ -z "$v" ]] && continue
    grep -qF "$v" <<<"$out" && bad "output contains $k"
    grep -qF "${v: -6}" <<<"$out" && bad "output contains part of $k"
  done
  for pair in $(getv STORE_API_KEYS "$envf" | tr ',' ' '); do
    v="${pair#*=}"; grep -qF "$v" <<<"$out" && bad "output contains a store key"
  done
  grep -qE '[0-9a-f]{32}' <<<"$out" && bad "output contains a long hex string"
  return 0
}

SP_ARGS=(sagepoppy sagepoppy-manager.example.com --clients-dir "$CL"
  --name "Sage & Poppy" --brand sagepoppy --store "sage-poppy=Sage & Poppy Bottle Shop"
  --zone America/Los_Angeles --currency USD --country US --retail
  --admin-email owner@example.com --registry registry.example.com --image-tag abc123)

echo "new-client.sh"

# 1. dry run
out="$("$SCRIPT" "${SP_ARGS[@]}" --dry-run)"
[[ ! -e "$CL" ]] || bad "dry run created $CL"
grep -q "DRY RUN" <<<"$out" || bad "dry run does not say so"
grep -q "would write" <<<"$out" || bad "dry run prints no plan"
grep -q "sage-poppy" <<<"$out" || bad "dry run does not list the store"
grep -qE '[0-9a-f]{32}' <<<"$out" && bad "dry run printed a hex secret"
ok "dry run writes nothing, prints the plan, no secrets"

# 2. real run
out="$("$SCRIPT" "${SP_ARGS[@]}")"
ENV="$CL/sagepoppy/.env"
[[ -f "$ENV" ]] || bad "no .env"
[[ "$(mode "$ENV")" == 600 ]] || bad ".env mode $(mode "$ENV")"
[[ "$(mode "$CL/sagepoppy/stores/sage-poppy.env")" == 600 ]] || bad "store env not 600"
[[ -x "$CL/sagepoppy/compose.sh" ]] || bad "no compose.sh"
[[ "$(getv TENANT_ID "$ENV")" == sagepoppy ]] || bad "tenant"
[[ "$(getv PORTAL_BRAND "$ENV")" == sagepoppy ]] || bad "brand"
[[ "$(getv STORES "$ENV")" == "sage-poppy=Sage & Poppy Bottle Shop" ]] || bad "stores: $(getv STORES "$ENV")"
[[ "$(getv STORE_CURRENCIES "$ENV")" == "sage-poppy=USD" ]] || bad "currency"
[[ "$(getv REPORTING_CURRENCY "$ENV")" == USD ]] || bad "reporting currency"
[[ "$(getv STORE_COUNTRIES "$ENV")" == "sage-poppy=US" ]] || bad "country"
[[ "$(getv RETAIL_STORES "$ENV")" == sage-poppy ]] || bad "retail"
[[ "$(getv VENUE_TZ "$ENV")" == America/Los_Angeles ]] || bad "zone"
[[ "$(getv COOKIE_SECURE "$ENV")" == true ]] || bad "secure cookies by default"
[[ "$(getv TOTP_REQUIRED "$ENV")" == true ]] || bad "TOTP by default"
[[ "$(getv PG_VOLUME "$ENV")" == sagepoppy-portal_pgdata ]] || bad "pg volume"
[[ "$(getv STORE_API_KEY "$ENV")" =~ ^[0-9a-f]{64}$ ]] || bad "store key not 64 hex"
[[ "$(getv DB_PASSWORD "$ENV")" =~ ^[A-Za-z0-9]{24}$ ]] || bad "db password"
[[ "$(getv ADMIN_PASSWORD "$ENV")" =~ ^[A-Za-z0-9]{24}$ ]] || bad "admin password"
[[ -z "$(getv STORE_API_KEYS "$ENV")" ]] || bad "one store → no STORE_API_KEYS"
SITE="$CL/proxy-sites/sagepoppy.caddy"
grep -q "^sagepoppy-manager.example.com {" "$SITE" || bad "site host"
grep -q "reverse_proxy sagepoppy-api:8081" "$SITE" || bad "site api"
grep -q "reverse_proxy sagepoppy-web:3000" "$SITE" || bad "site web"
SENV="$CL/sagepoppy/stores/sage-poppy.env"
[[ "$(getv CLOUD_SYNC_URL "$SENV")" == https://sagepoppy-manager.example.com ]] || bad "store sync url"
[[ "$(getv CLOUD_SYNC_API_KEY "$SENV")" == "$(getv STORE_API_KEY "$ENV")" ]] || bad "store key file"
[[ "$(getv VENUE_TZ "$SENV")" == America/Los_Angeles ]] || bad "store zone"
no_secrets_in "$out" "$ENV"
ok "real run: env 600, settings, proxy site, store file; no secrets printed"

# 3. idempotent
before="$(cat "$ENV")"
out="$("$SCRIPT" "${SP_ARGS[@]}")"
[[ "$(cat "$ENV")" == "$before" ]] || { diff <(echo "$before") "$ENV" || true; bad "re-run changed the env"; }
grep -q "DB_PASSWORD: kept" <<<"$out" || bad "re-run does not say kept"
out="$("$SCRIPT" sagepoppy sagepoppy-manager.example.com --clients-dir "$CL")"
[[ "$(cat "$ENV")" == "$before" ]] || bad "a bare re-run changed the env"
no_secrets_in "$out" "$ENV"
ok "re-running (full or bare) keeps every setting and secret"

# 4. add a store
k1="$(getv STORE_API_KEY "$ENV")"
out="$("$SCRIPT" sagepoppy sagepoppy-manager.example.com --clients-dir "$CL" \
  --store "sage-poppy=Sage & Poppy Bottle Shop" --store "sage-poppy-2=Sage & Poppy Two")"
[[ "$(getv STORE_API_KEY "$ENV")" == "$k1" ]] || bad "the primary's key changed"
k2="$(getv STORE_API_KEYS "$ENV")"
[[ "$k2" =~ ^sage-poppy-2=[0-9a-f]{64}$ ]] || bad "second key: $k2"
[[ -f "$CL/sagepoppy/stores/sage-poppy-2.env" ]] || bad "second store file"
grep -q "sage-poppy: kept" <<<"$out" && grep -q "sage-poppy-2: generated" <<<"$out" || bad "key notes"
no_secrets_in "$out" "$ENV"
ok "adding a store keeps the old key and mints one"

# 5. adopt a running stack
OLD="$TMP/old.env"
cat > "$OLD" <<'EOF'
DOMAIN=copperlantern-manager.example.com
LEGACY_DOMAIN=old-manager.example.com
STORE_DOMAIN=copperlantern.example.com
ACME_EMAIL=ops@example.com
DB_PASSWORD=oldDbPassword123
ADMIN_EMAIL=owner@example.com
ADMIN_PASSWORD=oldAdminPassword123
TOTP_REQUIRED=false
STORE_API_KEY=1111111111111111111111111111111111111111111111111111111111111111
STORE_API_KEYS=plateau=2222222222222222222222222222222222222222222222222222222222222222
STORES=vieux-port=Copper Lantern — Vieux-Port,plateau=Copper Lantern — Plateau
VENUE_NAME=Copper Lantern
REGISTRY=registry.example.com
IMAGE_TAG=deadbeef
REPORTING_CURRENCY=CAD
FX_USD_CAD=1.37
EOF
out="$("$SCRIPT" copperlantern copperlantern-manager.example.com --clients-dir "$CL" \
  --from-env "$OLD" --project copper-lantern-manager --pg-volume copper-lantern-manager_manager_pgdata)"
CENV="$CL/copperlantern/.env"
[[ "$(getv DB_PASSWORD "$CENV")" == oldDbPassword123 ]] || bad "adopt db password"
[[ "$(getv ADMIN_PASSWORD "$CENV")" == oldAdminPassword123 ]] || bad "adopt admin password"
[[ "$(getv STORE_API_KEY "$CENV")" == 1111111111111111111111111111111111111111111111111111111111111111 ]] || bad "adopt primary key"
[[ "$(getv STORE_API_KEYS "$CENV")" == plateau=2222222222222222222222222222222222222222222222222222222222222222 ]] || bad "adopt keys"
[[ "$(getv PG_VOLUME "$CENV")" == copper-lantern-manager_manager_pgdata ]] || bad "adopt volume"
[[ "$(getv COMPOSE_PROJECT_NAME "$CENV")" == copper-lantern-manager ]] || bad "adopt project"
[[ "$(getv TENANT_ID "$CENV")" == copperlantern ]] || bad "adopt tenant"
[[ "$(getv PORTAL_BRAND "$CENV")" == copperlantern ]] || bad "adopt brand"
[[ "$(getv DOMAIN_ALIASES "$CENV")" == old-manager.example.com ]] || bad "adopt alias"
[[ "$(getv TOTP_REQUIRED "$CENV")" == false ]] || bad "adopt totp"
[[ "$(getv FX_USD_CAD "$CENV")" == 1.37 ]] || bad "adopt fx"
grep -q "^STORE_DOMAIN=\|^ACME_EMAIL=\|^LEGACY_DOMAIN=" "$CENV" && bad "manager-only keys carried over"
grep -q "^copperlantern-manager.example.com, old-manager.example.com {" "$CL/proxy-sites/copperlantern.caddy" || bad "adopt site hosts"
no_secrets_in "$out" "$CENV"
grep -q "oldDbPassword123\|oldAdminPassword123\|11111111\|22222222" <<<"$out" && bad "adopt printed a secret"
ok "adopting keeps the stack's secrets, keys, database volume and old hostname"

# 5b. taking a store off the adopted client's list (the split): its key and every
# per-store entry go, FX goes with --no-fx; the other stores' keys are untouched
sed -i.bak -e 's/^STORES=.*/STORES=vieux-port=Copper Lantern — Vieux-Port,plateau=Copper Lantern — Plateau,sage-poppy=Sage \& Poppy Bottle Shop/' \
  -e 's/^STORE_API_KEYS=.*/STORE_API_KEYS=plateau=2222222222222222222222222222222222222222222222222222222222222222,sage-poppy=3333333333333333333333333333333333333333333333333333333333333333/' \
  -e 's/^STORE_ZONES=.*/STORE_ZONES=sage-poppy=America\/Los_Angeles/' -e 's/^STORE_CURRENCIES=.*/STORE_CURRENCIES=sage-poppy=USD/' \
  -e 's/^STORE_COUNTRIES=.*/STORE_COUNTRIES=sage-poppy=US/' -e 's/^RETAIL_STORES=.*/RETAIL_STORES=sage-poppy/' "$CENV"
rm -f "$CENV.bak"
out="$("$SCRIPT" copperlantern copperlantern-manager.example.com --clients-dir "$CL" \
  --store "vieux-port=Copper Lantern — Vieux-Port" --store "plateau=Copper Lantern — Plateau" --no-fx)"
[[ "$(getv STORES "$CENV")" == "vieux-port=Copper Lantern — Vieux-Port,plateau=Copper Lantern — Plateau" ]] || bad "split stores"
[[ "$(getv STORE_API_KEYS "$CENV")" == plateau=2222222222222222222222222222222222222222222222222222222222222222 ]] || bad "split keys: $(getv STORE_API_KEYS "$CENV" | cut -c1-20)"
[[ "$(getv STORE_API_KEY "$CENV")" == 1111111111111111111111111111111111111111111111111111111111111111 ]] || bad "split primary key"
for k in STORE_ZONES STORE_CURRENCIES STORE_COUNTRIES RETAIL_STORES FX_USD_CAD; do [[ -z "$(getv "$k" "$CENV")" ]] || bad "split left $k"; done
grep -q "no longer listed: sage-poppy" <<<"$out" || bad "split does not say sage-poppy is dropped"
[[ ! -f "$CL/copperlantern/stores/sage-poppy.env" ]] || bad "split left the store file"
grep -q 33333333 "$CENV" && bad "split kept the dropped key"
ok "taking a store off a client drops its key and per-store settings, keeps the others'"

# 6. refusals
refuse() { local why="$1"; shift; if "$SCRIPT" "$@" >/dev/null 2>&1; then bad "accepted: $why"; fi; }
refuse "a bad client id" "Bad_Id" x.example.com --clients-dir "$CL" --name X --admin-email a@b.c --store a=A --registry r --image-tag t --brand sagepoppy
refuse "an unknown brand" newco x.example.com --clients-dir "$CL" --name X --admin-email a@b.c --store a=A --registry r --image-tag t --brand nope
refuse "no store" newco x.example.com --clients-dir "$CL" --name X --admin-email a@b.c --registry r --image-tag t --brand sagepoppy
refuse "no images" newco x.example.com --clients-dir "$CL" --name X --admin-email a@b.c --store a=A --brand sagepoppy
refuse "a \$ in a name" newco x.example.com --clients-dir "$CL" --name 'X$Y' --admin-email a@b.c --store a=A --registry r --image-tag t --brand sagepoppy
refuse "changing the tenant id" sagepoppy sagepoppy-manager.example.com --clients-dir "$CL" --tenant other
refuse "a duplicate store" newco x.example.com --clients-dir "$CL" --name X --admin-email a@b.c --store a=A --store a=B --registry r --image-tag t --brand sagepoppy
[[ ! -d "$CL/newco" ]] || bad "a refused run left files"
ok "bad input is refused and writes nothing"

# 7. --local: plain HTTP site, non-secure cookies
out="$("$SCRIPT" localco local.localhost --clients-dir "$CL" --name "Local Co" --brand sagepoppy \
  --store "shop=Shop" --admin-email a@example.com --api-image api:local --web-image web:local --local --no-totp)"
grep -q "^http://local.localhost {" "$CL/proxy-sites/localco.caddy" || bad "local site not http://"
[[ "$(getv COOKIE_SECURE "$CL/localco/.env")" == false ]] || bad "local cookies"
[[ "$(getv TOTP_REQUIRED "$CL/localco/.env")" == false ]] || bad "local totp"
[[ "$(getv CLOUD_SYNC_URL "$CL/localco/stores/shop.env")" == http://local.localhost ]] || bad "local store url"
ok "--local writes a plain-HTTP site and store URL"

# 8. the compose file renders with a written env (when docker compose is available)
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  docker compose -p sagepoppy-portal --env-file "$ENV" -f "$HERE/../docker-compose.client.yml" config -q \
    || bad "docker-compose.client.yml does not render with the written env"
  ok "docker-compose.client.yml renders with the written env"
fi

# 9. the third client (a gas station): a US retail store in Central time, its own brand
PH_ARGS=(pronghorn pronghorn-manager.example.com --clients-dir "$CL"
  --name "Pronghorn Fuel & Market" --brand pronghorn --store "pronghorn=Pronghorn Fuel & Market"
  --zone America/Chicago --currency USD --country US --retail
  --admin-email owner@example.com --registry registry.example.com --image-tag abc123)
out="$("$SCRIPT" "${PH_ARGS[@]}" --dry-run)"
[[ ! -d "$CL/pronghorn" ]] || bad "pronghorn dry run wrote files"
grep -q "brand       : pronghorn" <<<"$out" || bad "pronghorn dry run brand"
grep -q "zone: America/Chicago" <<<"$out" || bad "pronghorn dry run zone"
out="$("$SCRIPT" "${PH_ARGS[@]}")"
PENV="$CL/pronghorn/.env"
[[ "$(getv TENANT_ID "$PENV")" == pronghorn ]] || bad "pronghorn tenant"
[[ "$(getv PORTAL_BRAND "$PENV")" == pronghorn ]] || bad "pronghorn brand"
[[ "$(getv STORES "$PENV")" == "pronghorn=Pronghorn Fuel & Market" ]] || bad "pronghorn stores"
[[ "$(getv VENUE_NAME "$PENV")" == "Pronghorn Fuel & Market" ]] || bad "pronghorn name"
[[ "$(getv VENUE_TZ "$PENV")" == America/Chicago ]] || bad "pronghorn zone"
[[ "$(getv STORE_CURRENCIES "$PENV")" == "pronghorn=USD" ]] || bad "pronghorn currency"
[[ "$(getv STORE_COUNTRIES "$PENV")" == "pronghorn=US" ]] || bad "pronghorn country"
[[ "$(getv RETAIL_STORES "$PENV")" == pronghorn ]] || bad "pronghorn retail"
[[ "$(getv REPORTING_CURRENCY "$PENV")" == USD ]] || bad "pronghorn reporting currency"
grep -q "^pronghorn-manager.example.com {" "$CL/proxy-sites/pronghorn.caddy" || bad "pronghorn site"
[[ "$(getv CLOUD_SYNC_URL "$CL/pronghorn/stores/pronghorn.env")" == https://pronghorn-manager.example.com ]] || bad "pronghorn store url"
[[ "$(getv VENUE_TZ "$CL/pronghorn/stores/pronghorn.env")" == America/Chicago ]] || bad "pronghorn store zone"
[[ "$(getv STORE_API_KEY "$PENV")" != "$(getv STORE_API_KEY "$ENV")" ]] || bad "pronghorn shares a key"
no_secrets_in "$out" "$PENV"
ok "a third client (the gas station) gets its own brand, zone, keys and site"

echo "all $PASS passed"
