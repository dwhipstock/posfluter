#!/usr/bin/env bash
# Provision a venue end to end on the cloud host (M8) — IDEMPOTENT: safe to
# re-run; every step upserts or no-ops. One run gives you:
#   - tenant + venue rows (+ per-venue STORE_API_KEY) in the reporting tier
#   - an optional portal admin for the tenant
#   - the <slug>.<base domain> Route 53 A record (the wildcard covers it too;
#     the explicit record keeps the console inventory honest)
#   - the venue's store container + volume on the cloud host, on the shared
#     network Caddy routes by subdomain (TLS from the wildcard cert, no cert steps)
#
# Usage:
#   scripts/provision-venue.sh <slug> --name "Demo One" [options]
# Options:
#   --tenant <id>          tenant to attach the venue to (default: <slug>)
#   --tenant-name <name>   display name when creating the tenant (default: --name)
#   --tz <zone>            venue timezone (default: America/Toronto)
#   --admin-email <email>  create a portal admin for the tenant (with --admin-password)
#   --admin-password <pw>  password for that admin (TOTP enrolls at first login)
#   --xmx <heap>           store JVM heap (default: 224m — several venues share 2GB)
#   --rotate-key           mint a fresh STORE_API_KEY even if one exists
#   --image-tag <tag>      pull ECR pos-store:<tag> on the box (no local build) —
#                          the versioned-deploy path (S5c). Omit for legacy latest.
#   --build                rebuild the pos-store image from server/ before starting
#   --skip-dns             don't touch Route 53
#
# Requirements on this machine: aws cli (Route 53 perms), ssh key for the host.
set -euo pipefail

HOST="${POS_CLOUD_HOST:-ubuntu@44.206.119.59}"
SSH_KEY="${POS_CLOUD_SSH_KEY:-$HOME/.ssh/pos-cloud.pem}"
ZONE_ID="${POS_ROUTE53_ZONE:-Z02696742N8MNPIZFBQAV}"
BASE_DOMAIN="${POS_BASE_DOMAIN:-example.com}"
ELASTIC_IP="${POS_CLOUD_IP:-44.206.119.59}"
REMOTE_ROOT="/home/ubuntu/pos"
CLOUD_COMPOSE="$REMOTE_ROOT/cloud/infra/docker-compose.yml"
# Backups (S5a): the venue's Litestream sidecar replicates its SQLite DB here.
# S3 auth is the box's instance profile (no keys); this is just the target.
BACKUP_BUCKET="${POS_BACKUP_BUCKET:-pos-backups-842588910054}"
AWS_REGION="${AWS_REGION:-us-east-1}"
# Registry the CI build workflow pushes to (S5c). With --image-tag <sha> the box
# pulls pos-store:<sha> from here (via its instance profile) instead of building.
ECR_REGISTRY="${POS_ECR_REGISTRY:-842588910054.dkr.ecr.us-east-1.amazonaws.com}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE="$REPO_ROOT/cloud/infra/venue-compose.template.yml"
LITESTREAM_CONF="$REPO_ROOT/cloud/infra/litestream.yml"

die() { echo "ERROR: $*" >&2; exit 1; }

# Escape a value for safe use inside a single-quoted SQL literal: double every
# apostrophe (SQL's own escape). Everything user-supplied that lands in SQL goes
# through this so `--name "Bob's Bar"` can't break the transaction or inject
# into the shared cloud DB. crypt()/gen_salt() see the escaped literal exactly
# as Postgres unescapes it back to the original bytes, so hashing is unaffected.
esc() { printf "%s" "$1" | sed "s/'/''/g"; }

