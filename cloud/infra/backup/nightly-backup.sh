#!/usr/bin/env bash
# Nightly off-site backup of every hosted client's portal database.
#
#   cloud/infra/backup/nightly-backup.sh            # all clients (cron, 03:15 UTC)
#   cloud/infra/backup/nightly-backup.sh sagepoppy  # one client
#
# For each client under cloud/infra/clients/<name>/ (a compose.sh + .env):
#   pg_dump -Fc of pos_cloud  ->  s3://$BACKUP_BUCKET/daily/<client>/<YYYY>/<MM>/<DD>/<file>.dump
#   plus a .manifest.json beside it (sha256, size, row counts of the main tables)
# The bucket encrypts with its KMS key, keeps 30 days in S3 Standard, then
# Glacier Deep Archive to 7 years; object lock (governance, 7 years) means the
# server can write backups but never delete or overwrite them. Uploads go
# through the amazon/aws-cli image with the instance role (no keys on disk).
# A local copy is kept for $LOCAL_DAYS days in ~/backups/nightly.
#
# Exit 0 only when every client uploaded. Log: ~/backups/nightly.log
set -euo pipefail

BACKUP_BUCKET="${BACKUP_BUCKET:-posflutter-backups-842588910054}"
AWS_REGION="${AWS_REGION:-us-east-1}"
LOCAL_DAYS="${LOCAL_DAYS:-14}"
AWS_CLI_IMAGE="${AWS_CLI_IMAGE:-amazon/aws-cli:2.27.50}"
INFRA="${INFRA:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
OUT="$HOME/backups/nightly"
mkdir -p "$OUT"
exec >>"$HOME/backups/nightly.log" 2>&1

TABLES=(checks check_lines check_tenders refunds shifts cash_movements catalog_items catalog_categories staff portal_users item_photos events)

log() { echo "$(date -u +%FT%TZ) $*"; }
aws_() { docker run --rm --network host -e AWS_REGION="$AWS_REGION" -v "$OUT:/data" "$AWS_CLI_IMAGE" "$@"; }

clients=("$@")
if [[ ${#clients[@]} -eq 0 ]]; then
  for d in "$INFRA"/clients/*/; do [[ -x "$d/compose.sh" && -f "$d/.env" ]] && clients+=("$(basename "$d")"); done
fi

fail=0
for c in "${clients[@]}"; do
  ts=$(date -u +%Y%m%dT%H%M%SZ); day=$(date -u +%Y/%m/%d)
  name="pos_cloud-$c-$ts"
  if ! db=$("$INFRA/clients/$c/compose.sh" ps -q db) || [[ -z "$db" ]]; then
    log "FAIL $c: db container not running"; fail=1; continue
  fi
  if ! docker exec "$db" pg_dump -U pos -d pos_cloud -Fc -Z 6 >"$OUT/$name.dump"; then
    log "FAIL $c: pg_dump"; rm -f "$OUT/$name.dump"; fail=1; continue
  fi
  counts=""
  for t in "${TABLES[@]}"; do
    n=$(docker exec "$db" psql -U pos -d pos_cloud -Atc "select count(*) from $t" 2>/dev/null || echo null)
    counts+="\"$t\":$n,"
  done
  sha=$(sha256sum "$OUT/$name.dump" | cut -d' ' -f1)
  size=$(stat -c %s "$OUT/$name.dump")
  printf '{"client":"%s","createdAt":"%s","file":"%s.dump","sha256":"%s","bytes":%s,"rowCounts":{%s}}\n' \
    "$c" "$ts" "$name" "$sha" "$size" "${counts%,}" >"$OUT/$name.manifest.json"
  dest="s3://$BACKUP_BUCKET/daily/$c/$day"
  if aws_ s3 cp "/data/$name.dump" "$dest/$name.dump" --only-show-errors --metadata "sha256=$sha" \
     && aws_ s3 cp "/data/$name.manifest.json" "$dest/$name.manifest.json" --only-show-errors; then
    log "OK $c $name.dump $size bytes sha256=$sha"
    date -u +%FT%TZ >"$HOME/backups/last-ok-$c"
  else
    log "FAIL $c: upload"; fail=1
  fi
done

find "$OUT" -type f \( -name '*.dump' -o -name '*.manifest.json' \) -mtime +"$LOCAL_DAYS" -delete
# the deploy script's pre-deploy dumps: keep 30 days
find "$HOME/backups" -maxdepth 1 -name 'pos_cloud-*-pre-*.sql.gz' -mtime +30 -delete
exit $fail
