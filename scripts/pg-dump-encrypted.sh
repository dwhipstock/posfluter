#!/usr/bin/env bash
# Nightly encrypted, off-host Postgres backup for the reporting tier (S5a).
#
# Streams the dump -> gzip -> GPG (asymmetric: ONLY the recipient's PUBLIC key
# lives on this box, so a box compromise cannot decrypt old dumps) -> S3, then
# verifies the upload and prunes the local encrypted copy so nothing bulky
# lingers on the 20 GB root disk. The pre-existing plaintext dumps are left
# alone (we only delete our own *.sql.gz.gpg artifacts).
#
# S3 auth is the EC2 instance profile (no static keys). Runs from cron as the
# ubuntu user; see cloud/infra/README.md for install + the restore procedure.
set -euo pipefail

BUCKET="${POS_BACKUP_BUCKET:-pos-backups-842588910054}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="${AWS_REGION}"
# Isolated keyring holding ONLY the backup public key (see runbook setup).
export GNUPGHOME="${POS_BACKUP_GNUPGHOME:-/home/ubuntu/pos/backups/gpg}"
RECIPIENT="${POS_BACKUP_GPG_RECIPIENT:-pos-backup@example.com}"
COMPOSE="${POS_CLOUD_COMPOSE:-/home/ubuntu/pos/cloud/infra/docker-compose.yml}"
WORKDIR="${POS_BACKUP_WORKDIR:-/home/ubuntu/backups}"
STAMP="$(date -u +%Y-%m-%d-%H%M)"
OUT="$WORKDIR/pos_cloud_${STAMP}.sql.gz.gpg"
KEY="pg_dump/pos_cloud_${STAMP}.sql.gz.gpg"

mkdir -p "$WORKDIR"

# dump -> gzip -> encrypt to the recipient's public key. pipefail (set -o above)
# aborts before upload if any stage of the pipe fails, so we never ship a
# truncated dump.
docker compose -f "$COMPOSE" exec -T db pg_dump -U pos pos_cloud \
  | gzip \
  | gpg --homedir "$GNUPGHOME" --batch --yes --trust-model always \
        --encrypt --recipient "$RECIPIENT" --output "$OUT"

# Upload, then confirm the object size matches before trusting it enough to
# delete the local copy.
aws s3 cp "$OUT" "s3://$BUCKET/$KEY"
local_size=$(stat -c%s "$OUT")
remote_size=$(aws s3api head-object --bucket "$BUCKET" --key "$KEY" \
                --query ContentLength --output text)
if [ "$local_size" != "$remote_size" ]; then
  echo "ERROR: uploaded size $remote_size != local $local_size for $KEY" >&2
  exit 1
fi

# Prune: the encrypted dump is safely off-host now. Only ever delete our own
# encrypted artifacts — never the operator's plaintext baseline dumps.
rm -f "$OUT"
find "$WORKDIR" -maxdepth 1 -name 'pos_cloud_*.sql.gz.gpg' -type f -delete
echo "OK: s3://$BUCKET/$KEY ($remote_size bytes, gpg-encrypted, local pruned)"