# ssh connection multiplexing: run() and rsync fire ~40 times per provision at a
# us-east host — one shared master connection (ControlPersist keeps it warm)
# turns per-call TCP+auth round-trips into cheap channel opens. The socket is
# per-run and torn down at exit.
# The mktemp dir is already per-run and unique, so a fixed short socket name
# inside it is enough — and keeps the path well under macOS's ~104-char AF_UNIX
# limit that %h-%p-%r expansion can blow past on a long $TMPDIR.
SSH_MUX_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pos-provision.XXXXXX")"
SSH_MUX_OPTS=(-o ControlMaster=auto -o ControlPersist=60s -o "ControlPath=$SSH_MUX_DIR/mux.sock")
cleanup() {
  # Close the master (best-effort) and drop the temp socket dir.
  ssh -i "$SSH_KEY" "${SSH_MUX_OPTS[@]}" -O exit "$HOST" >/dev/null 2>&1 || true
  rm -rf "$SSH_MUX_DIR" 2>/dev/null || true
}
trap cleanup EXIT

run() { ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "${SSH_MUX_OPTS[@]}" "$HOST" "$@"; }
psql_run() { run "docker compose -f $CLOUD_COMPOSE exec -T db psql -U pos -d pos_cloud -v ON_ERROR_STOP=1 -tA" <<<"$1"; }

# --- args ---
SLUG="${1:-}"; shift || true
[ -n "$SLUG" ] || die "usage: provision-venue.sh <slug> --name \"Venue Name\" [options]"
NAME="" TENANT="" TENANT_NAME="" TZ_NAME="America/Toronto" ADMIN_EMAIL="" ADMIN_PASSWORD=""
XMX="224m" ROTATE_KEY=0 BUILD_IMAGE=0 SKIP_DNS=0 IMAGE_TAG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2;;
    --tenant) TENANT="$2"; shift 2;;
    --tenant-name) TENANT_NAME="$2"; shift 2;;
    --tz) TZ_NAME="$2"; shift 2;;
    --admin-email) ADMIN_EMAIL="$2"; shift 2;;
    --admin-password) ADMIN_PASSWORD="$2"; shift 2;;
    --xmx) XMX="$2"; shift 2;;
    --rotate-key) ROTATE_KEY=1; shift;;
    --image-tag) IMAGE_TAG="$2"; shift 2;;
    --build) BUILD_IMAGE=1; shift;;
    --skip-dns) SKIP_DNS=1; shift;;
    *) die "unknown option $1";;
  esac
done
# Store image: an immutable ECR ref when --image-tag is given (the versioned path),
# else the legacy locally-built tag. STORE_IMAGE is rendered into the venue compose.
if [ -n "$IMAGE_TAG" ]; then
  STORE_IMAGE="$ECR_REGISTRY/pos-store:$IMAGE_TAG"
else
  STORE_IMAGE="pos-store:latest"
fi
[ -n "$NAME" ] || die "--name is required"
TENANT="${TENANT:-$SLUG}"
TENANT_NAME="${TENANT_NAME:-$NAME}"

# slug becomes a hostname label AND a container name — keep it strict, and
# refuse names that collide with the portal or look reserved
echo "$SLUG" | grep -Eq '^[a-z0-9][a-z0-9-]{1,29}$' || die "slug must be [a-z0-9-], 2-30 chars"
case "$SLUG" in copperlantern|www|api|portal|mail|staging|admin) die "slug '$SLUG' is reserved";; esac
{ [ -z "$ADMIN_EMAIL" ] && [ -z "$ADMIN_PASSWORD" ]; } || { [ -n "$ADMIN_EMAIL" ] && [ -n "$ADMIN_PASSWORD" ]; } \
  || die "--admin-email and --admin-password go together"

# Lightweight input hygiene (esc() below still guards SQL; these just reject
# obviously bad input early so a typo never reaches the shared DB).
[ "${#NAME}" -le 120 ] || die "--name must be <= 120 chars"
if [ -n "$ADMIN_EMAIL" ]; then
  echo "$ADMIN_EMAIL" | grep -Eq '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' \
    || die "--admin-email does not look like an email address"
fi

echo "== venue '$SLUG' (tenant '$TENANT') on $BASE_DOMAIN =="

