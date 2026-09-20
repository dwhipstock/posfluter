#!/usr/bin/env bash
# Dead-man freshness check for backups (S5a).
#
# A Litestream sidecar or the pg_dump cron can silently stop, and a silent
# backup gap is worse than none. For each venue's Litestream prefix and for the
# newest pg_dump, this checks the age of the most recent S3 object and TRIPS
# (non-zero exit + user.err syslog line + optional dead-man ping to a
# Healthchecks.io-style URL) when it exceeds the threshold.
#
# Venues are discovered from their live litestream/<slug>/ prefixes, so a newly
# provisioned venue is covered with no edit here. Run from cron every 15 min;
# a non-zero exit is what makes cron/journald surface the failure.
set -uo pipefail

BUCKET="${POS_BACKUP_BUCKET:-pos-backups-842588910054}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="${AWS_REGION}"
VENUE_MAX_AGE="${POS_BACKUP_VENUE_MAX_AGE:-900}"       # 15 min > Litestream's 5-min snapshot heartbeat
PGDUMP_MAX_AGE="${POS_BACKUP_PGDUMP_MAX_AGE:-129600}"  # 36 h (nightly + slack)
# Optional Healthchecks.io-style dead-man URL: pinged on success, /fail on trip.
PING_URL="${POS_BACKUP_HEALTHCHECK_URL:-}"
STATUS_FILE="${POS_BACKUP_STATUS_FILE:-/home/ubuntu/pos/backups/freshness.status}"

now=$(date -u +%s)
stale=0
report=""

# Age (seconds) of the newest object under a prefix; 999999999 if none exist.
newest_age() {
  local prefix="$1" ts epoch
  ts=$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "$prefix" \
         --query 'sort_by(Contents,&LastModified)[-1].LastModified' \
         --output text 2>/dev/null)
  if [ -z "$ts" ] || [ "$ts" = "None" ]; then echo 999999999; return; fi
  epoch=$(date -u -d "$ts" +%s 2>/dev/null || echo 0)
  echo $(( now - epoch ))
}

venues=$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "litestream/" --delimiter / \
           --query 'CommonPrefixes[].Prefix' --output text 2>/dev/null)
if [ -z "$venues" ] || [ "$venues" = "None" ]; then
  report+="CRITICAL no litestream/ prefixes in bucket at all\n"; stale=1
fi
for p in $venues; do
  slug=${p#litestream/}; slug=${slug%/}
  age=$(newest_age "$p")
  if [ "$age" -gt "$VENUE_MAX_AGE" ]; then
    report+="STALE venue $slug: newest replica object ${age}s old (> ${VENUE_MAX_AGE}s)\n"; stale=1
  else
    report+="ok    venue $slug: ${age}s\n"
  fi
done

age=$(newest_age "pg_dump/")
if [ "$age" -gt "$PGDUMP_MAX_AGE" ]; then
  report+="STALE pg_dump: newest ${age}s old (> ${PGDUMP_MAX_AGE}s)\n"; stale=1
else
  report+="ok    pg_dump: ${age}s\n"
fi

mkdir -p "$(dirname "$STATUS_FILE")"
printf "%b" "$report" | sed "s/^/$(date -u +%FT%TZ) /" | tee "$STATUS_FILE"

if [ "$stale" -ne 0 ]; then
  printf "%b" "$report" | logger -t backup-freshness -p user.err
  [ -n "$PING_URL" ] && curl -fsS -m 10 "$PING_URL/fail" >/dev/null 2>&1 || true
  echo "BACKUP FRESHNESS: TRIPPED" >&2
  exit 1
fi
[ -n "$PING_URL" ] && curl -fsS -m 10 "$PING_URL" >/dev/null 2>&1 || true
echo "BACKUP FRESHNESS: OK"
