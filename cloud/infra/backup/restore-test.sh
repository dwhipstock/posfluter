#!/usr/bin/env bash
# Monthly test restore (cron, 1st of the month 04:30 UTC): proves the backups
# in S3 actually restore.
#
#   cloud/infra/backup/restore-test.sh             # every client
#   cloud/infra/backup/restore-test.sh sagepoppy
#
# For each client: fetch the newest daily dump + manifest from S3 (still in
# S3 Standard, so no Glacier wait), check its sha256, restore it into a
# throwaway Postgres (same major version as production, no network, deleted
# afterwards), and compare row counts with the manifest. The result goes to
# s3://$BACKUP_BUCKET/restore-tests/<client>/<date>.json and ~/backups/nightly.log.
# Exit 0 only when every client restored and matched.
set -euo pipefail

BACKUP_BUCKET="${BACKUP_BUCKET:-posflutter-backups-842588910054}"
AWS_REGION="${AWS_REGION:-us-east-1}"
AWS_CLI_IMAGE="${AWS_CLI_IMAGE:-amazon/aws-cli:2.27.50}"
PG_IMAGE="${PG_IMAGE:-postgres:17-alpine}"
INFRA="${INFRA:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
WORK="$(mktemp -d "$HOME/backups/restore-test.XXXXXX")"
exec >>"$HOME/backups/nightly.log" 2>&1
trap 'docker rm -f "pos-restore-test-$$" >/dev/null 2>&1 || true; rm -rf "${WORK:?}"' EXIT

log() { echo "$(date -u +%FT%TZ) restore-test $*"; }
aws_() { docker run --rm --network host -e AWS_REGION="$AWS_REGION" -v "$WORK:/data" "$AWS_CLI_IMAGE" "$@"; }

clients=("$@")
if [[ ${#clients[@]} -eq 0 ]]; then
  for d in "$INFRA"/clients/*/; do [[ -x "$d/compose.sh" && -f "$d/.env" ]] && clients+=("$(basename "$d")"); done
fi

fail=0
for c in "${clients[@]}"; do
  key=$(aws_ s3api list-objects-v2 --bucket "$BACKUP_BUCKET" --prefix "daily/$c/" \
        --query 'sort_by(Contents[?ends_with(Key, `.dump`)], &LastModified)[-1].Key' --output text)
  if [[ -z "$key" || "$key" == "None" ]]; then log "FAIL $c: no backup in S3"; fail=1; continue; fi
  base="${key%.dump}"
  aws_ s3 cp "s3://$BACKUP_BUCKET/$key" /data/b.dump --only-show-errors
  aws_ s3 cp "s3://$BACKUP_BUCKET/$base.manifest.json" /data/b.manifest.json --only-show-errors
  want=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' "$WORK/b.manifest.json")
  got=$(sha256sum "$WORK/b.dump" | cut -d' ' -f1)
  status=ok; detail=""
  if [[ "$want" != "$got" ]]; then status=fail; detail="sha256 mismatch"; fi

  if [[ $status == ok ]]; then
    name="pos-restore-test-$$"
    docker run -d --name "$name" --network none -e POSTGRES_PASSWORD=restoretest -e POSTGRES_USER=pos -e POSTGRES_DB=pos_cloud \
      -v "$WORK:/data:ro" "$PG_IMAGE" >/dev/null
    for _ in $(seq 60); do docker exec "$name" pg_isready -U pos -d pos_cloud >/dev/null 2>&1 && break; sleep 1; done
    sleep 2
    if ! docker exec "$name" pg_restore -U pos -d pos_cloud --no-owner --exit-on-error /data/b.dump; then
      status=fail; detail="pg_restore failed"
    else
      for t in $(python3 -c 'import json,sys; [print(k) for k,v in json.load(open(sys.argv[1]))["rowCounts"].items() if v is not None]' "$WORK/b.manifest.json"); do
        exp=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["rowCounts"][sys.argv[2]])' "$WORK/b.manifest.json" "$t")
        n=$(docker exec "$name" psql -U pos -d pos_cloud -Atc "select count(*) from $t")
        if [[ "$n" != "$exp" ]]; then status=fail; detail+="$t: $n != $exp; "; fi
      done
    fi
    docker rm -f "$name" >/dev/null
  fi
  today=$(date -u +%F)
  printf '{"client":"%s","testedAt":"%s","backup":"%s","status":"%s","detail":"%s"}\n' \
    "$c" "$(date -u +%FT%TZ)" "$key" "$status" "$detail" >"$WORK/result.json"
  aws_ s3 cp /data/result.json "s3://$BACKUP_BUCKET/restore-tests/$c/$today.json" --only-show-errors || true
  log "$status $c $key $detail"
  [[ $status == ok ]] || fail=1
  rm -f "$WORK/b.dump" "$WORK/b.manifest.json" "$WORK/result.json"
done
exit $fail