# --- 1. per-venue store key (plaintext lives ONLY in the venue's .env on the host) ---
VENUE_DIR="$REMOTE_ROOT/venues/$SLUG"
run "mkdir -p $VENUE_DIR && chmod 700 $REMOTE_ROOT/venues $VENUE_DIR" >/dev/null
EXISTING_KEY="$(run "grep -s '^CLOUD_SYNC_API_KEY=' $VENUE_DIR/.env | cut -d= -f2" || true)"
if [ -n "$EXISTING_KEY" ] && [ "$ROTATE_KEY" = 0 ]; then
  STORE_KEY="$EXISTING_KEY"
  echo "-- store key: reusing existing (use --rotate-key to replace)"
else
  STORE_KEY="$(openssl rand -hex 32)"
  echo "-- store key: minted fresh"
fi
KEY_SHA=$(printf %s "$STORE_KEY" | shasum -a 256 | cut -d' ' -f1)

# --- 2. reporting-tier rows (upserts; never disturbs other tenants/venues) ---
# All user-supplied values are esc()'d into their single-quoted SQL literals.
echo "-- cloud rows: tenant/venue/key"
E_TENANT="$(esc "$TENANT")"
E_TENANT_NAME="$(esc "$TENANT_NAME")"
E_SLUG="$(esc "$SLUG")"
E_NAME="$(esc "$NAME")"
E_TZ="$(esc "$TZ_NAME")"
E_KEY_SHA="$(esc "$KEY_SHA")"

# On --rotate-key (or a first-ever key), retire the venue's existing key rows so
# a previously-leaked key stops working the moment the new one is minted. A
# plain re-run (key reused from the .env) leaves the DB untouched.
ROTATE_SQL=""
if [ "$ROTATE_KEY" = 1 ] || [ -z "$EXISTING_KEY" ]; then
  ROTATE_SQL="DELETE FROM store_api_keys WHERE tenant_id='$E_TENANT' AND venue_id='$E_SLUG';"
fi
psql_run "
INSERT INTO tenants (id, name, created_at) VALUES ('$E_TENANT', '$E_TENANT_NAME', now())
    ON CONFLICT (id) DO NOTHING;
INSERT INTO venues (tenant_id, id, name, timezone, subdomain)
    VALUES ('$E_TENANT', '$E_SLUG', '$E_NAME', '$E_TZ', '$E_SLUG')
    ON CONFLICT (tenant_id, id) DO UPDATE
    SET name = EXCLUDED.name, timezone = EXCLUDED.timezone, subdomain = EXCLUDED.subdomain;
$ROTATE_SQL
INSERT INTO store_api_keys (tenant_id, venue_id, key_sha256, label, created_at)
    VALUES ('$E_TENANT', '$E_SLUG', '$E_KEY_SHA', 'provision-venue', now())
    ON CONFLICT (key_sha256) DO NOTHING;
" >/dev/null

if [ -n "$ADMIN_EMAIL" ]; then
  echo "-- portal admin: $ADMIN_EMAIL (TOTP enrolls at first login)"
  # pgcrypto bf == the BCrypt the API verifies; create-only, like Bootstrap.
  # esc() the email + password so an apostrophe in either can't break out of
  # its literal; crypt() hashes the unescaped bytes, so login still works.
  E_ADMIN_EMAIL="$(esc "$ADMIN_EMAIL")"
  E_ADMIN_PASSWORD="$(esc "$ADMIN_PASSWORD")"
  psql_run "
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO portal_users (tenant_id, email, password_hash, totp_enabled, display_name, created_at)
    SELECT '$E_TENANT', '$E_ADMIN_EMAIL', crypt('$E_ADMIN_PASSWORD', gen_salt('bf', 10)), false, 'Owner', now()
    WHERE NOT EXISTS (
        SELECT 1 FROM portal_users WHERE tenant_id = '$E_TENANT' AND email = '$E_ADMIN_EMAIL');
" >/dev/null
fi

# --- 3. DNS (explicit record; the wildcard *.BASE_DOMAIN also covers it) ---
if [ "$SKIP_DNS" = 0 ]; then
  echo "-- route53: UPSERT $SLUG.$BASE_DOMAIN → $ELASTIC_IP"
  aws route53 change-resource-record-sets --hosted-zone-id "$ZONE_ID" --change-batch "{
    \"Changes\": [{\"Action\": \"UPSERT\", \"ResourceRecordSet\": {
      \"Name\": \"$SLUG.$BASE_DOMAIN\", \"Type\": \"A\", \"TTL\": 300,
      \"ResourceRecords\": [{\"Value\": \"$ELASTIC_IP\"}]}}]}" >/dev/null
