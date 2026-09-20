#!/usr/bin/env bash
# upgrade-venue.sh <slug> <tag> — roll ONE cloud venue's store container to an
# immutable ECR image tag, gated, with an automatic rollback (S5c / rec 6).
#
# Store migrations are FORWARD-ONLY: a bad image that migrates the venue's SQLite
# forward has no schema down-path. So the rollback artifact is a `sqlite3 .backup`
# snapshot taken BEFORE the upgrade — restoring it reverts data + schema together.
#
# Flow:
#   1. preflight: resolve the venue compose + current image; pull the new image
#      (fail here, before touching anything, if the tag doesn't exist)
#   2. snapshot the venue SQLite (.backup) + integrity-check it  → rollback artifact
#   3. rewrite the compose `image:` line to the new tag; `docker compose up -d`
#   4. GATE: container health + push_hwm caught up (sync alive on the new image)
#   5. on gate failure → restore the snapshot + previous tag, bring it back up, exit 1
#
# Runs from the operator laptop over SSH (same host/key defaults as provision-venue.sh),
# so any keyholder can drive it. Fenced by design: it only ever touches ONE named
# venue's own compose project + volume.
#
# Drill hook: UPGRADE_FORCE_GATE_FAIL=1 forces the gate to fail after a real
# upgrade, so the rollback path can be rehearsed on demand (an un-run rollback is
# as untrustworthy as an un-restored backup).
set -euo pipefail

HOST="${POS_CLOUD_HOST:-ubuntu@44.206.119.59}"
SSH_KEY="${POS_CLOUD_SSH_KEY:-$HOME/.ssh/pos-cloud.pem}"
REMOTE_ROOT="/home/ubuntu/pos"
ECR_REGISTRY="${POS_ECR_REGISTRY:-842588910054.dkr.ecr.us-east-1.amazonaws.com}"
AWS_REGION="${AWS_REGION:-us-east-1}"
HEALTH_TRIES="${POS_HEALTH_TRIES:-30}"       # ×4s  = 2 min for container health
HWM_TRIES="${POS_HWM_TRIES:-15}"             # ×4s  = 1 min for sync to catch up

die() { echo "ERROR: $*" >&2; exit 2; }

SLUG="${1:-}"; TAG="${2:-}"
[ -n "$SLUG" ] && [ -n "$TAG" ] || die "usage: upgrade-venue.sh <slug> <tag>"
echo "$SLUG" | grep -Eq '^[a-z0-9][a-z0-9-]{1,29}$' || die "bad slug"

SSH_MUX_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pos-upgrade.XXXXXX")"
SSH_MUX_OPTS=(-o ControlMaster=auto -o ControlPersist=60s -o "ControlPath=$SSH_MUX_DIR/mux.sock")
cleanup() {
  ssh -i "$SSH_KEY" "${SSH_MUX_OPTS[@]}" -O exit "$HOST" >/dev/null 2>&1 || true
  rm -rf "$SSH_MUX_DIR" 2>/dev/null || true
}
trap cleanup EXIT
run() { ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "${SSH_MUX_OPTS[@]}" "$HOST" "$@"; }

VENUE_DIR="$REMOTE_ROOT/venues/$SLUG"
COMPOSE="$VENUE_DIR/docker-compose.yml"
CONTAINER="store-$SLUG"
VOL="pos-venue-${SLUG}_storedata"
NEW_IMAGE="$ECR_REGISTRY/pos-store:$TAG"

# sqlite over the venue volume via a throwaway alpine (the store image has no
# sqlite3). SQL arrives on stdin so single-quoted literals never fight the quoting.
# The volume is mounted READ-WRITE, not :ro — a read-only mount of a WAL-mode DB
# fails to open (SQLITE_CANTOPEN: WAL readers must map the -shm file). The store
# is always running here, so the -shm already exists owned by uid 999; root just
# opens it (no new files, no ownership change, safe alongside the live writer).
SQLITE_RO="docker run --rm -i -v $VOL:/data alpine sh -c 'apk add --no-cache sqlite >/dev/null 2>&1; exec sqlite3 -batch -noheader /data/pos.db'"
venue_ro() { run "$SQLITE_RO"; }

echo "== upgrade venue '$SLUG' → pos-store:$TAG =="

# --- 1. preflight ---
run "test -f $COMPOSE" || die "no venue compose at $COMPOSE (is '$SLUG' provisioned on this box?)"
PRIOR_IMAGE="$(run "grep -E '^[[:space:]]*image:[[:space:]]*[^[:space:]]*pos-store' $COMPOSE | head -1 | sed -E 's/.*image:[[:space:]]*//'")"
[ -n "$PRIOR_IMAGE" ] || die "could not read current pos-store image from $COMPOSE"
echo "-- current image: $PRIOR_IMAGE"
if [ "$PRIOR_IMAGE" = "$NEW_IMAGE" ]; then echo "-- already on $NEW_IMAGE; nothing to do"; exit 0; fi

echo "-- pulling $NEW_IMAGE (before touching the venue)"
run "aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REGISTRY >/dev/null && docker pull $NEW_IMAGE >/dev/null" \
  || die "ECR pull failed for $NEW_IMAGE — does the tag exist? is ecr:Pull on the instance role?"

# --- pre-upgrade sync state ---
PRE_HWM="$(echo "SELECT COALESCE((SELECT value FROM sync_state WHERE key='push_hwm'),'0');" | venue_ro)"
PRE_MAXOUT="$(echo "SELECT COALESCE(MAX(id),0) FROM sync_outbox;" | venue_ro)"
PRE_HWM="${PRE_HWM:-0}"; PRE_MAXOUT="${PRE_MAXOUT:-0}"
echo "-- pre-upgrade push_hwm=$PRE_HWM  max(outbox)=$PRE_MAXOUT"