fi

# --- 4. store image: pull from ECR (versioned) OR build on box (legacy) ---
if [ -n "$IMAGE_TAG" ]; then
  # Versioned path (S5c): the box pulls the immutable image with its instance
  # profile (ecr:Pull added by scripts/aws/ci-ecr-setup.sh) — no build competes
  # with live venue JVMs for the 2 GB, and the exact code is reproducible.
  echo "-- image: pull $STORE_IMAGE from ECR"
  run "aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REGISTRY >/dev/null && docker pull $STORE_IMAGE" \
    || die "ECR pull failed for $STORE_IMAGE (does the tag exist? is ecr:Pull on the instance role?)"
elif [ "$BUILD_IMAGE" = 1 ] || ! run "docker image inspect pos-store:latest" >/dev/null 2>&1; then
  echo "-- image: rsync server/ + build pos-store:latest (a few minutes on first run)"
  rsync -az --delete \
    -e "ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new -o ControlMaster=auto -o ControlPersist=60s -o ControlPath=$SSH_MUX_DIR/mux.sock" \
    --exclude build --exclude .gradle --exclude .kotlin --exclude data \
    --exclude receipts --exclude bills --exclude '*.db*' \
    "$REPO_ROOT/server/" "$HOST:$REMOTE_ROOT/server/"
  run "cd $REMOTE_ROOT/server && docker build -t pos-store:latest ."
else
  echo "-- image: pos-store:latest present (use --build to rebuild)"
fi

# --- 5. render + start the venue container (store + Litestream backup sidecar) ---
echo "-- container: store-$SLUG (+ litestream-$SLUG backup sidecar)"
sed -e "s/__SLUG__/$SLUG/g" -e "s/__BASE_DOMAIN__/$BASE_DOMAIN/g" \
    -e "s/__XMX__/$XMX/g" -e "s#__TZ__#$TZ_NAME#g" \
    -e "s#__STORE_IMAGE__#$STORE_IMAGE#g" \
    -e "s/__BACKUP_BUCKET__/$BACKUP_BUCKET/g" -e "s/__AWS_REGION__/$AWS_REGION/g" \
    "$TEMPLATE" | run "cat > $VENUE_DIR/docker-compose.yml"
# The sidecar bind-mounts ./litestream.yml — ship the shared config next to it.
run "cat > $VENUE_DIR/litestream.yml" < "$LITESTREAM_CONF"
run "umask 177 && printf 'CLOUD_SYNC_API_KEY=%s\n' '$STORE_KEY' > $VENUE_DIR/.env"
run "cd $VENUE_DIR && docker compose up -d" >/dev/null

# --- 6. health gates: container first, then TLS + routing from the outside ---
echo "-- waiting for container health"
for i in $(seq 1 30); do
  STATUS="$(run "docker inspect -f '{{.State.Health.Status}}' store-$SLUG" 2>/dev/null || echo starting)"
  [ "$STATUS" = healthy ] && break
  sleep 4
  [ "$i" = 30 ] && die "store-$SLUG never became healthy — docker logs store-$SLUG on the host"
done
echo "-- waiting for https://$SLUG.$BASE_DOMAIN/health (DNS + wildcard TLS + routing)"
for i in $(seq 1 30); do
  if curl -fsS -m 8 "https://$SLUG.$BASE_DOMAIN/health" >/dev/null 2>&1; then
    echo "== DONE: https://$SLUG.$BASE_DOMAIN is live =="
    echo "Next: portal → Devices → venue '$NAME' → generate a pairing code for each terminal."
    exit 0
  fi
  sleep 5
done
die "container is healthy but https://$SLUG.$BASE_DOMAIN/health is not answering — check DNS propagation and 'docker logs pos-cloud-caddy-1'"