# --- 2. snapshot (rollback artifact) ---
TS="$(date +%Y%m%d-%H%M%S)"
SNAP_DIR="$VENUE_DIR/snapshots"
SNAP_NAME="pre-${TAG}-${TS}.db"
run "mkdir -p $SNAP_DIR"
echo "-- snapshot: $SNAP_DIR/$SNAP_NAME"
# rw mount so sqlite can read WAL frames; .backup yields a consistent single file
# even while the store keeps writing (WAL allows concurrent readers).
run "docker run --rm -v $VOL:/data -v $SNAP_DIR:/snap alpine sh -c 'apk add --no-cache sqlite >/dev/null 2>&1 && sqlite3 /data/pos.db \".backup /snap/$SNAP_NAME\"'" \
  || die "snapshot failed — NOT proceeding (no rollback artifact)"
INTEG="$(run "docker run --rm -v $SNAP_DIR:/snap alpine sh -c 'apk add --no-cache sqlite >/dev/null 2>&1 && sqlite3 /snap/$SNAP_NAME \"PRAGMA integrity_check;\"'")"
[ "$INTEG" = "ok" ] || die "snapshot integrity_check=$INTEG — NOT proceeding"
echo "-- snapshot integrity: ok"

# --- 3. rewrite image tag + up ---
# Only the pos-store line — never the litestream sidecar. '#' delimiter: image ref has '/'.
echo "-- rewriting compose image → $NEW_IMAGE and recreating $CONTAINER"
run "sed -i -E 's#^([[:space:]]*)image:[[:space:]]*[^[:space:]]*pos-store[^[:space:]]*#\1image: $NEW_IMAGE#' $COMPOSE"
run "cd $VENUE_DIR && docker compose up -d" >/dev/null

# --- 4. gate: health, then push_hwm caught up ---
gate_pass=1; gate_reason=""
echo "-- gate: waiting for $CONTAINER health"
healthy=0
for i in $(seq 1 "$HEALTH_TRIES"); do
  st="$(run "docker inspect -f '{{.State.Health.Status}}' $CONTAINER" 2>/dev/null || echo starting)"
  [ "$st" = healthy ] && { healthy=1; break; }
  sleep 4
done
[ "$healthy" = 1 ] || { gate_pass=0; gate_reason="container never became healthy"; }

if [ "$gate_pass" = 1 ]; then
  echo "-- gate: waiting for push_hwm to catch up (≥ pre-upgrade max outbox $PRE_MAXOUT)"
  caught=0
  for i in $(seq 1 "$HWM_TRIES"); do
    POST_HWM="$(echo "SELECT COALESCE((SELECT value FROM sync_state WHERE key='push_hwm'),'0');" | venue_ro)"
    POST_HWM="${POST_HWM:-0}"
    # advancing/live: HWM did not regress AND has drained the backlog that existed
    # before the upgrade. (HWM only moves on a 200 from the cloud, so this proves
    # the new image reads its DB, runs the sync loop, and reaches the cloud.)
    if [ "$POST_HWM" -ge "$PRE_HWM" ] && [ "$POST_HWM" -ge "$PRE_MAXOUT" ]; then
      caught=1; echo "-- gate: push_hwm=$POST_HWM (ok)"; break
    fi
    sleep 4
  done
  [ "$caught" = 1 ] || { gate_pass=0; gate_reason="push_hwm did not catch up (stuck at ${POST_HWM:-?}, need ≥ $PRE_MAXOUT)"; }
fi

if [ "${UPGRADE_FORCE_GATE_FAIL:-0}" = 1 ]; then
  gate_pass=0; gate_reason="forced by UPGRADE_FORCE_GATE_FAIL (rollback drill)"
fi

if [ "$gate_pass" = 1 ]; then
  echo "== OK: $SLUG upgraded to pos-store:$TAG (snapshot kept at $SNAP_DIR/$SNAP_NAME) =="
  exit 0
fi

# --- 5. rollback ---
echo "!! GATE FAILED: $gate_reason — rolling back to $PRIOR_IMAGE + snapshot" >&2
run "cd $VENUE_DIR && docker compose stop $CONTAINER" >/dev/null 2>&1 || true
# restore the pre-upgrade DB (reverts any forward-only migration the bad image ran)
run "docker run --rm -v $VOL:/data -v $SNAP_DIR:/snap alpine sh -c 'apk add --no-cache sqlite >/dev/null 2>&1; rm -f /data/pos.db-wal /data/pos.db-shm; cp /snap/$SNAP_NAME /data/pos.db; chown 999:999 /data/pos.db'" \
  || echo "WARN: snapshot restore reported an error — inspect $SNAP_DIR/$SNAP_NAME by hand" >&2
run "sed -i -E 's#^([[:space:]]*)image:[[:space:]]*[^[:space:]]*pos-store[^[:space:]]*#\1image: $PRIOR_IMAGE#' $COMPOSE"
run "cd $VENUE_DIR && docker compose up -d" >/dev/null
echo "-- rollback: waiting for $CONTAINER health"
for i in $(seq 1 "$HEALTH_TRIES"); do
  st="$(run "docker inspect -f '{{.State.Health.Status}}' $CONTAINER" 2>/dev/null || echo starting)"
  [ "$st" = healthy ] && { echo "-- rollback healthy on $PRIOR_IMAGE"; break; }
  sleep 4
done
echo "== ROLLED BACK: $SLUG is on $PRIOR_IMAGE, DB restored from $SNAP_NAME ==" >&2
exit 1
